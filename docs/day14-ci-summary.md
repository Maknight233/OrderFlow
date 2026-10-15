# Day 14 CI 验收记录

2026-10-15：**Push / Pull Request 及合并后 main 远程 CI 均成功，3/3 单元测试与 15/15 Docker Smoke 通过。**
main 强制分支保护仍受当前私有仓库的 GitHub 账号计划限制，**Day 14 尚未完整验收**。
PR #1 已标记为 Ready for review 并合并到 `main`；故障演练 PR #2 已关闭且未合并。

## 验收结果

| 验收项 | 结果 |
| --- | --- |
| 本地 Java 21 / Maven 四模块打包 | PASS |
| 本地 Gateway 独立测试 | PASS：3 tests，0 failures，0 errors |
| Push 自动触发及远程成功 | PASS：[最新功能分支运行 38796964140](https://github.com/Maknight233/OrderFlow/actions/runs/38796964140) |
| PR 自动触发及远程成功 | PASS：[最新 PR 运行 38796969386](https://github.com/Maknight233/OrderFlow/actions/runs/38796969386) |
| PR #1 合并后 main CI | PASS：[运行 38800126152](https://github.com/Maknight233/OrderFlow/actions/runs/38800126152)，两个 Job 均成功 |
| 远程四镜像构建、七服务健康检查 | PASS：功能分支与 main 均通过 |
| 远程 Docker Smoke | PASS：功能分支与 main 均为 15/15，`error: null` |
| 故意失败及失败报告下载 | PASS：远程 CI failure；Surefire XML 已下载，3 tests / 1 failure |
| README、CI 操作及排查文档 | PASS |
| main 必需检查阻止失败代码合并 | BLOCKED：私有仓库需 GitHub Pro；GitHub API 返回 403 |

## PR 处置与合并核验

- [故障演练 PR #2](https://github.com/Maknight233/OrderFlow/pull/2) 于 20:12:30 UTC 关闭；
  GitHub 确认为 `state: closed`、`merged: false`；另核对故意失败提交 `a608f39` 不在 main 历史中。
- [PR #1](https://github.com/Maknight233/OrderFlow/pull/1) 最新提交为
  `94c5dbcc8964a028465966abeaee1e44fcf8f7f2`；合并前核对其最新 Push / PR 运行，
  两次的 `build-test` 和 `docker-smoke` 四个 Job 均为 `completed / success`。
- PR #1 于 20:12:49 UTC 标记为 **Ready for review**，于 20:12:54 UTC 合并到 main。
  合并使用预期 head SHA 校验，合并提交为 `023c42dfc59a64780639938639aa410e0e259330`。
- main 的 Push 事件自动触发 [合并后 CI](https://github.com/Maknight233/OrderFlow/actions/runs/38800126152)，
  `build-test` 和 `docker-smoke` 均为 `completed / success`；已下载核对 Maven 与 Docker 报告。

以下同时保留 main 合并提交及初次完整远程验收的 Artifact；初次验收的代码提交为
`76f67648b4f00c3fc14989c37f0776aa7deb11b1`。后续文档提交也会按工作流规则自动触发 CI。

## 已下载核对的远程证据

| 事件 | Docker 项目 / 运行时间（UTC） | 已下载 Artifact |
| --- | --- | --- |
| main 合并后 Push | `orderflow-smoke-9d3a979a99`；20:14:21–20:23:22 | [Docker 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38800126152/artifacts/12003579306)、[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38800126152/artifacts/12004256026) |
| Push | `orderflow-smoke-2c4ec428e2`；19:38:20–19:46:42 | [Docker 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795406144/artifacts/12001354238)、[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795406144/artifacts/12001043339) |
| Pull Request | `orderflow-smoke-301dcf8c12`；19:38:21–19:46:40 | [Docker 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795412637/artifacts/12002750578)、[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795412637/artifacts/12002286070) |

三份 Maven XML 均为 3 tests、0 failures、0 errors、0 skipped；
三份 Docker `report.json` 均为 `PASS`，15 项全部 `PASS`。
Docker ZIP 均包含 `compose.log`、`http.jsonl`、`services.log`、`docker-smoke.log` 和 `runner.log`。
报告保留 14 天，过期后应在 Actions 中重新运行生成新证据。

故障演练：[运行 38792699671](https://github.com/Maknight233/OrderFlow/actions/runs/38792699671)，
分支 `ci/day14-failure-probe`，提交 `a608f39`；
[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38792699671/artifacts/11999329914)
已实际下载，失败断言为 `expected: <2.0> but was: <1.0>`。
`build-test` 失败、`docker-smoke` 正确跳过、上传步骤成功。
故意错误未进入正常功能分支；对应 PR #2 已关闭且未合并，演练分支仅保留用于追溯。

## CI 验证期间修复的问题

- 四个 Dockerfile 的 BuildKit Maven 缓存使用 `sharing=locked`，避免并发写入，串行执行 Maven 阶段；`-e` 保留异常原因链。
- Gateway Docker profile 将 Netty DNS 缓存上限设为 5 秒，让服务重启、IP 变化后重新解析地址。
  修复前两次远程运行在第 10 项恢复阶段失败：业务服务已启动并提交 Saga，Gateway 仍持续 503。
  修复后两次远程运行的服务重启、熔断恢复、JVM 崩溃及完整容器重建检查全部通过。
- Docker 失败时的报告上传也已实际验证并下载，详见
  [早期失败运行](https://github.com/Maknight233/OrderFlow/actions/runs/38793224299)。

## 剩余验收项

**main 分支保护仍未启用。** 再次查询 GitHub 分支信息确认 `protected: false`。
GitHub API 对当前私有仓库返回：
`Upgrade to GitHub Pro or make this repository public to enable this feature.`
当前仓库保持私有；Workflow 检查失败能标红，但尚不能强制禁止合并。
账号具备分支保护能力后，应用 [`day14-main-protection.json`](day14-main-protection.json)，
要求 `build-test`、`docker-smoke` 成功，包含管理员，并在失败 PR 上验证合并被阻止。

运行与排查说明见 [day14-ci.md](day14-ci.md)。
