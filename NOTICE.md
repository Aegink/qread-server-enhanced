# NOTICE · 致谢与许可声明

本仓库是 [`autobcb/read`](https://github.com/autobcb/read) 的维护分支，包含来自多个上游的代码。
本文件记录各方权利与许可，**任何再分发都必须保留本文件**。

---

## 1. 直接上游：轻阅读后端

- 仓库：<https://github.com/autobcb/read>
- 作者：q9uo11（GitHub [@autobcb](https://github.com/autobcb)）
- 许可：MIT
- 本分支的起点：commit `c29186c`（tag `3.4.6`，2026-04-08）

上游原始许可声明（完整保留）：

```
MIT License

Copyright (c) 2025 autobcb

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

**说明**：上游自身即为 MIT。本分支在此之上移植了 GPL-3.0 的引擎代码（见第 2 节），因此**整个仓库
按 GPL-3.0 发布**（GPL-3.0 与 MIT 兼容）。若你只需要 MIT 许可的那部分代码，请直接使用上游仓库。

---

## 2. 书源解析引擎来源（GPL-3.0）

`book/` 模块中的规则解析引擎移植自「阅读 3.0」及其活跃分支，著作权归各上游作者与贡献者，均为 GPL-3.0：

| 仓库 | 角色 |
|---|---|
| [LegadoTeam/legado](https://github.com/LegadoTeam/legado) | 引擎主标准：`model/analyzeRule`、`help/*`、`help/http`、`model/localBook`、`model/rss` |
| [HapeLee/legado-with-MD3](https://github.com/HapeLee/legado-with-MD3) | 行为交叉校验基线 |
| [gedoor/legado](https://github.com/gedoor/legado) | 阅读 3.0 原始项目（现仅存公告），规则语义出处 |
| [rimvoid/legado](https://github.com/rimvoid/legado)（阅读 Archive） | 提交规范、发布说明与测试写法参考 |

主要涉及路径：

```
book/src/main/kotlin/book/webBook/analyzeRule/**   规则解析（AnalyzeRule / AnalyzeUrl / Jsoup / XPath / JSONPath / QueryTTF）
book/src/main/kotlin/book/webBook/localBook/**     本地书解析（TXT / EPUB / UMD / CBZ / MOBI / PDF）
book/src/main/kotlin/book/webBook/rss/**           RSS 解析
book/src/main/kotlin/book/util/**                  HTTP、编码、正则、缓存等工具
book/src/main/kotlin/com/script/**                 Rhino JSR-223 脚本引擎封装
```

---

## 3. 本分支新增代码

本仓库相对于 `c29186c` 新增/修改的代码（安全加固、多租户隔离、测试、文档、构建脚本等）由本仓库
维护者编写，同样以 **GPL-3.0** 发布。逐次变更见 `git log` 与 `docs/releases/`。

---

## 4. 第三方依赖

按各自许可使用，未做修改：Solon（Apache-2.0）、MyBatis-Plus（Apache-2.0）、jsoup（MIT）、
OkHttp（Apache-2.0）、Rhino（MPL-2.0）、Gson（Apache-2.0）、json-path（Apache-2.0）、
sqlite-jdbc（Apache-2.0）、mysql-connector-j（GPL-2.0 + Universal FOSS Exception）、
Bouncy Castle（MIT 风格）、Hutool（Apache-2.0）、FreeMarker（Apache-2.0）等。

随仓库分发、未经修改的 JS 库：

- **crypto-js 4.2.0**（MIT，© Jeff Mott / Evan Vosberg）——
  `book/src/main/resources/js/crypto-js.min.js`，用于给书源规则提供全局 `CryptoJS`
  （MD5/SHA/HMAC/AES/Base64），与「阅读」系内置 CryptoJS 的行为对齐。

---

## 5. 内容与合规

本项目只提供**书源规则的解析与多端同步能力**，不内置、不存储、不分发任何书籍内容。所有内容来自使用者
自行添加的第三方书源，相关责任由使用者与内容提供方承担。请遵守当地法律并支持正版。
