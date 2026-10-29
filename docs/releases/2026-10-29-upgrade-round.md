# 2026-10-29 依赖安全 + 引擎能力升级（M1~M4）

- 版本标识：upstream 3.4.6（`c29186c`）/ 本次提交见 `git log -1` / 2026-10-29
- 产物：`read.jar` 26,409,714 字节 / SHA-256 `F3F612FB8B02B9ACC56CE73259E04A0492D36E5DF8666B9647F75AF044B91938`
- 产物：`libs/book-1.0-SNAPSHOT.jar` 1,445,493 字节 / SHA-256 `8602A87A9AEDF7C31A700FCDBFB989F3B28FA3A38F5D776A32E7E4531EA1BA36`
- 部署方式：替换 `read.jar` + `libs/`；`conf/` 不变

## 背景

《升级计划-2026-10》研究稿列出 4 个已知漏洞（jsoup ×2、bcpkix ×1、rhino ×1）与 12 项引擎能力缺口，
本次按 M1（依赖安全 + 解析对齐）→ M2（引擎能力）→ M3（大版本）→ M4（护栏）全部落地，Solon 4 除外。

## 变更

### 依赖与配置

| 依赖 | 前 | 后 | 说明 |
|---|---|---|---|
| jsoup | 1.16.2 | **1.23.2** | 修 GHSA-65r4-943x-97jj、GHSA-pmhh-3w7g-xqp8 |
| bcpkix-jdk18on | 1.80 | **1.86** | 修 GHSA-wg6q-6289-32hp |
| rhino | 1.8.0 | **1.8.1** | 修 GHSA-3w8q-xq97-5j7x（1.9.x 移除了 VMBridge，需先改 VMBridgeReflect） |
| gson | 2.11.0 | 2.15.0 | — |
| sqlite-jdbc | 3.49.1.0 | 3.54.1.0 | — |
| okhttp / logging-interceptor | 4.12.0 | **5.5.1** | 编译零改动 |
| json-path | 2.9.0 | **3.0.0** | 6 项 JSONPath 列表用例全绿 |
| Kotlin | 2.1.0 | **2.4.21** | 同时清掉 jackson-module-kotlin 2.13.5 的 force |
| coroutines | 1.10.1 | 1.11.0 | — |

另外：`kotlin.io.createTempFile` 在 Kotlin 2.4 升为 ERROR 级弃用，测试改用 `File.createTempFile`；
测试 JVM 固定 768m 堆 + 单 fork（本机内存紧张时多 worker 会 native malloc 失败）。

### 引擎（book/）

- **HTML 自闭合解析**：新增 `SourceHtmlParser.parseSourceHtml`（`TagSet` + `Tag.SelfClose`，上游 #1182），
  AnalyzeByJSoup / AnalyzeByXPath 统一走它，老书源的 `<a/>` 不再把兄弟节点吞进 `<a>`
- **URL 网络选项**：`UrlOption` 新增 `timeout` / `followRedirects`，落到每链接的 OkHttp readTimeout 与 followRedirects
- **登录头同站限制**：登录态请求头只发给书源本站（上游 #1099），跨域需认证头的书源改用 urlOption `headers`
- **规则级局部变量**：新增 `setLocal` / `setRuleName`；`java.put` 现在一定会写进本级内存，`java.get` 优先读它
- **书源级并发**：新增 `SourceLock`，JS 侧 `java.tick` / `java.lock` / `java.singleFlight`（默认等待 15s）；
  singleFlight 会把同书源同名字的并发调用合并跳过
- **JSONPath list-backed 结果**：`getList` 兼容非 ArrayList 的 List（上一轮已修，本次保留回归）

### 测试

新增 8 个套件 / 17 个用例：`SourceHtmlParsingTest`、`AnalyzeUrlNetworkOptionsTest`、`AnalyzeUrlLoginHeaderTest`、
`AnalyzeRuleLocalBindingsTest`、`SourceConcurrencyTest`、`RealWorldSourceRegressionTest`（真源反序列化 + GBK 解码）、
`RhinoSemanticsCompatibilityTest`、`AnalyzeByJSonPathListTest`、`WebBookEndToEndRegressionTest`。

## 风险与注意事项

- **okhttp 5**：本仓库未用已删 API，但重定向/超时/拦截器行为有细微差异，建议上线后抽测 2~3 个真实书源的搜索与正文。
- **rhino 仍为 1.8.1**：1.9.x 移除了 `VMBridge`，`com.script.rhino.VMBridgeReflect` 需要改写后才能升。
- **后台登录**：`conf/conf.yml` 的 `admin.passwordHash` 必须已生成，否则任何密码都登不进后台。
- **发布三件套**：`read.jar`、`libs/`、`conf/` 必须同批替换。
- **未动**：`src/main/resources/static/*`（App 的 Flutter 产物）与 `appversion`（3.1.0）。
- **未升级**：solon-parent 3.3.2-M1 → 4.1.1（编译报 `Tran` 注解、`org.noear.snack` 序列化、`ONodeAttr` 三类阻断，
  见《维护基线》第九节）；mysql-connector-j 9.1.0 → 26.x 也未动（SQLite 部署不受影响）。

## 验证

- JDK 21 `clean build -x test` 成功；`:test` 与 `:book:test` 分模块跑完
- 测试：**52 个套件 / 188 个用例 / 0 失败 / 0 错误 / 0 跳过**
- 隔离启动（端口 18142）：`/` 200、`/admin` 200、`/api/5/appversion` 200（`3.1.0`）、无令牌 `/api/5/getUserInfo` → `NEED_LOGIN`；
  停止后端口释放；启动日志 `ERROR|Exception` 计数 0

## 回滚

- 按里程碑单提交回退：`fa6aac1`（M1 依赖）→ `b499f9d`（M1 引擎）→ `c5a3490`/`288d6aa`/`49f3b27`（M2）→
  `139fd0e`（M3 大版本）→ `16ce734`（M4 护栏）；或整体回退到 `669e147`。
- 产物同步回退，三件套一起换。
- 回滚后必查：`/`、`/admin`、`/api/5/appversion`、无令牌鉴权、日志无 `ERROR`。
