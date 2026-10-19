# Day 15：GHCR 镜像发布与安全扫描

在 [Day 14 CI](day14-ci.md) 中增加四镜像 Buildx/Trivy 检查及 GHCR 发布。
沿用 [Day 13 Dockerfile 与 Compose](day13-docker.md)。实际执行结果见
[Day 15 验收记录](day15-ghcr-summary.md)。

## 流程与权限

`build-test` 成功后，`docker-smoke` 与四服务 `scan-images` 并行运行。
扫描 Job 使用官方 Docker Actions 构建单平台 `linux/amd64` OCI 归档，
将 OCI 归档解包为 layout 目录后，Trivy 扫描其中的 OS 和 Java 依赖。
所有扫描成功且 Smoke 达到 15/15 后，
`publish-images` 才能开始；它显式依赖 `build-test`、`docker-smoke`、`scan-images`。

发布使用当前 run/attempt 的归档，以 Buildx 的 `oci-layout://` 命名上下文导出，
不重新运行 Maven、不重新下载运行时依赖。发布前后的 OCI manifest digest 必须相同。
四服务均实际拉取 SHA 镜像、核对 revision label、执行 `java -version` 成功后，
`promote-main` 才逐个更新 main 标签，并核对 main/SHA manifest digest 相同。

| 事件 | Maven / Smoke / Trivy | GHCR 写入 |
| --- | --- | --- |
| feature 分支 Push | 运行 | 无 |
| Pull Request（包含 fork） | 运行 | 无 |
| Merge Queue / 手动运行 | 运行 | 无 |
| main Push | 运行 | 全部门禁通过后发布 |
| Git Tag Push | 当前不触发 | 无；预留正式版本扩展 |

全局权限保持 `contents: read`。只有 main 发布/提升 Job 设置 `packages: write`；
使用 `docker/login-action` 与 `secrets.GITHUB_TOKEN` 登录 `ghcr.io`。
不添加 PAT、仓库 Secret 或 `security-events: write`。
新增 Docker/Trivy/下载 Actions 固定完整 commit SHA，注释记录对应版本。

## 镜像与版本规则

| 服务 | 镜像 |
| --- | --- |
| Discovery | `ghcr.io/maknight233/orderflow-discovery-service` |
| Gateway | `ghcr.io/maknight233/orderflow-gateway-service` |
| Order | `ghcr.io/maknight233/orderflow-order-service` |
| Inventory | `ghcr.io/maknight233/orderflow-inventory-service` |

镜像路径由小写 `github.repository` 加服务名生成，兼容已有 Compose 的
`IMAGE_PREFIX` / `IMAGE_TAG`，无需额外部署 Compose 文件。
`docker/metadata-action` 生成 `sha-<完整 40 位 github.sha>` 和 OCI labels。
main 的 SHA 是 main Push 提交（合并时为合并提交），并非 PR 的临时 merge SHA。
`main` 只用于定位最新通过发布验证的构建，不用于固定版本部署；禁用 `latest`。

SHA 标签提供提交到镜像的映射。发布前逐页查询 Package 版本；若同名 SHA 标签已存在，
只接受相同 digest，拒绝以重新构建的不同内容覆盖它。scanner/registry 失败重跑应优先
选择 **Re-run failed jobs**，使用同次运行已批准的镜像归档；归档过期则需重新构建与扫描。
如已发布 SHA 的重新构建产生不同 digest，应沿用原镜像或创建新提交发布。
GHCR 标签本身仍可被仓库外有写权限的人修改，
且浮动基础镜像、软件源和构建时间使同一源码重新构建不保证逐字节相同。
发布 Job 在 Actions Summary 记录 `image@sha256:...`，正式部署应优先记录并使用
该不可变 digest；保留部署 SHA/digest 才能准确回滚。不要通过重新构建旧提交恢复旧镜像。

当前严格限制只有 main Push 发布。未来启用 Git Tag 正式发布时，在同一变更中：

1. 在 `on.push.tags` 加入 `v[0-9]*.[0-9]*.[0-9]*`，并验证 Tag 是 main 上的提交。
2. 扩展归档上传、`publish-images` 的事件条件，使受信任 Tag Push 可以进入；
   保持 PR、手动运行没有发布入口。
3. 发布 metadata 已有 `type=semver,pattern={{version}}` 模板；在正式发布 Job 也启用它，
   将 `v1.0.0` 映射到 `1.0.0`，仍设置 `latest=false`。
   应给该提交已经扫描并发布的 SHA 镜像增加版本别名，使二者指向同一 digest，
   避免重新构建后触发 SHA 防覆盖门禁；不存在 SHA 镜像时必须先完成完整测试、构建和扫描。
4. `promote-main` 保持只处理 main，版本发布不移动 main 标签；保护 `v*` 标签，
   防止已发布版本被重新指向其他提交。

## 私有 Package 访问权限

当前仓库为私有。第一次由本仓库 `GITHUB_TOKEN` 发布的 Package 默认私有，
OCI `org.opencontainers.image.source` 指向 `https://github.com/Maknight233/OrderFlow`。
不要为通过验收而修改仓库或 Package 的可见性。

首次发布后逐个打开个人主页 Packages → 对应镜像 → Package settings：

1. 确认 Package visibility 为 **Private**，关联 repository 为 `Maknight233/OrderFlow`。
2. 确认 **Inherit access from source repository**；如没有继承，在 **Manage Actions access**
   为本仓库授予 Write（消费者仓库仅需 Read）。
3. 同仓库发布后的 pull 步骤使用当前 token 验证实际写入与读取权限。
   另一个部署仓库必须先获得 Package 的 Read Actions access，再用其 `GITHUB_TOKEN` 拉取。
4. 若同名 Package 曾由 CLI 创建而未关联仓库，先修正关联/Actions access；
   `packages: write` 不会自动授予一个未授权既有 Package 的访问权。

发布验收核对 Package API 的 `visibility=private`、镜像的 source/revision 标签，
并实际使用当前 workflow token push/pull。API 若提供 `repository`，额外核对其稳定的
repository ID；该字段为可选元数据。首次远程响应对四个 Package 均未返回此字段，
不能把字段缺失直接解释为 token 没有访问权限。继承复选框与关联设置仍可在 Package settings
按上面步骤核对；不声称 API 已验证未返回的 UI 设置。

本机拉取私有镜像需已登录 GHCR。需要时用具有 `read:packages` 的个人凭据
通过 `docker login ghcr.io --username Maknight233 --password-stdin` 从标准输入登录，
凭据交给本机 credential store；不要写入命令历史、README、`.env`、仓库或 Actions Secrets。
CI 发布始终只用 `GITHUB_TOKEN`。

## 拉取、部署与回滚

```powershell
$commitSha = '<已成功发布的完整 main 提交 SHA>'
$image = "ghcr.io/maknight233/orderflow-gateway-service:sha-$commitSha"
docker pull $image
docker image inspect $image --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}'
docker run --rm --entrypoint java $image -version

$env:IMAGE_PREFIX = 'ghcr.io/maknight233/orderflow'
$env:IMAGE_TAG = "sha-$commitSha"
docker compose pull discovery-service gateway-service order-service inventory-service
docker compose up -d --no-build --wait --wait-timeout 300

# 更强的不可变部署引用，digest 从对应 Actions Summary 复制：
docker pull ghcr.io/maknight233/orderflow-gateway-service@sha256:<published-manifest-digest>
```

回滚时将四服务切回同一次已验证发布的 SHA，再执行 pull 与 `up --no-build`。
数据库迁移和业务数据回滚需另行评估；切换容器镜像不会回滚数据。

## 安全阻断策略与报告

Trivy Action v0.36.0 / CLI v0.76.0；`scanners: vuln`、`severity: HIGH,CRITICAL`、
`ignore-unfixed: false`、`exit-code: 1`。扫描最终运行镜像（不包含 Maven 构建阶段），
包括基础操作系统及 JAR 库。没有忽略文件、漏洞白名单、`continue-on-error` 或失败吞掉逻辑。
HIGH/CRITICAL 无论是否已有补丁都阻止发布；数据库下载、解析和 scanner 运行错误同样阻止。

JSON 报告通过 `if: always()` 上传，名称包含服务、run ID、attempt，保留 14 天。
扫描未产生报告时上传步骤也失败并在日志说明文件缺失，不能解释为“没有漏洞”。
扫描通过的 main 构建另保存 OCI 归档 3 天，仅作为同次运行的发布输入。
归档名称包含服务和 run ID，完整重跑会替换它；只重跑失败发布 Job 可复用此前成功扫描的归档。
不用付费私有仓库 Code Scanning 能力也可下载 JSON，因此无需 SARIF 上传权限。

处理失败时先从 Artifact 确认 CVE、依赖路径与修复版本，再升级基础镜像/应用依赖。
没有可用补丁时保留阻断，不静默设置 `ignore-unfixed` 或将退出码改为 0。
外部数据库临时不可用可重跑失败检查；重新构建后必须重新扫描。

本次首次本地扫描发现 Jackson 2/3、Tomcat、Kafka 的 lz4 以及 Eureka 页面使用的
FreeMarker 漏洞。保留 Spring Boot 4.1.1，在服务 POM 中使用受支持的 BOM 属性覆盖
Jackson 2.21.7 / 3.1.7、Tomcat 11.0.25、FreeMarker 2.3.35，并通过业务服务的
dependencyManagement 将 `at.yawk.lz4:lz4-java` 固定为 1.11.4。
Boot BOM 纳入相同或更高修复版本后可移除这些覆盖；升级后仍需重新执行扫描与 Smoke。

## 发布失败与验收

任何 Maven、Smoke、镜像构建或扫描失败，发布全部跳过，main 标签不更新。
SHA 发布/拉取验证有任意服务失败，`promote-main` 全部跳过；已推送的部分 SHA
可以保留用于排查，不作为完整发布部署。main Push 运行串行且不自动取消，
提升前额外校验仍为 main HEAD，旧提交重跑不会回退 main。

GHCR 不提供跨四个 Package 的原子标签更新：若 registry 在更新 main 标签途中故障，
可能只有部分 main 标签移动，Job 明确失败。部署始终固定完整已验证发布的 SHA/digest，
修复后重新核对四个 Package；不能将这种故障报告为完整发布成功。

验收必须记录实际链接与结论：

1. PR 的 Maven、15/15 Smoke、四扫描通过；`publish-images`、`promote-main` 为 skipped。
2. 若任何严重漏洞存在，确认非零扫描退出码、可下载 JSON、发布跳过。
3. 检查本次 PR head 的全部必需检查通过，再合并；不绕过失败检查。
4. main Push 的四发布 Job 和 main 提升成功，核对四私有 Package 和 source/revision labels。
5. 下载安全报告；确认 workflow 已实际拉取 SHA 镜像并校验 Java/摘要。
6. 记录 main 提交、镜像 digest、报告、私有 Package access 与任何未完成项目。

Day 14 的私有仓库计划限制可能仍阻止强制分支保护；工作流内发布依赖仍会阻止
失败构建发布，但不能据此声称 GitHub 已禁止手动合并失败 PR。

依据：[Docker OCI 命名上下文](https://docs.docker.com/reference/cli/docker/buildx/build/#additional-build-contexts---build-context)、
[Trivy Action](https://github.com/aquasecurity/trivy-action)、
[GHCR 与 GITHUB_TOKEN](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)、
[Package 权限](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility)。
