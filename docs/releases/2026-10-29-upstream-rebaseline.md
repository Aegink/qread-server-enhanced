# 2026-10-29 上游重锚 + 回归护栏

- 版本标识：upstream 3.4.6（`c29186c`）/ 我们的提交见 `git log -1` / 2026-10-29
- 产物：`read.jar` 26,394,410 字节 / SHA-256 `6D452FF81D132AC6B1A57375E36C5B1BDE6CFE6E35CD4130B1499785A32693F8`
- 产物：`libs/book-1.0-SNAPSHOT.jar` 1,405,082 字节 / SHA-256 `218A9DC552FF6F7E5A430951AF6B7CD3720285B78034D13D58E5CA075E444447`
- 部署方式：1Panel 手工替换 `read.jar` + `libs/`，`conf/` 不变

## 背景

1. 后端上游 `autobcb/read` 自 2026-04-08（`c29186c`，tag 3.4.6）后无提交，官方安卓阅读 `gedoor/legado` 只剩公告，原来的对比标准 `Suml1/Legado_Max` 仓库已 404。
2. 本项目此前没有 git，12 万行改动只能靠报告追溯，出问题无法定位与回滚。
3. 现有 154 个测试里没有一条"真实书源从搜索跑到正文"的链路测试，引擎移植改动无法验证。

## 变更

### 引擎（book/）

- `AnalyzeByJSonPath.getList`：把 `read<ArrayList<Any>>` 改成 `ArrayList(read<List<Any>>)`，与 `LegadoTeam/legado` 对齐，修复 JsonPath 返回非 ArrayList 的 list-backed 结果时硬转失败、异常被吞导致"规则正确但列表为空"的问题。

### 测试与文档

- 新增 `RhinoSemanticsCompatibilityTest`（9 项）：Map 取值、for-of/for-in、null、正则截断、chapter 绑定、Java List forEach、typeof；并固定一处已知差异（`'' + String()` 的 typeof 为 `object`）。
- 新增 `WebBookEndToEndRegressionTest`（2 项）：本地 HTTP 站点 + `WBook` 全流程，覆盖搜索 → 详情 → 目录 → 正文；含 XPath 书名、相对地址补全、`{{key}}/{{page}}`、`formatJs`、`##正则##` 剔除广告；另有 JSONPath 搜索端到端。
- 新增 `AnalyzeByJSonPathListTest`（6 项）：`$.`、`[*]`、`$..`、`%%` 合并、缺失路径。
- 新增《维护基线》（上游锚点、可移植清单、同步命令、版本标识规则）与 `docs/releases/` 发布说明规范。

### 依赖与配置

- 无变更（jsoup 仍为 1.16.2，`conf/` 未动）。

## 风险与注意事项

- **后台登录**：`conf/conf.yml` 的 `admin.passwordHash` 必须先用 `java -cp "read.jar:libs/*" web.util.admin.AdminPasswordTool` 生成并填入；`passwordHash` 与旧式 `password` 都为空时任何密码都登不进后台。
- **发布三件套**：`read.jar`、`libs/`、`conf/` 必须同批替换；本次 `libs/` 与 `read.jar` 已由同一次构建产出。
- **没有动 `src/main/resources/static/*`**：那是 App 的 Flutter 构建产物，本次不含 App 变更。
- 引擎差异中"HTML `<a/>` 自闭合解析"一项**未移植**：需要先把 jsoup 从 1.16.2 升到 ≥1.21（上游用 `TagSet`），会影响 JsoupXpath 兼容性，留给独立发布。

## 验证

- JDK 21：`.\gradlew.bat clean build --no-daemon --console=plain` → BUILD SUCCESSFUL（1m21s）。
- 测试：**46 个套件 / 171 个用例 / 0 失败 / 0 错误 / 0 跳过**（较上次 43/154 增加 3 套件 17 用例）。
- 隔离启动（端口 18141，绝对配置路径）：`/` 200、`/admin` 200、`/api/5/appversion` 200（`3.1.0`）、无令牌 `/api/5/getUserInfo` → `NEED_LOGIN`；停止后端口释放；启动日志 `ERROR|Exception` 计数 0。

## 回滚

- 代码回滚：`git reset --hard 9b4f448`（本次之前的提交）或回到上游 `c29186c`。
- 产物回滚：同时回退 `read.jar` + `libs/` + `conf/`，不要只换其中一个。
- 回滚后必查：`/`、`/admin`、`/api/5/appversion`、无令牌鉴权返回 `NEED_LOGIN`、启动日志无 `ERROR`。
