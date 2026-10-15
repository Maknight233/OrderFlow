# Day 14 CI 验收记录

2026-10-15：**Push / Pull Request 远程 CI 均成功，3/3 单元测试与 15/15 Docker Smoke 通过。**
main 强制分支保护仍受当前私有仓库的 GitHub 账号计划限制，**Day 14 尚未完整验收**。

## 验收结果

| 验收项 | 结果 |
| --- | --- |
| 本地 Java 21 / Maven 四模块打包 | PASS |
| 本地 Gateway 独立测试 | PASS：3 tests，0 failures，0 errors |
| Push 自动触发及远程成功 | PASS：[运行 38795406144](https://github.com/Maknight233/OrderFlow/actions/runs/38795406144) |
| PR 自动触发及远程成功 | PASS：[运行 38795412637](https://github.com/Maknight233/OrderFlow/actions/runs/38795412637) |
| 远程四镜像构建、七服务健康检查 | PASS：两次运行均通过 |
| 远程 Docker Smoke | PASS：两次运行均为 15/15，`error: null` |
| 故意失败及失败报告下载 | PASS：远程 CI failure；Surefire XML 已下载，3 tests / 1 failure |
| README、CI 操作及排查文档 | PASS |
| main 必需检查阻止失败代码合并 | BLOCKED：私有仓库需 GitHub Pro；GitHub API 返回 403 |

功能分支：`feature/day14-ci`；[草稿 PR #1](https://github.com/Maknight233/OrderFlow/pull/1)。
以下两次成功运行验证代码提交 `76f67648b4f00c3fc14989c37f0776aa7deb11b1`；
验收记录的后续文档提交也会按工作流规则自动触发 CI。

## 已下载核对的远程证据

| 事件 | Docker 项目 / 运行时间（UTC） | 已下载 Artifact |
| --- | --- | --- |
| Push | `orderflow-smoke-2c4ec428e2`；19:38:20–19:46:42 | [Docker 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795406144/artifacts/12001354238)、[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795406144/artifacts/12001043339) |
| Pull Request | `orderflow-smoke-301dcf8c12`；19:38:21–19:46:40 | [Docker 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795412637/artifacts/12002750578)、[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38795412637/artifacts/12002286070) |

两份 Maven XML 均为 3 tests、0 failures、0 errors、0 skipped；
两份 Docker `report.json` 均为 `PASS`，15 项全部 `PASS`。
Docker ZIP 均包含 `compose.log`、`http.jsonl`、`services.log`、`docker-smoke.log` 和 `runner.log`。
报告保留 14 天，过期后应在 Actions 中重新运行生成新证据。

故障演练：[运行 38792699671](https://github.com/Maknight233/OrderFlow/actions/runs/38792699671)，
分支 `ci/day14-failure-probe`，提交 `a608f39`；
[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38792699671/artifacts/11999329914)
已实际下载，失败断言为 `expected: <2.0> but was: <1.0>`。
`build-test` 失败、`docker-smoke` 正确跳过、上传步骤成功。
故意错误未进入正常功能分支，该演练分支不可合并。

## CI 验证期间修复的问题

- 四个 Dockerfile 的 BuildKit Maven 缓存使用 `sharing=locked`，避免并发写入，串行执行 Maven 阶段；`-e` 保留异常原因链。
- Gateway Docker profile 将 Netty DNS 缓存上限设为 5 秒，让服务重启、IP 变化后重新解析地址。
  修复前两次远程运行在第 10 项恢复阶段失败：业务服务已启动并提交 Saga，Gateway 仍持续 503。
  修复后两次远程运行的服务重启、熔断恢复、JVM 崩溃及完整容器重建检查全部通过。
- Docker 失败时的报告上传也已实际验证并下载，详见
  [早期失败运行](https://github.com/Maknight233/OrderFlow/actions/runs/38793224299)。

## 剩余验收项

GitHub API 对当前私有仓库返回：
`Upgrade to GitHub Pro or make this repository public to enable this feature.`
当前仓库保持私有；Workflow 检查失败能标红，但尚不能强制禁止合并。
账号具备分支保护能力后，应用 [`day14-main-protection.json`](day14-main-protection.json)，
要求 `build-test`、`docker-smoke` 成功，包含管理员，并在失败 PR 上验证合并被阻止。

运行与排查说明见 [day14-ci.md](day14-ci.md)。
