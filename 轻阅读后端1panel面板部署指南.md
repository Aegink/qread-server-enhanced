# 轻阅读后端部署指南

## 概述
本文档用于通过 1Panel 部署“轻阅读”后端。生产环境推荐使用 MySQL；项目也支持 SQLite，但更适合单机测试或小规模使用。  
请在开始前确保已拥有：  
- 一台安装有 1Panel 面板的 Linux 服务器  
- 可访问公网的 IP 地址  
- 基本的文件上传与编辑能力

---

## 步骤一：下载并核对部署包
1. 访问项目发布页：[Releases · autobcb/read](https://github.com/autobcb/read/releases)
2. 找到最新版本，下载名称以 **web** 开头的压缩包（例如：`web-xxx.zip` 或 `web-xxx.tar.gz`）
3. 部署包必须同时包含 `read.jar`、完整 `libs/` 目录和配置模板；**不能只上传 `read.jar`**
4. 发布方提供 SHA-256 时，上传前用 `sha256sum read.jar libs/book-1.0-SNAPSHOT.jar` 核对，避免文件损坏或版本混用


---

## 步骤二：上传并解压部署包
1. 登录服务器面板，进入 **文件管理**
2. 选择一个合适的存放路径，例如 `/www/wwwroot/read`
3. 点击 **上传**，选择刚才下载的压缩包
4. 上传完成后，选中该压缩包并点击 **解压**
5. 确认最终目录至少符合以下结构；若包内只有 `conf/conf.yml`，首次部署时将它复制为根目录 `conf.yml`

```text
/www/wwwroot/read/
├── read.jar
├── libs/
├── conf.yml
├── storage/
├── local/
└── logs/
```

6. 建议将目录权限设置为 `755`、普通文件设置为 `644`；包含数据库密码和管理员密码哈希的 `conf.yml` 必须单独设置为 `600`。`storage/`、`local/`、`logs/` 需归运行 Java 服务的系统用户所有并允许该用户写入

> 安全提示：不建议直接设置为 `777`，以免引入安全风险。若必须开放写权限，可针对特定目录（如日志、缓存目录）单独设置。

---

## 步骤三：创建 MySQL 数据库
1. 在面板中找到 **数据库** 选项
2. 选择 **MySQL**，若未安装则根据提示安装
3. 点击 **创建数据库**，填写以下信息：
   - 数据库名（自定义，如 `read_db`）
   - 用户名（自定义）
   - 密码（建议使用强密码）
4. 创建完成后，建议将数据库权限设置为 **本地服务器可访问**，并为该用户授予对应数据库的全部权限
5. 记录下数据库的连接信息（主机地址、数据库名、用户名、密码），后续配置将使用

---

## 步骤四：编辑配置文件
1. 进入部署目录，找到根目录 `conf.yml` 文件
2. 用文本编辑器打开，将其内容替换为以下配置，并根据实际情况修改标注的部分：

```yaml
solon.dataSources:
  db:
    class: "com.zaxxer.hikari.HikariDataSource"
    driverClassName: com.mysql.cj.jdbc.Driver
    jdbcUrl: "jdbc:mysql://数据库地址:3306/数据库名?characterEncoding=UTF-8&allowMultiQueries=true&serverTimezone=UTC"
    username: "数据库用户名"
    password: "数据库密码"
    maximumPoolSize: 20      # 最大连接数；小内存服务器可按容量调低
    minimumIdle: 5           # 最小空闲连接数
    connectionTimeout: 30000 # 获取连接最多等待 30 秒
    validationTimeout: 5000  # 连接有效性检查最多等待 5 秒
    idleTimeout: 600000      # 空闲连接保留 10 分钟
    maxLifetime: 1800000     # 连接最长使用 30 分钟

admin:
  gonggao: ""           # 公告内容，留空则不显示
  username: "管理员账号"  # 自定义后台管理账号
  passwordHash: "bcrypt哈希" # 禁止填写明文密码，按下方命令生成
  update: false         # 是否开启定时更新任务，共享环境建议关闭
  code: ""              # 预留字段，如需使用请从APP管理后台获取密钥

user:
  allowchange: true     # 是否允许用户自助修改权限
  allowuptxt: true      # 是否允许上传txt文件
  allowimg: false       # 是否启用图片解密功能
  allowcheck: true      # 是否允许校验书源
  source: 1             # 0=不可修改书源，1=可修改后台书源，2=独立书源
  maxsource: 0          # 最大书源数量，0表示无限制
  timeout: 0            # 限制频繁操作，0表示不限制
  proxypng: false       # 是否代理封面图片
  index: 0              # 0=开启Web界面，1=仅允许注册/找回密码，2=关闭Web界面
```

3. 在部署目录执行 `java -cp "read.jar:libs/*" web.util.admin.AdminPasswordTool`，按提示输入两次至少 12 位的管理员密码，将输出的 `$2a$...` bcrypt 哈希完整填入 `admin.passwordHash`
4. 修改完成后保存文件，不要在 `conf.yml` 中添加旧版 `admin.password` 明文字段
5. 在 1Panel 终端中执行 `chmod 600 /www/wwwroot/read/conf.yml`，并确认该文件仅归运行服务的系统用户所有

> 连接池参数适合常规生产部署。MySQL 的 `max_connections` 必须高于本服务的 `maximumPoolSize`，同一数据库上部署多个实例时还要为其他实例和管理连接预留容量。

---

## 步骤五：配置 Java 运行环境
1. 在面板中找到 **网站** 或 **运行环境** 选项
2. 选择 **Java**，点击 **创建运行环境**
3. 根据系统架构安装 **JDK 21 或 JDK 22**；项目使用 JDK 21 工具链构建，不要使用 JDK 17 或更旧版本
4. 设置以下参数：
   - 运行路径：选择部署包解压目录（如 `/www/wwwroot/read`）
   - 启动命令：`java -jar /www/wwwroot/read/read.jar --solon.config.add=/www/wwwroot/read/conf.yml --server.port=8080`
   - 端口：选择一个未被占用的端口（如 `8080`）
5. 确认配置并启动环境

> 必须使用配置文件绝对路径。Solon 对资源中的 `./conf.yml` 可能按 JAR 所在目录解析，而 1Panel 的实际工作目录未必与部署目录一致；显式传入 `--solon.config.add` 可避免“缺少 `admin.username`”一类启动失败。

启动后先查看日志，直到出现 `App: End loading elapsed=...ms` 和 `Started`。若使用 systemd 或 1Panel 守护进程，请配置失败自动重启，并设置合理的 JVM 内存上限，例如小内存服务器可在 `java` 后增加 `-Xms256m -Xmx768m`。

### 如果服务器没有全局 IPv6（重要）

很多站点（含 Cloudflare 后面的）会同时返回 A 与 AAAA 记录。JVM 默认**优先走 IPv6**；如果宿主机或
容器没有可用的全局 IPv6，连接会**立刻失败**而不是回退到 IPv4，表现为：

- App「从网络拉取书源/订阅」失败，提示「没解析到」
- 书源搜索/正文间歇性失败
- 后端日志里只有一句 `GET请求失败` 或直接没日志

先在服务器上确认：

```bash
curl -4 -o /dev/null -w '%{http_code}\n' https://www.baidu.com   # 200 说明 IPv4 正常
curl -6 -o /dev/null -w '%{http_code}\n' https://www.baidu.com   # 000 说明 IPv6 不通
```

IPv6 不通时，在启动命令里强制 IPv4（这一条同时修复代拉、引擎抓书源等所有出网请求）：

```bash
java -Djava.net.preferIPv4Stack=true -jar /www/wwwroot/read/read.jar \
     --solon.config.add=/www/wwwroot/read/conf.yml --server.port=8080
```

1Panel 运行环境改启动命令后需要**重建容器/重启环境**才会生效（`docker compose up -d --force-recreate`）。

---

## 步骤六：配置反向代理
如果通过 1Panel 网站或 nginx 暴露服务，请不要将 Java 后端端口直接开放到公网，并在反向代理配置中**覆写**客户端可提交的转发头：

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

不要把客户端原始 `X-Forwarded-For` 直接透传给后端。保存后重载 nginx，并确认安全组和防火墙只允许 nginx 访问 Java 后端端口。

### 如果反向代理前面还有 WAF（重要）

书源/RSS 规则里**合法包含** `eval(` / `include(` / `require(` / `define(` 这类 JS 关键字
（`@js:` 规则、`<js>` 目录规则必然用到）。通用 WAF 的「参数注入」规则会把它们当成攻击载荷，
直接返回 403 拦截页 —— App 侧只显示「网络错误」，后端日志里查不到任何异常。

给这些**已鉴权**的接口放行 WAF 内容检测：

```bash
# 1. 按需调整放行路径（默认 /api/5/ 与 /admin，每行一个前缀）
vi conf/waf-skip-paths.txt

# 2. 生成反代配置并重载（先 nginx -t 校验，失败自动删除）
conf/scripts/apply-waf-skip.sh \
  --site  你的域名 \
  --backend http://127.0.0.1:8080 \
  --openresty 1Panel-openresty-xxxx     # 裸机 nginx 安装则省略此参数

# 3. 需要恢复 WAF 检测时
conf/scripts/apply-waf-skip.sh --site 你的域名 --revert
```

脚本会写入 `<站点目录>/proxy/zz-waf-skip-<域名>.conf`，只对清单里的路径关闭 WAF 内容检测，
站点其它路径不受影响。用的是云厂商 WAF（阿里云 / 腾讯云 / Cloudflare）时，把
`conf/waf-skip-paths.txt` 里的路径加到它的白名单即可。

> 判断是不是这个问题：看反向代理的 access log 有没有 `403`，以及 WAF 日志里是否命中
> 「参数注入 / args」类规则。只看应用日志会以为一切正常。

---

## 步骤七：验证部署
1. 等待日志出现 `App: End loading elapsed=...ms`
2. 在服务器终端执行以下健康检查：

```bash
curl -fsS http://127.0.0.1:8080/ >/dev/null
curl -fsS http://127.0.0.1:8080/admin >/dev/null
curl -fsS http://127.0.0.1:8080/api/5/appversion
curl -sS http://127.0.0.1:8080/api/5/getgroup
```

版本接口应返回 `3.1.0`；最后一个无令牌请求应返回包含 `NEED_LOGIN` 的 JSON。随后再通过反向代理域名检查：

3. 在浏览器中访问：
   - 后端地址：`http://你的服务器IP:你设置的端口`
   - 管理后台：`http://你的服务器IP:你设置的端口/admin`
4. 若能看到登录页面或默认界面，说明基础连通性正常
5. 使用配置文件中设置的 **管理员账号和密码** 登录管理后台，进行进一步设置
6. 检查 `logs/`，确认没有新增 `ERROR`、`Exception`、`failed`，也没有 `bearer`、`authorization` 等凭据明文

```bash
grep -RniE 'ERROR|Exception|failed|bearer|authorization' /www/wwwroot/read/logs
```

---

## 步骤八：已部署实例安全更新

以下流程适用于已经运行在 `/www/wwwroot/read` 的实例。若实际路径或端口不同，请统一替换命令中的路径和 `8080`。

### 1. 准备完整更新包

1. 更新包必须包含同一次构建产生的 `read.jar` 和完整 `libs/`，不能只上传 JAR
2. 在 1Panel 文件管理中将更新包解压到独立暂存目录，例如 `/www/wwwroot/read-release`，不要直接覆盖运行目录
3. 确认暂存目录中存在 `read.jar`、`libs/` 和 `libs/book-1.0-SNAPSHOT.jar`
4. 核对本次成品哈希：

```bash
sha256sum /www/wwwroot/read-release/read.jar
sha256sum /www/wwwroot/read-release/libs/book-1.0-SNAPSHOT.jar
```

当前成品应分别为：

```text
F8309BF7BE388B94AB6FD67012C1B8F8BE971DAA2ABFABC93AA7A12C26F5CA53  read.jar
237738ABD48DC6B2E6C7D9E72D614EDFADBD33710092BB975FDC5BC4E79B3B3A  libs/book-1.0-SNAPSHOT.jar
```

### 2. 停服并备份

1. 在 1Panel 的 **网站/运行环境/Java** 中停止轻阅读服务，确认 8080 端口已释放
2. 使用 1Panel 的数据库备份功能完整备份 MySQL 数据库
3. 在 1Panel 终端执行以下命令，备份程序、配置和本地数据：

```bash
cd /www/wwwroot
backup_dir="/www/backup/read-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup_dir"
cp -a read/conf.yml read/storage read/local "$backup_dir/"
printf '备份目录：%s\n' "$backup_dir"
```

如果使用 SQLite，还必须把实际数据库文件一并复制到该备份目录。不要省略数据库备份。

### 3. 替换程序文件

只替换程序文件，**保留线上 `conf.yml`、`storage/`、`local/`、`logs/` 和数据库**：

```bash
cd /www/wwwroot/read
mv read.jar "$backup_dir/read.jar.pre-update"
mv libs "$backup_dir/libs.pre-update"
install -m 644 /www/wwwroot/read-release/read.jar ./read.jar
cp -a /www/wwwroot/read-release/libs ./libs
```
这一步会把旧程序文件直接移入备份目录，不会重复占用一份空间。`backup_dir` 变量必须与上一步在同一个终端会话中执行；若终端已关闭，请把它改成刚才输出的实际备份目录。替换后按 1Panel Java 运行用户修正所有者，例如：

```bash
chown -R 运行用户:运行用户 /www/wwwroot/read/read.jar /www/wwwroot/read/libs
chmod 600 /www/wwwroot/read/conf.yml
```

不要照抄“运行用户”，应替换为 1Panel 中该 Java 进程的真实系统用户。

### 4. 处理旧版管理员配置

数据库结构无需手工迁移。若线上 `conf.yml` 仍只有旧版 `admin.password`，先用新包生成 bcrypt 哈希：

```bash
cd /www/wwwroot/read
java -cp "read.jar:libs/*" web.util.admin.AdminPasswordTool
```

将输出的哈希写入 `admin.passwordHash`，确认能登录后删除旧的 `admin.password` 明文字段。其他线上配置不要用模板整体覆盖。

### 5. 启动与验收

在 1Panel 中确认启动命令仍为绝对配置路径，然后启动服务：

```text
java -jar /www/wwwroot/read/read.jar --solon.config.add=/www/wwwroot/read/conf.yml --server.port=8080
```

执行健康检查：

```bash
curl -fsS http://127.0.0.1:8080/ >/dev/null
curl -fsS http://127.0.0.1:8080/admin >/dev/null
curl -fsS http://127.0.0.1:8080/api/5/appversion
curl -sS http://127.0.0.1:8080/api/5/getgroup
grep -RniE 'ERROR|Exception|failed|bearer|authorization' /www/wwwroot/read/logs
```

验收标准：前三个接口成功，版本接口返回 `3.1.0`，无令牌接口返回 `NEED_LOGIN`，后台可以登录，新增日志没有异常或凭据明文。确认正常后再删除 `/www/wwwroot/read-release`；建议至少保留一份最近可用备份。

### 6. 更新失败时回滚

立即停止服务，把本次 `read.jar` 和 `libs/` 移出运行目录，再从同一备份目录恢复旧程序文件；数据库发生异常时同时使用 1Panel 恢复对应数据库备份。恢复后重新启动并重复健康检查。禁止把新版 JAR 与旧版 `libs/` 混合回滚。

---

## 常见问题
- **端口无法访问**：检查服务器防火墙和安全组是否放行对应端口
- **数据库连接失败**：确认数据库地址、用户名、密码是否正确，并确保数据库允许当前服务器 IP 连接
- **提示缺少 `admin.username`**：确认启动命令包含绝对 `--solon.config.add=/www/wwwroot/read/conf.yml`，且运行用户可以读取该文件
- **出现 `ClassNotFoundException` / `NoClassDefFoundError`**：确认 `libs/` 与 `read.jar` 来自同一版本，且没有漏传或混入旧依赖 JAR
- **Java 环境启动报错**：执行 `java -version` 确认为 21/22，并查看 `logs/` 中的首个异常，而不是只反复重启
- **升级后异常**：停止服务后同时替换 `read.jar` 和 `libs/`，不要热覆盖正在使用的 JAR；升级前备份 `conf.yml`、数据库、`storage/` 和 `local/`
- **App 里保存书源/订阅失败，提示「网络错误」，后端日志却没有任何异常**：几乎一定是**反向代理或
  WAF 拦掉了请求**。书源/RSS 规则里合法包含 `eval(` / `include(` / `require(` 等 JS 关键字，
  通用 WAF 会把它判成攻击载荷并返回 403 拦截页，App 只能显示「网络错误」。按「步骤六」里的
  《如果反向代理前面还有 WAF》一节放行对应路径即可；排查时先看反代的 access log 有没有 403，
  而不是只看应用日志。
- **App 从网络拉取书源/订阅失败，提示「没解析到」**：先看后端日志有没有 `上游返回 <code>`。
  若上游状态码正常，问题多半在服务器出网——用 `curl -4` / `curl -6` 对比同一地址，IPv6 不通时
  在启动命令加 `-Djava.net.preferIPv4Stack=true`（见「步骤五」）。

---

## 完成
完成上述健康检查、后台登录和日志检查后，部署才算完成。后续可在管理后台添加经过审查的可信书源、管理用户，并通过 APP 或 Web 界面使用阅读服务。
