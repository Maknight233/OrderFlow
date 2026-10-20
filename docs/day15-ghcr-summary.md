# Day 15 GHCR 验收记录

2026-10-19：**Day 15 远程 CI/CD 验收完成。**
[PR #3](https://github.com/Maknight233/OrderFlow/pull/3) 及修复
[PR #4](https://github.com/Maknight233/OrderFlow/pull/4)、
[PR #5](https://github.com/Maknight233/OrderFlow/pull/5) 均在完整检查成功后合并 main。
[最终 main 运行 39313175449](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449)
全部 Job 成功，发布版本为 `34ed73031211b98b31b487e9182b0d26b9975a73`。
四个私有镜像的 SHA 推送、实际拉取、Java 21、source/revision、摘要与 main 标签均已核对。
本地补充测试失败及私有仓库分支保护限制仍如实保留，见下文。

| 验收项 | 结果 |
| --- | --- |
| 从最新 main 创建 `feature/day15-ghcr` | PASS：起点 `2ca3664a6d6d03a7a6210f89b28fe59e62dd94eb` |
| Day 14 CI 已存在 | PASS：`build-test`、`docker-smoke` 及 15/15 报告门禁 |
| 四 Dockerfile 构建 | PASS：原始版本及全部安全补丁后均已本地构建成功，远程四镜像构建成功 |
| Maven、Java 与预热回归测试 | PASS：四模块打包；Java 3/3、Python 4/4，0 failures / 0 errors |
| Workflow actionlint | PASS：actionlint 1.7.12；`git diff --check` 通过 |
| OCI 原镜像导出摘要一致 | PASS：Gateway 导出前后均为 `sha256:cc48c83c95cdbc67142697647b0e5b9030149492ce4e5adc087b6af5c75c1769` |
| 四镜像 HIGH/CRITICAL 扫描与 JSON | PASS：安全补丁后远程四扫描均成功，下载核对四 JSON 均为 HIGH 0 / CRITICAL 0 |
| 本地 Docker Smoke | 预热修复前两次未完整通过：6 项后 TTL 失败、8 项后 Kafka 恢复 Saga 超时；未将其计为 PASS，最终远程 15/15 PASS |
| PR 测试通过且不发布 | PASS：PR #3/#4/#5 各自最终 head 的 Push / PR 均成功，两个发布 Job skipped |
| main 四个 GHCR Packages | PASS：四个私有 Package 均发布完整 SHA 标签，main 标签提升成功 |
| 私有 Package 关联/访问权限 | PASS：四个 Package 的 visibility 均 private；workflow token 已实际 push/pull；API 不提供 repository 元数据，UI 继承状态不作已验证声明 |
| SHA 拉取与 main 标签核对 | PASS：四镜像实际拉取、Java 21、source/revision 与扫描/发布摘要一致；四个 main/SHA manifest digest 相同 |
| Day 15 PR 合并与 main 远程验收 | PASS：PR #3/#4/#5 已合并；main 运行 39313175449 全部成功 |

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

修复提交 `95d32d0f009ffddf06684fc01f9ae9d085e2b368` 的
[Push 39308391109](https://github.com/Maknight233/OrderFlow/actions/runs/39308391109)
与 [PR 39308432179](https://github.com/Maknight233/OrderFlow/actions/runs/39308432179)
均完整成功，发布/提升 Job 均 skipped。已下载两份 Smoke 报告：
`orderflow-smoke-4b6c9138f8` 与 `orderflow-smoke-63688ce7ee` 均为 15/15 PASS、`error: null`；
PR 的 Maven 3/3、四个 Trivy JSON HIGH/CRITICAL 均为 0。

PR #4 合并前确认 CLEAN / MERGEABLE，使用 expected head SHA 校验合并；
main 提交为 `c5b9cfea5bfd4be1fee306bfc6ee520c4b9fe058`，
触发 [修复后 main 运行 39310091882](https://github.com/Maknight233/OrderFlow/actions/runs/39310091882)。

修复后 main 的 Maven 与四个扫描 Job 已成功；已下载对应 Maven 与四份 Trivy JSON，
确认 3/3 单元测试、四镜像 HIGH 0 / CRITICAL 0。Smoke 随后在启动预热阶段失败，
发布和 main 标签提升正确 skipped；预热修复后继续验收。

## Smoke 启动预热修复

运行 39310091882 的 `orderflow-smoke-3c71cfa564` 通过容器健康与 Kafka metadata 两项后失败。
HTTP 记录为：Inventory GET 503、503、404；Order GET 404；Inventory POST 201；第二次 POST 503 CIRCUIT_OPEN。
Gateway 日志同时记录 Eureka 服务尚未进入 LoadBalancer 缓存。
Smoke 配置最小调用数 4、失败率阈值 50%，单次成功探测并不保证启动失败已退出统计窗口。

预热改为每条只读路由连续成功至少 4 次，发生 503 时整个只读探测重新等待，
使熔断开启/恢复在写入前完成；业务 POST 仍只发送一次，既有 3 秒/7 秒超时与熔断配置不变。
新增四项标准库 unittest：健康路由、首次成功后熔断、Order 独立恢复、持续发现失败仍阻断。
本地四项全部通过，加入 `build-test`；业务 Smoke 验收仍要求原有 15/15，不降低门禁。

预热修复 [PR #5](https://github.com/Maknight233/OrderFlow/pull/5) 的最终 head 为
`392488c4224cdc0dca73dcfb7181f8b8e62ddc48`。
[Push 39311568652](https://github.com/Maknight233/OrderFlow/actions/runs/39311568652)
与 [PR 39311578241](https://github.com/Maknight233/OrderFlow/actions/runs/39311578241)
均完整成功，两个发布 Job skipped；下载 PR 报告确认 Java 3/3、Python 4/4、
四扫描 HIGH/CRITICAL 均 0、`orderflow-smoke-bc9f2590c5` 为 15/15 PASS。
HTTP 记录显示写入前完成 8 次预期 404 的只读探测，随后 POST 均成功。

再次以 expected head SHA 校验合并 PR #5，main 提交为
`34ed73031211b98b31b487e9182b0d26b9975a73`，
触发 [最终 main 运行 39313175449](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449)。
此运行已完成，所有 Job 均为 `completed / success`，包括四个发布 Job 和 `promote-main`。

## 最终 main 发布证据

验收提交：`34ed73031211b98b31b487e9182b0d26b9975a73`。
四个镜像共同使用标签 `sha-34ed73031211b98b31b487e9182b0d26b9975a73`，平台 `linux/amd64`。
发布仅使用临时 `GITHUB_TOKEN`，没有添加 PAT 或仓库 Secret。

已下载 [Maven 与预热日志](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/artifacts/12217176752)
和 [Docker 报告](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/artifacts/12216358469)：
Java 3/3、Python 4/4；`orderflow-smoke-2d53cc50c2` 为 15/15 PASS、`error: null`。

四份最终 Trivy JSON 已下载核对，均为 HIGH 0 / CRITICAL 0：
[Discovery](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/artifacts/12217092098)、
[Gateway](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/artifacts/12217231653)、
[Order](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/artifacts/12217321386)、
[Inventory](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/artifacts/12216538995)。

镜像名前缀均为 `ghcr.io/maknight233/orderflow-`。四个发布 Job 的原始日志均确认：
扫描 manifest digest = 发布 digest = 实际拉取 digest；Java 21 可运行，source/revision 正确，
Package API visibility 为 private，workflow token 实际 push/pull 成功。

| 服务 | 发布 manifest digest | 远程证据 |
| --- | --- | --- |
| `discovery-service` | `sha256:3d3c9730ddba83d18fc0458673e880cbfbe4bb59b418311875b27476782737d9` | [发布与拉取验证](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/job/118272361818) |
| `gateway-service` | `sha256:7e02e1db407cc2ee6cc5cc3de16024877eb7c76beee54979b2f1113db25df4ea` | [发布与拉取验证](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/job/118272361854) |
| `order-service` | `sha256:e639d6311c202b027986260fc60dc6433694a2977de4832d6ab5d2dec46a7b9b` | [发布与拉取验证](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/job/118272361980) |
| `inventory-service` | `sha256:3615f6a8f3b2a5bd5199c61ab81524cc5104043b8b0d12ac12b39cef8485f087` | [发布与拉取验证](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/job/118272361923) |

[main 标签提升 Job](https://github.com/Maknight233/OrderFlow/actions/runs/39313175449/job/118272803127)
在 2026-10-19 19:53:03–19:53:16 UTC 实际推送四个 `main` 标签，
逐个比较 main/SHA manifest digest 相等，全部成功。
这证明 main 标签引用上述已扫描、已拉取验证的四个镜像，没有重新构建。

初次扫描错误、Package 可选元数据误报、启动熔断失败均保留原始运行；
它们对应的发布或 main 提升被跳过，未以失败运行更新 main 标签。
预热修复后的完整本地 Docker 套件没有再重跑；本地两次失败不算作通过，
远程 Linux CI 的完整 15/15 及发布结果为本次 CI/CD 验收依据。
