# Day 15 GHCR 验收记录

2026-10-19：已实现发布流程并创建 [PR #3](https://github.com/Maknight233/OrderFlow/pull/3)。
正在核对安全补丁后的远程检查；尚未合并或确认 GHCR 发布成功。

| 验收项 | 结果 |
| --- | --- |
| 从最新 main 创建 `feature/day15-ghcr` | PASS：起点 `2ca3664a6d6d03a7a6210f89b28fe59e62dd94eb` |
| Day 14 CI 已存在 | PASS：`build-test`、`docker-smoke` 及 15/15 报告门禁 |
| 四 Dockerfile 构建 | PASS：原始版本及全部安全补丁后均已本地构建成功，远程四镜像构建成功 |
| Maven 与 Gateway 3 项测试 | PASS：原始版本与 Jackson/Tomcat/lz4 补丁后均四模块成功、3 tests / 0 failures / 0 errors |
| Workflow actionlint | PASS：actionlint 1.7.12；`git diff --check` 通过 |
| OCI 原镜像导出摘要一致 | PASS：Gateway 导出前后均为 `sha256:cc48c83c95cdbc67142697647b0e5b9030149492ce4e5adc087b6af5c75c1769` |
| 四镜像 HIGH/CRITICAL 扫描与 JSON | PASS：安全补丁后远程四扫描均成功，下载核对四 JSON 均为 HIGH 0 / CRITICAL 0 |
| 本地 Docker Smoke | FAIL：原始镜像通过前 6 项；第 7 项库存缓存 TTL 检查失败，保留真实结果 |
| PR 测试通过且不发布 | PASS：应用提交 `ffe87ad` 的 PR 测试/Smoke/四扫描全部成功，两个发布 Job skipped；最后的发布保护与文档提交另行检查 |
| main 四个 GHCR Packages | 待执行 |
| 私有 Package 关联/访问权限 | 仓库私有已核对；Package 待发布后核对 |
| SHA 拉取与 main 标签核对 | 待执行 |
| Day 15 PR 合并与 main 远程验收 | 待执行 |

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

## 权限核对

仓库 API 确认为 `private`；Actions 默认权限为 read。
2026-10-19 再次查询 main 分支保护仍返回 HTTP 403：
`Upgrade to GitHub Pro or make this repository public to enable this feature.`
因此尚不能声称平台已强制禁止失败 PR 合并；本次按实际 CI 结果执行合并门禁。

本机既有 GitHub 凭据查询 Packages API 返回缺少 `read:packages` scope 的 HTTP 403。
不扩大本机凭据或在仓库保存 PAT；发布 Job 将用自己的临时 `GITHUB_TOKEN` 逐个查询
Package visibility/source repository 并实际 push/pull，核对是否具有所需 Actions 访问权限。
