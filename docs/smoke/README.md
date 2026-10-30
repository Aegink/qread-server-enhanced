# 真源抽测存档（`docs/smoke/`）

这里放 `RealSourceLiveSmokeTest` 产出的通过率与失败归因，用来做**跨轮对照**：每次升级引擎或依赖后
跑一遍，和上一轮的 `smoke-latest.json` diff 一下，就知道「通过率有没有掉、失败是不是换了类型」。

## 复跑

```powershell
$env:JAVA_HOME="D:\Program Files\Java\jdk-21"
$env:GRADLE_USER_HOME="$env:USERPROFILE\.gradle"
$env:QREAD_LIVE_SMOKE="1"
$env:QREAD_SMOKE_SOURCE_DIR="C:\path\to\sources"   # 源仓下载的 *.json，文件名用源 id
$env:QREAD_SMOKE_LIMIT="100"
$env:QREAD_SMOKE_KEY="斗破苍穹"
$env:QREAD_SMOKE_REPORT_DIR="docs/smoke"
.\gradlew.bat :book:test --tests "book.webBook.RealSourceLiveSmokeTest"
```

书源样本取自源仓 <https://yuancang.xyz>：

- 列表：`GET /api/v1/sources?page=N&page_size=30&category_id=1`（`data.items[].id`）
- 下载：`GET /api/v1/sources/{id}/download`（返回书源 JSON 数组，用 `-OutFile` 直写字节）
- 注意：**下载接口有 429 限流**，按 1.5~2s 一份的节奏拉，遇 429 退避 20s

## 文件

| 文件 | 说明 |
|---|---|
| `smoke-latest.csv` / `smoke-latest.json` | 最近一次结果，**进版本库**，跨轮 diff 就看它 |
| `smoke-<时间戳>.csv/json` | 每次运行的快照，本地保留、不进库（避免仓库膨胀） |

字段：`index,sourceId,name,status,category,searchCount,tocCount,contentLen,elapsedMs,url,detail`。
`status` 为 PASS / FAIL / SKIP；`category` 是失败归因分类（`网络层:断流`、`网络层:SSL`、`搜索无结果`、
`TocEmpty`、`ContentEmpty`、`Script`、`超时`、`PathNotFound` 等）。

## 归因口径

`category` 只描述**观测到的失败位置**，不等于责任方。判断是否要在引擎侧动手，看两条：

1. 同一类失败是否**跨多个源重复出现**；
2. 是否有证据指向引擎（例如 PowerShell/curl 直连同一主机成功，而引擎失败 → 才怀疑 UA/请求头/重试）。

站点 403/断流/风控、书源规则过时，都交回站点或书源维护者，不在引擎里打补丁。
