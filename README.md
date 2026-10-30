# 轻阅读后端 · 增强维护版

> **本仓库是 [`autobcb/read`](https://github.com/autobcb/read)（轻阅读 App 官方后端）的社区维护分支。**
> 原作者：**q9uo11**（GitHub [@autobcb](https://github.com/autobcb)）。上游最后一笔提交是
> `c29186c`（tag `3.4.6`，2026-04-08），此后停更。本分支从该提交继续维护，**完整保留原作者的版权与
> 许可声明**，并在此致谢 —— 没有上游这份后端，就没有这个项目。
>
> 上游 README 原文（App 用法、书源权限、自定义包名等）见 **[README.upstream.md](README.upstream.md)**，
> 未做任何改动。

---

## 一、上游与致谢

| 来源 | 作者 | 在本项目中的角色 |
|---|---|---|
| [`autobcb/read`](https://github.com/autobcb/read) | q9uo11（autobcb） | **本项目的基础**：Solon Web 层、MyBatis-Plus 数据层、多端同步接口、数据库与部署形态。MIT 许可 |
| [`LegadoTeam/legado`](https://github.com/LegadoTeam/legado) | LegadoTeam 及 legado 全体贡献者 | **书源解析引擎的移植来源**（`book/` 下的 analyzeRule / help / http / localBook / rss）。GPL-3.0 |
| [`HapeLee/legado-with-MD3`](https://github.com/HapeLee/legado-with-MD3) | HapeLee 及贡献者 | 引擎行为的交叉校验基线。GPL-3.0 |
| [`rimvoid/legado`](https://github.com/rimvoid/legado)（阅读 Archive） | rimvoid（Rimchars） | 提交规范、发布说明与测试写法的参考。GPL-3.0 |
| [`gedoor/legado`](https://github.com/gedoor/legado) | gedoor 及贡献者 | 阅读 3.0 原始项目（仓库现仅存公告），规则语义的历史出处 |

本项目相对上游 `c29186c` 累计 **23 个提交 / 219 个文件 / +14,464 −1,191 行**。完整维护规则见
[`维护基线.md`](维护基线.md)，每次可部署变更见 [`docs/releases/`](docs/releases/)。

---

## 二、与上游的差异

### 1. 安全（上游未涉及，本分支补齐）

- 未鉴权/越权接口：SSRF、任意图片上传、WebView 回传投毒、本地书路径越权、跨用户缓存读取
- 凭据：用户密码升级 bcrypt（旧哈希登录后迁移）、管理员密码改为 `admin.passwordHash`、token 安全随机 +
  过期 + 兼容迁移、`/qapi/*` 恒定时间比较与失败限流
- 后台：session CSRF 防护、后台路由方法约束
- 日志与隐私：删除 Cookie 明文刷屏，URL / Header / 验证码 / 令牌统一脱敏
- 多租户：Cookie / Cache / WebSocket / WebView / 本地书解析按 `userid` 隔离
- 登录态请求头只发同站（跨域请求不再带出 Cookie/Authorization）
- 2026-10-30 补洞（上一轮遗漏）：`/api/5/listen`、`/api/5/getjson` 之前没走 SSRF 校验（已补，含跳转目标）；
  Cookie 落库加密由硬编码 key 改为 `v2:` 随机 IV + 环境变量 `QREAD_COOKIE_KEY`（存量数据兼容）；
  后台列表 `order` 参数加白名单（防 ORDER BY 注入）；`/api/` 的未预期异常统一回可读 JSON，
  不再只给空 500

### 2. 解析引擎（从 legado 系移植，逐函数对照，不整包覆盖）

- `AnalyzeRule` / `AnalyzeUrl` / `AnalyzeByJSoup` / `AnalyzeByXPath` / `AnalyzeByJSonPath` 能力对齐
- `JsExtensions` 补齐服务端可用函数；新增 `java.tick` / `java.lock` / `java.singleFlight`（书源级并发）
- `SourceHtmlParser`：`<a/>` 这类老书源自闭合写法按 XHTML 语义解析
- URL 选项补齐 `timeout` / `followRedirects`，与 `HEAD` / `bodyJs` / `dnsIp` / `webJs` 等并存
- `QueryTTF`：glyphId、复合字形、cmap 0/4/6/12
- 内置 **CryptoJS 4.2.0**：规则里可直接用 `CryptoJS.MD5(...)` / `CryptoJS.AES.decrypt(...)`（真源实测暴露的缺口）
- 本地书：TXT / EPUB / UMD / CBZ / MOBI / AZW / AZW3 / PDF 全格式与解析缓存
- RSS、段评、目录反序、`formatJs` 标题格式化、`preUpdateJs` 预更新脚本

### 3. 依赖与运行时（上游停留在 2026-04 的版本）

| 依赖 | 上游 | 本分支 |
|---|---|---|
| Solon | 3.3.2-M1（里程碑版） | **4.1.1** |
| Kotlin | 2.1.0 | **2.4.21** |
| jsoup | 1.16.2 | **1.23.2**（修 2 个 CVE） |
| okhttp | 4.12.0 | **5.5.1** |
| rhino | 1.8.0 | **1.9.2**（修 CVE，改用 `Context.enter(Context)`） |
| json-path | 2.9.0 | **3.0.0** |
| gson | 2.11.0 | 2.15.0 |
| bcpkix-jdk18on | 1.80 | **1.86**（修 CVE） |
| sqlite-jdbc | 3.49.1.0 | 3.54.1.0 |
| mysql-connector-j | 9.1.0 | 26.10.0 |
| mybatis-plus | 3.5.9 | 3.5.17 |
| Gradle | 8.10 | **8.14.6**（Kotlin 2.5 要求 ≥8.14.4；双栈网络需 `systemProp.java.net.preferIPv4Stack=true`） |
| layui（后台 UI） | 2.9.18 | **2.13.9** |
| jQuery UI | 1.8.22（2012 年） | **已移除**（登录/注册页零调用，升级到 1.14 反而需要补 `window.jQuery` 垫片） |
| 运行镜像 | `1panel/java:22`（JDK 22.0.2，非 LTS） | **`eclipse-temurin:21-jre`**（JDK 21.0.13 LTS） |

### 4. 测试与文档

- **58 个测试套件 / 210 个用例**（上游为 0）
- 覆盖：本地 HTTP fixture 的书源端到端（搜索→详情→目录→正文）、JSONPath 列表规则、Rhino 语义、
  本地书各格式、RSS、存储兼容、凭据与 CSRF、并发工具、真实书源 JSON 往返与规则模式
- 真源抽测：100 份线上书源的通过率与失败归因存档在 `docs/smoke/`（`smoke-latest.csv/json`，
  可跨轮 diff），口径与复跑方法见 [`docs/smoke/README.md`](docs/smoke/README.md)
- 文档：`维护基线.md`（上游锚点与同步规则）、`docs/releases/`（发布说明）、
  `轻阅读后端1panel面板部署指南.md`（部署与迁移）

### 5. 不兼容变更（升级时必读）

1. **管理员密码不再接受明文**：`conf/conf.yml` 的 `admin.password` 改为 `admin.passwordHash`，
   必须先用 `java -cp "read.jar:libs/*" web.util.admin.AdminPasswordTool` 生成 bcrypt 哈希；
   两者都为空时**任何密码都无法登录后台**（旧配置含 `admin.password` 仍可登录并告警迁移）。
   ⚠️ 该工具**强制要求密码 ≥12 位**，迁移时等于必须换一个更长的密码。
2. **`libs/` 采用清空重拷**：依赖升级后旧 jar 会残留（曾出现两个 mysql 驱动），发布时必须整目录替换。
3. **需要 JDK 21** 构建与运行（上游文档提到 Java 22，本分支以 21 为基线验证；生产容器已换成
   `eclipse-temurin:21-jre`）。
4. **Solon 4**：组件集合与 3.x 不同，`conf/` 无需改动，但 `libs/` 必须与 `read.jar` 同批替换。
5. **启动必须指定配置文件**：打包内 `app.yml` 写的是 `solon.config.add: "./conf.yml"`（相对工作目录），
   仓库/生产布局把配置放在 `conf/conf.yml` 或独立文件，必须显式
   `--solon.config.add=/opt/qread/conf.yml`，否则会因 `admin.username` 缺失直接启动失败。
6. **Gradle 8.14 起依赖下载走 JDK HttpClient**：双栈网络下若 IPv6 不通，会报
   `Remote host terminated the handshake`；`gradle.properties` 已加
   `systemProp.java.net.preferIPv4Stack=true` 兜底。
7. **服务器没有全局 IPv6 时**：JVM 默认优先 IPv6，容器又只有 `lo` 的 IPv6，所有出网请求
   （代拉书源、引擎抓站）会**立即失败**而不是回退 IPv4。启动参数加
   `-Djava.net.preferIPv4Stack=true`；自查方法见部署指南「步骤五」。

### 6. 明确保留的 qread 独有能力

`usePhone` 手机代理（`getusePhone` / `headusePhone` / `postusePhone` / `getVerificationCodeusePhone`）、
服务端 WebView 桥（`java.click`、`java.showBrowser`）、`startBrowserDp` 段评接口、`java.qread()`、
按用户隔离的 Cookie/Cache、CBZ/MOBI/AZW/AZW3/PDF、本地书八格式、`java.getWebViewUANEW`。

### 7. 维护边界（本分支做什么、不做什么）

**做**：

- 解析引擎（从 `LegadoTeam/legado` 移植、按上游提交逐项对齐）
- 服务端接口、鉴权与安全加固（CSRF、凭据、限流、按用户隔离）
- 依赖与运行时升级（Solon / rhino / okhttp / JDBC 驱动等）
- 配置兼容与部署脚本（`conf/`、启动参数、升级流程）
- 后台管理模板（`templates/`）与后台静态资源（`static/static/`）

**不做**：

- **不改 Flutter 前端产物**（`src/main/resources/static/` 的 `main.dart.js`、`assets/`、
  `flutter-canvaskit/` 等）。这些是「轻悦时光」App 的 Web 构建产物，源码不公开，
  上游从 3.4.5 起也没有重建过；手改编译产物只会与 App 版本错配。
- 不改 `appversion`（改动会触发 App 的更新提示）。
- 不追上游停更之后的新功能。
- 不为「站点断流 / 风控 / 书源规则写错」在引擎里打补丁 —— 这类失败归书源或站点维护者，
  判断口径见 [`docs/smoke/README.md`](docs/smoke/README.md)。

**判定「这一轮做完了」的标准**：回归测试全绿 + 100 源抽测通过数不低于上一轮 +
启动冒烟 5 项通过 + 发布三件套（`read.jar` / `libs/` / `conf/`）同批。

---

## 三、构建与运行

```powershell
# 构建（JDK 21）
.\gradlew.bat clean assemble copyDependencies

# 生成管理员密码哈希（填入 conf/conf.yml 的 admin.passwordHash）
java -cp "read.jar:libs/*" web.util.admin.AdminPasswordTool

# 启动
java -jar read.jar --server.port=8080 --solon.config.add='F:/qreadbackend/conf/conf.yml'
```

默认使用 SQLite（`read.db`）；MySQL 部署见 [`conf/mysqlconf.yml`](conf/mysqlconf.yml) 与部署指南。
后台 `/admin`，Web 阅读根路径 `/`。完整 1Panel 部署步骤见
[`轻阅读后端1panel面板部署指南.md`](轻阅读后端1panel面板部署指南.md)。

> **反向代理/WAF 必须放行**：书源与 RSS 规则里合法含 `eval(` / `include(` / `require(` 等 JS
> 关键字，通用 WAF 会判成攻击并返回 403，表现为「App 保存书源/订阅报网络错误、后端日志却没事」。
> 用 [`conf/waf-skip-paths.txt`](conf/waf-skip-paths.txt) 维护放行清单，再执行
> `conf/scripts/apply-waf-skip.sh --site <域名>`（`--revert` 可恢复）。细节见部署指南步骤六。

## 四、测试

```powershell
.\gradlew.bat :test :book:test          # 58 套件 / 210 用例
$env:QREAD_LIVE_SMOKE="1"; .\gradlew.bat :book:test --tests "book.webBook.RealSourceLiveSmokeTest"
```

真源联网抽测默认跳过：书站普遍有 Cloudflare / 地域风控，只在人工验收时开。

## 五、许可证

本分支整体以 **GNU GPL-3.0** 发布（见 [`LICENSE`](LICENSE)）。原因：`book/` 下的书源解析引擎移植自
**GPL-3.0** 的 legado 系项目，MIT 许可无法覆盖这部分代码。

- 上游 `autobcb/read` 的原始代码为 **MIT**（© 2025 autobcb），其声明完整保留在 [`NOTICE.md`](NOTICE.md)
- 移植的引擎代码版权归 legado 系各贡献者，同样适用 GPL-3.0
- 如需只用上游的 MIT 部分，请直接使用 [`autobcb/read`](https://github.com/autobcb/read)

## 六、免责声明

与原项目一致：本项目是网络文学**搜索与试读工具**，不存储、不提供任何书籍内容。所有内容均来自第三方
网站，使用者应自行判断其合法性并支持正版。第三方站点内容与本站无关，本站不承担任何法律责任。
