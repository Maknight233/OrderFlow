# Day 15 GHCR 验收记录

2026-10-19：已实现发布流程并创建 [PR #3](https://github.com/Maknight233/OrderFlow/pull/3)。
最终 head 的 Push / PR 检查均通过，PR 已合并。main 的四镜像已推送并可拉取，
发布校验错误地要求 Package API 必须返回可选的 `repository` 字段，main 标签因此尚未提升；修复中。

| 验收项 | 结果 |
| --- | --- |
| 从最新 main 创建 `feature/day15-ghcr` | PASS：起点 `2ca3664a6d6d03a7a6210f89b28fe59e62dd94eb` |
| Day 14 CI 已存在 | PASS：`build-test`、`docker-smoke` 及 15/15 报告门禁 |
| 四 Dockerfile 构建 | PASS：原始版本及全部安全补丁后均已本地构建成功，远程四镜像构建成功 |
| Maven 与 Gateway 3 项测试 | PASS：原始版本与 Jackson/Tomcat/lz4 补丁后均四模块成功、3 tests / 0 failures / 0 errors |
| Workflow actionlint | PASS：actionlint 1.7.12；`git diff --check` 通过 |
| OCI 原镜像导出摘要一致 | PASS：Gateway 导出前后均为 `sha256:cc48c83c95cdbc67142697647b0e5b9030149492ce4e5adc087b6af5c75c1769` |
| 四镜像 HIGH/CRITICAL 扫描与 JSON | PASS：安全补丁后远程四扫描均成功，下载核对四 JSON 均为 HIGH 0 / CRITICAL 0 |
| 本地 Docker Smoke | 两次均未完整通过：原始镜像 6 项后 TTL 失败；补丁镜像 8 项后 Kafka 恢复 Saga 等待超时；远程完整回归已通过 |
| PR 测试通过且不发布 | PASS：最终 head `b442da9` 的 Push / PR 全部成功，15/15 Smoke、四扫描均通过，两个发布 Job skipped |
| main 四个 GHCR Packages | 已创建四个 Package 与 SHA 标签；首次发布 Job 的可选元数据校验误报，修复中 |
| 私有 Package 关联/访问权限 | PASS：四个 Package 的 visibility 均 private；workflow token 已实际 push/pull；API 不提供 repository 元数据，UI 继承状态不作已验证声明 |
| SHA 拉取与 main 标签核对 | 四个 SHA 镜像已实际拉取并运行 Java 21，摘要一致；main 标签提升因校验误报而跳过，修复中 |
| Day 15 PR 合并与 main 远程验收 | PR #3 已合并为 `a1051020acedf5ddaf5f7eeca416ad183d4d3a09`；main 发布验收中 |

策略为所有 HIGH/CRITICAL（含尚无补丁的漏洞）阻断发布；scanner 错误也失败。
没有豁免或 `continue-on-error`。发现漏洞时保留真实失败及报告，不以降低门禁完成验收。

## 初次扫描与修复

本地 Trivy 0.76.0 使用同一漏洞策略扫描四个最终运行镜像，所有 JSON 已保存在
`target/day15/trivy-<service>.json`，不会提交大型本机报告或工具到仓库。
OS 包没有本次策略范围内的发现；下列均为镜像内 Java 依赖：

| 原始镜像 | HIGH | CRITICAL |
| --- | ---: | ---: |
| Discovery | 10 | 4 |
| Gateway | 10 | 0 |
| Order | 11 | 3 |
| Inventory | 11 | 3 |

修复 Jackson 2.21.5 → 2.21.7、Jackson 3.1.5 → 3.1.7、Tomcat 11.0.24 → 11.0.25、
lz4-java 1.10.1 → 1.11.4、FreeMarker 2.3.34 → 2.3.35。
漏洞与依赖来自实际 JSON 的 `PkgName`、`InstalledVersion`、`FixedVersion`；
已核对所选版本在 Maven Central 发布，不通过扫描忽略文件规避漏洞。

初次远程 [Push 运行 39300290079](https://github.com/Maknight233/OrderFlow/actions/runs/39300290079)
暴露 Trivy 的输入格式问题：纯 OCI tar 被当作 Docker tar 查找 `manifest.json`，
随后以非零退出码结束，报告未生成时 Artifact 上传也明确失败。
现已先解包为 OCI layout 目录再扫描；本地以该路径完成 Gateway 扫描并生成 JSON，
实际检出 10 个 HIGH，证明修正后的输入可扫描应用依赖。

该次远程 `build-test`、`docker-smoke` 均成功，四个扫描 Job 均失败；
`publish-images` 与 `promote-main` 均为 `skipped`，没有执行发布或 main 标签更新。
这是 feature Push 的失败门禁实测；main 发布路径仍需合并后的实际运行验收。

本地 Smoke 项目为 `orderflow-smoke-e9018a56cf`，报告位于
`integration-tests/target/docker-smoke/orderflow-smoke-e9018a56cf/report.json`。
前 6 项包括七容器健康、非 root / Java 21、Kafka metadata、Eureka 路由、Saga 成功与补偿、
重复消息幂等；随后库存 5 秒缓存 TTL 断言失败。隔离容器与卷已按脚本清理。
该本地失败不计为 15/15 PASS，安全补丁后的完整回归以远程报告另行记录。

安全补丁后的第二次本地运行项目为 `orderflow-smoke-0b77f43f16`。
本次通过前 8 项，包括此前失败的缓存 TTL 和 Redis 离线回源检查；
第 9 项 Kafka 恢复后等待 Saga `1d6c56be-f7b9-4ffa-a4aa-1eb57af9a860` 进入 COMPLETED 超时。
`report.json` 和 `services.log` 保存于对应项目目录，隔离资源已清理。
该次也不计为完整本地 PASS；未通过增加超时、移除断言或放宽扫描门禁掩盖失败。

## 安全补丁后的远程扫描

已核对应用提交 `ffe87ad959147ea1dc0ff268e68a0b177b55630e` 的
[PR 运行 39302161036](https://github.com/Maknight233/OrderFlow/actions/runs/39302161036)。
四个 `scan-images` Job 均为 `completed / success`，下载 JSON 后统计如下：

| 服务 | HIGH | CRITICAL | 已下载核对的 Artifact |
| --- | ---: | ---: | --- |
| Discovery | 0 | 0 | [JSON](https://github.com/Maknight233/OrderFlow/actions/runs/39302161036/artifacts/12211358856) |
| Gateway | 0 | 0 | [JSON](https://github.com/Maknight233/OrderFlow/actions/runs/39302161036/artifacts/12212398241) |
| Order | 0 | 0 | [JSON](https://github.com/Maknight233/OrderFlow/actions/runs/39302161036/artifacts/12210738680) |
| Inventory | 0 | 0 | [JSON](https://github.com/Maknight233/OrderFlow/actions/runs/39302161036/artifacts/12212113391) |

同提交 [feature Push 运行 39302147210](https://github.com/Maknight233/OrderFlow/actions/runs/39302147210)
的四个扫描 Job 也均为成功。

PR 运行已完整成功；下载 [Docker 报告](https://github.com/Maknight233/OrderFlow/actions/runs/39302161036/artifacts/12212771403)
确认 `orderflow-smoke-3fdc8b82ba` 为 `PASS`、15/15、`error: null`；
[Maven 报告](https://github.com/Maknight233/OrderFlow/actions/runs/39302161036/artifacts/12210994683)
为 3 tests、0 failures、0 errors、0 skipped。两个发布 Job 为 `skipped`，
PR 没有提前登录或推送 GHCR。后续发布保护/文档提交保持应用源码、Dockerfile 和 POM 不变。

SHA 防覆盖逻辑已用六种离线场景验证：新 Package、新 SHA、既有相同摘要允许，
不同摘要拒绝，403 权限错误传播，以及第二页版本中的 SHA 冲突拒绝。

## 最终提交验收与合并

最终 PR head 为 `b442da9b8dc88e00938da5b13aa51464e8b6fc9f`。
合并前再次核对 [Push 39304237189](https://github.com/Maknight233/OrderFlow/actions/runs/39304237189)
与 [PR 39304246966](https://github.com/Maknight233/OrderFlow/actions/runs/39304246966)：
两个运行均为 `completed / success`，两个发布 Job 均 `skipped`。
已下载最终 PR 的 Maven、Docker、四个 Trivy 报告，确认 3/3 单元测试、
`orderflow-smoke-a77ced20c4` 的 15/15 PASS / `error: null`，以及四扫描 HIGH/CRITICAL 均为 0。

GitHub 确认 PR 为 CLEAN / MERGEABLE，base 仍为 `2ca3664`；
使用 `expected_head_sha=b442da9...` 合并，返回 `merged: true`。
main 合并提交为 `a1051020acedf5ddaf5f7eeca416ad183d4d3a09`，
自动触发 [main 运行 39305855478](https://github.com/Maknight233/OrderFlow/actions/runs/39305855478)。
main 的 Package、推送/拉取摘要、私有访问权限和 main 标签只在该运行实际通过后记录为成功。

## 权限核对

仓库 API 确认为 `private`；Actions 默认权限为 read。
2026-10-19 再次查询 main 分支保护仍返回 HTTP 403：
`Upgrade to GitHub Pro or make this repository public to enable this feature.`
因此尚不能声称平台已强制禁止失败 PR 合并；本次按实际 CI 结果执行合并门禁。

本机既有 GitHub 凭据查询 Packages API 返回缺少 `read:packages` scope 的 HTTP 403。
不扩大本机凭据或在仓库保存 PAT；发布 Job 使用自己的临时 `GITHUB_TOKEN` 逐个查询
Package visibility 并实际 push/pull，核对所需 Actions 访问权限；source/revision 从实际拉取镜像核对。

## 首次 main 发布的元数据校验修复

main 运行 39305855478 的 Maven、15/15 Smoke、四个扫描均通过。
四个发布 Job 均成功推送 SHA 镜像，实际拉取、摘要一致性、revision 与 Java 21 均通过，
Package 的 private 断言也通过；随后可选 `repository` 字段缺失被误判为未关联，Job 失败。
`promote-main` 正确 skipped，未更新 main 标签。

仅重跑失败发布 Job 的 attempt 2 成功复用了同次运行已扫描归档，SHA 防覆盖检查通过，
但相同元数据断言继续失败。这同时实际验证了失败重跑无需重建镜像。

独立 [只读 Package 诊断运行 39308004056](https://github.com/Maknight233/OrderFlow/actions/runs/39308004056)
使用 `packages: read` 的 workflow token，确认四个 Package 都为 private，返回 keys 均无
`repository`；未读取/写入 PAT，未修改 Package，诊断分支未合并 main。
源标签已从扫描报告确认是 `https://github.com/Maknight233/OrderFlow`。

修复为验证实际 push/pull、私有可见性与 OCI source/revision；API 提供 repository 时再核对 ID。
不以可选字段缺失推断权限错误，不声称继承复选框已通过 API 验证。
追加四项校验场景：私有且无可选元数据、正确 ID 接受，错误 ID 与 public 拒绝。
