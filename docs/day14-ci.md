# Day 14：GitHub Actions 持续集成

Workflow：[`.github/workflows/ci.yml`](../.github/workflows/ci.yml)。
所有分支 Push、Pull Request、Merge Queue 均运行检查，也支持 `workflow_dispatch`。
同一事件、同一分支的新提交取消旧运行；Push 与 PR 各自保留运行记录。

## 检查范围

| 必需检查名称 | 内容 | Job 超时 |
| --- | --- | --- |
| `build-test` | Java 21 / Maven 缓存；四个微服务打包；Gateway 3 项独立单元测试 | 20 分钟 |
| `docker-smoke` | 前一 Job 成功后构建四个镜像、启动七个服务，验证 15 项业务与故障恢复检查 | 50 分钟 |

使用官方 `actions/checkout@v7`、`actions/setup-java@v6` 和
`actions/upload-artifact@v7`，权限为 `contents: read`，不需要仓库 Secrets。
Docker Job 使用 Ubuntu 24.04 自带的 Python 3、Docker Engine 和 Compose。

本地运行同样的检查（Java 21、Maven、Python 3.10+、Docker Compose）：

```powershell
mvn -B -ntp -DskipTests package
mvn -B -ntp -pl gateway-service '-Dtest=GatewayConcurrencyFilterTest' test
python scripts/docker-smoke.py
```

`-DskipTests` 仍会编译测试源码。CI 显式选择无需基础设施的 Gateway 测试；
Order/Inventory 的 PostgreSQL、Kafka 测试不加入默认 `mvn test`。
根 POM 的 `integration-tests` Profile 保持独立，详见
[`integration-tests/README.md`](../integration-tests/README.md)。

Docker 沿用 [Day 13 脚本](day13-docker.md)，使用唯一项目名、临时端口和独立卷。
`COMPOSE_PARALLEL_LIMIT=1` 限制 Compose 引擎调用并发；Bake 仍可能并行构建镜像，
四个 Dockerfile 的 Maven 缓存使用 `sharing=locked`，串行执行 Maven 阶段，
避免同时写入同一个依赖缓存并降低内存峰值。Maven `-e` 保留构建失败原因链；
`runner.log` 记录实际内存、磁盘和 Docker 版本。
脚本执行限制为 40 分钟，随后发送 SIGINT，让现有异常处理写报告、收集日志并清理；
额外留出 4 分钟清理宽限及上传时间。Job 的 50 分钟硬超时或 Runner 中断仍可能截断上传。
最终报告必须为 `PASS` 且恰好 15 项全部通过，才接受 Docker 检查。

Gateway 的 Docker profile 将 Netty DNS 缓存上限设为 5 秒，配合 Eureka 和
LoadBalancer 刷新：Compose 重启可能重新分配服务 IP，Gateway 需要重新解析服务名。
CI 的重启恢复检查覆盖这一场景；HTTP 响应/总超时仍为 Day 12 的 3 秒/7 秒。
配置依据：[Reactor Netty DNS 缓存](https://projectreactor.io/docs/netty/release/reference/http-client.html#_host_name_resolution)、
[Compose 动态 IP](https://docs.docker.com/compose/how-tos/networking/#update-containers-on-the-network)。

## 下载报告与排查

打开仓库 **Actions → CI → 对应运行 → Artifacts**。
两个 Job 的报告上传均使用 `if: always()`，保留 14 天；失败不会被 `tee` 隐藏，
GitHub 的 Bash 以 `-e -o pipefail` 执行步骤。

| Artifact 前缀 | 文件 |
| --- | --- |
| `maven-reports-` | Surefire XML/文本报告、`package.log`、`unit-tests.log` |
| `docker-smoke-reports-` | `report.json`、`compose.log`、`http.jsonl`、`services.log`、`docker-smoke.log`、`runner.log` |

失败排查：

1. 构建失败：查看 `package.log`，确认 Java 21、Maven Central 访问和编译错误。
2. 单元测试失败：下载 Surefire XML，查看失败方法及断言。此时 Docker Job 会跳过，整个 CI 仍失败。
3. Docker 构建/启动失败：查看 `compose.log` 和 `runner.log`，检查基础镜像下载、磁盘、内存与健康检查。
4. Smoke 断言失败：先看 `report.json.error` 和最后通过项，再按 `http.jsonl` 的状态码/耗时与 `services.log` 时间戳定位。
5. 没有 `report.json`：脚本可能尚未开始或 Runner 被强制终止，先检查步骤日志及 `docker-smoke.log`。
6. 确认为外部下载/Runner 瞬时故障时，在 Actions 中选择 **Re-run failed jobs**；断言或编译问题应修复并再次 Push。

## main 分支保护

**失败检查自动阻止合并需要 GitHub 分支保护；Workflow 文件本身不会设置仓库规则。**
2026-10-15 对当前私有仓库读取保护规则时，GitHub 返回 HTTP 403：
`Upgrade to GitHub Pro or make this repository public to enable this feature.`
因此当前无法启用/验证强制合并门禁；仓库保持私有，Day 14 完整验收仍有此阻碍。
参见 [GitHub protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)。

账号具备所需计划后，在 **Settings → Branches → Add branch protection rule**：

1. 分支模式填写 `main`，启用 **Require a pull request before merging**。
2. 启用 **Require status checks to pass before merging**，选择精确名称 `build-test`、`docker-smoke`。
3. 启用 **Require branches to be up to date before merging**。
4. 启用 **Do not allow bypassing the above settings**，让管理员也受检查限制。

也可以使用已登录、具有仓库管理权限的 GitHub CLI 应用可审阅的
[`day14-main-protection.json`](day14-main-protection.json)：

```powershell
gh api --method PUT repos/Maknight233/OrderFlow/branches/main/protection --input docs/day14-main-protection.json
gh api repos/Maknight233/OrderFlow/branches/main/protection
```

## 远程验收方法

必须查看实际远程运行，不能以本地构建或 YAML 已提交代替。

1. Push 功能分支，确认远程 `CI` 自动启动；两个 Job 均成功，下载 Docker 报告核对 15/15 PASS。
2. 创建到 `main` 的 PR，确认 `pull_request` 事件也产生运行，两个检查成功。
3. 在单独的故障演练分支，将 `GatewayConcurrencyFilterTest` 的断言改为必然失败并 Push。
   确认 `build-test` 失败、`docker-smoke` 跳过、Maven Artifact 仍可下载，XML 包含失败断言。
   不合并该演练分支；正常功能分支保留正确测试。
4. 分支保护生效后，对失败 PR 检查合并按钮被禁用，再修复失败并确认检查恢复。

实际运行链接、Artifact 及未完成项记录在 [Day 14 验收记录](day14-ci-summary.md)。
