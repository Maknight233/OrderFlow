# Day 14 CI 验收记录

状态：CI 配置已实现，远程验收进行中；main 强制分支保护受 GitHub 账号计划限制。

| 验收项 | 结果 |
| --- | --- |
| 本地 Java 21 / Maven 四模块打包 | PASS（2026-10-15） |
| 本地 Gateway 独立测试 | PASS：3 tests，0 failures，0 errors |
| Push 自动触发及远程成功 | 待执行 |
| PR 自动触发及远程成功 | 待执行 |
| 远程四镜像构建、七服务、15/15 Smoke | 待执行 |
| 故意失败及失败报告下载 | PASS：远程 CI failure；Surefire XML 已下载，3 tests / 1 failure |
| main 必需检查阻止失败代码合并 | BLOCKED：私有仓库需 GitHub Pro；GitHub API 返回 403 |

故障演练：[运行 38792699671](https://github.com/Maknight233/OrderFlow/actions/runs/38792699671)，
分支 `ci/day14-failure-probe`，提交 `a608f39`；
[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/38792699671/artifacts/11999329914)
已实际下载，失败断言为 `expected: <2.0> but was: <1.0>`。
`docker-smoke` 正确跳过，故意错误未进入正常功能分支。

首次 PR Docker 构建在 Maven 插件依赖解析阶段失败，
[失败运行](https://github.com/Maknight233/OrderFlow/actions/runs/38792649851)的
Docker 报告也成功上传并下载。日志显示四个 Maven 构建并发访问同一 BuildKit 缓存；
已为缓存增加排他锁，并启用 Maven 异常原因链输出，继续远程验证。

运行与排查说明见 [day14-ci.md](day14-ci.md)。
