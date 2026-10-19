# Day 15 GHCR 验收记录

2026-10-19：实施与验证中。远程发布、Package 权限及合并结果仅在实际核对后记录。

| 验收项 | 结果 |
| --- | --- |
| 从最新 main 创建 `feature/day15-ghcr` | PASS：起点 `2ca3664a6d6d03a7a6210f89b28fe59e62dd94eb` |
| Day 14 CI 已存在 | PASS：`build-test`、`docker-smoke` 及 15/15 报告门禁 |
| 四 Dockerfile 构建 | 验证中 |
| Maven 与 Gateway 3 项测试 | 验证中 |
| Workflow actionlint | 验证中 |
| OCI 原镜像导出摘要一致 | 验证中 |
| 四镜像 HIGH/CRITICAL 扫描与 JSON | 验证中 |
| PR 测试通过且不发布 | 待执行 |
| main 四个 GHCR Packages | 待执行 |
| 私有 Package 关联/访问权限 | 仓库私有已核对；Package 待发布后核对 |
| SHA 拉取与 main 标签核对 | 待执行 |
| Day 15 PR 合并与 main 远程验收 | 待执行 |

策略为所有 HIGH/CRITICAL（含尚无补丁的漏洞）阻断发布；scanner 错误也失败。
没有豁免或 `continue-on-error`。发现漏洞时保留真实失败及报告，不以降低门禁完成验收。
