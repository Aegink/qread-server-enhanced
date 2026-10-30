#!/bin/bash
# 按 conf/waf-skip-paths.txt 生成「跳过 WAF 内容检测」的反向代理配置，并校验重载。
#
# 背景：书源/RSS 规则里合法包含 eval( / include( / require( 等 JS 关键字，通用 WAF 会把它
# 当作攻击载荷直接返回 403；App 侧只看到「网络错误」，服务端日志里没有任何异常。
#
# 用法：
#   conf/scripts/apply-waf-skip.sh --site qreadbackend.xyz
#   conf/scripts/apply-waf-skip.sh --site qreadbackend.xyz --dry-run
#   conf/scripts/apply-waf-skip.sh --site qreadbackend.xyz --revert
#
# 可选参数：
#   --paths <文件>        路径清单，默认 conf/waf-skip-paths.txt
#   --site-dir <目录>     站点目录，默认 /opt/1panel/www/sites/<site>
#   --backend <地址>      后端地址，默认 http://127.0.0.1:8080
#   --openresty <容器名>  Nginx 容器名；留空表示 nginx 直接装在宿主机
set -euo pipefail

SITE=""
PATHS_FILE="$(cd "$(dirname "$0")/.." && pwd)/waf-skip-paths.txt"
SITE_DIR=""
BACKEND="http://127.0.0.1:8080"
OPENRESTY=""
DRY_RUN=0
REVERT=0

usage() {
  sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --site) SITE="${2:?缺少域名}"; shift 2 ;;
    --paths) PATHS_FILE="${2:?缺少文件}"; shift 2 ;;
    --site-dir) SITE_DIR="${2:?缺少目录}"; shift 2 ;;
    --backend) BACKEND="${2:?缺少地址}"; shift 2 ;;
    --openresty) OPENRESTY="${2:?缺少容器名}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --revert) REVERT=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "未知参数: $1" >&2; usage 1 ;;
  esac
done

[ -n "$SITE" ] || { echo "必须用 --site 指定站点域名" >&2; usage 1; }
[ -n "$SITE_DIR" ] || SITE_DIR="/opt/1panel/www/sites/$SITE"
PROXY_DIR="$SITE_DIR/proxy"
TARGET="$PROXY_DIR/zz-waf-skip-$SITE.conf"

nginx_cmd() {
  if [ -n "$OPENRESTY" ]; then
    docker exec "$OPENRESTY" sh -c "$1"
  else
    sh -c "$1"
  fi
}

reload() {
  if [ "$DRY_RUN" = "1" ]; then
    echo "[dry-run] 跳过 nginx -t 与 reload"
    return 0
  fi
  if nginx_cmd 'nginx -t' 2>&1 | tail -2; then
    nginx_cmd 'nginx -s reload'
    echo "✅ 已重载 Nginx"
  else
    echo "❌ nginx -t 校验失败，已回滚" >&2
    return 1
  fi
}

if [ "$REVERT" = "1" ]; then
  if [ -f "$TARGET" ]; then
    rm -f "$TARGET"
    echo "已删除 $TARGET"
    reload
    echo "↩️ 已恢复 WAF 内容检测（放行配置已移除）"
  else
    echo "没有找到 $TARGET，无需回滚"
  fi
  exit 0
fi

[ -d "$PROXY_DIR" ] || { echo "站点目录不存在：$PROXY_DIR（1Panel 站点目录通常是 /opt/1panel/www/sites/<域名>）" >&2; exit 1; }
[ -f "$PATHS_FILE" ] || { echo "路径清单不存在：$PATHS_FILE" >&2; exit 1; }

mapfile -t PATHS < <(grep -vE '^[[:space:]]*(#|$)' "$PATHS_FILE" | sed 's/[[:space:]]*$//')
if [ "${#PATHS[@]}" -eq 0 ]; then
  echo "路径清单为空，什么都没做" >&2
  exit 1
fi

{
  echo "# 由 conf/scripts/apply-waf-skip.sh 生成，请勿手改；改路径请编辑 conf/waf-skip-paths.txt"
  echo "# 缘由：书源/RSS 规则含 eval( / include( / require( 等 JS 关键字，会被通用 WAF 误判为攻击"
  echo "# 删除本文件并重载 Nginx 即可恢复 WAF 内容检测。"
  for p in "${PATHS[@]}"; do
    echo
    echo "location ^~ $p {"
    echo "    access_by_lua_block { }"
    echo "    proxy_pass $BACKEND;"
    echo "    proxy_set_header Host \$host;"
    echo "    proxy_set_header X-Real-IP \$remote_addr;"
    echo "    proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;"
    echo "    proxy_set_header Upgrade \$http_upgrade;"
    echo "    proxy_set_header Connection \$http_connection;"
    echo "    proxy_set_header X-Forwarded-Proto \$scheme;"
    echo "    proxy_http_version 1.1;"
    echo "}"
  done
} > "$TARGET.tmp"

echo "放行路径（来自 $PATHS_FILE）："
printf '  %s\n' "${PATHS[@]}"

if [ "$DRY_RUN" = "1" ]; then
  echo "[dry-run] 将写入 $TARGET："
  cat "$TARGET.tmp"
  rm -f "$TARGET.tmp"
  exit 0
fi

mv "$TARGET.tmp" "$TARGET"
echo "已写入 $TARGET"
if ! reload; then
  rm -f "$TARGET"
  reload || true
  exit 1
fi
echo "ℹ️ 若你的 WAF 不是基于 OpenResty/nginx（例如云厂商 WAF），把上面这些路径加到它的白名单即可。"
