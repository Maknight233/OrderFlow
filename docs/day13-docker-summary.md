# Day13 Docker 验证结果

2026-10-15 实际执行：**15/15 容器 Smoke Test + 3/3 Gateway 并发单元测试通过**。

| 交付阶段 | 完成情况 |
| --- | --- |
| Phase 1：四个 Java 21 多阶段 Dockerfile | 4/4 |
| Phase 2：完整 Compose、统一网络、双 Listener、健康启动依赖 | 4/4 |
| Phase 3：Docker Profile、环境变量、忽略文件、重启策略与卷 | 3/3 |
| Phase 4：构建启动、业务与故障验证、Smoke Test、文档和 Git 交付 | 5/5 |

## 执行环境与命令

Windows / Docker Desktop Linux containers；Engine 29.8.2，Compose v5.6.0；
四个运行镜像均实际检查为 Temurin Java 21、UID 10001。
基础设施使用 PostgreSQL 16、Redis 7、Apache Kafka 4.2.1。

```powershell
docker compose config --quiet
docker compose --progress plain build
python scripts/docker-smoke.py
mvn -B -ntp -pl gateway-service '-Dtest=GatewayConcurrencyFilterTest' test
```

Smoke Test 项目：`orderflow-smoke-43fea12098`。
完整成功运行时间：17:48:06–17:59:02 UTC，约 11 分钟。
脚本实际执行 `up -d --build --wait`，中途保留卷 `down/up`，最终清理本次项目及卷；
已确认没有遗留本次测试的容器或数据卷。

本机原始证据（生成文件，不提交 Git）：

- `integration-tests/target/docker-smoke/orderflow-smoke-43fea12098/report.json`
- 同目录 `compose.log`、`http.jsonl`、`services.log`
- `integration-tests/target/docker-build/build.log` 与 `gateway-unit.log`
- `gateway-service/target/surefire-reports/TEST-com.rom.orderflow.gateway.resilience.GatewayConcurrencyFilterTest.xml`

## 容器检查

| # | 检查 | 结果 |
| --- | --- | --- |
| 1 | 七容器健康；四个 Java 21 / 非 root 运行进程 | PASS |
| 2 | 从宿主机读取 Kafka metadata，验证外部发布 host/port | PASS |
| 3 | Eureka 注册 Docker DNS，Gateway 路由 Order / Inventory | PASS |
| 4 | Gateway → Order → Outbox → Kafka → Inventory → Saga COMPLETED | PASS |
| 5 | 多 SKU 库存不足，Saga CANCELLED、补偿及库存保持正确 | PASS |
| 6 | 重复命令和结果消费提交后，幂等记录各一条、库存只扣一次 | PASS |
| 7 | Redis 实际缓存与 TTL：Order ≤45s / Inventory ≤5s | PASS |
| 8 | Redis 停止期间数据库回源、订单继续完成，Redis 恢复 | PASS |
| 9 | Kafka 停止时持久化 Outbox 重试，恢复后自动完成订单 | PASS |
| 10 | Inventory 停止后发布命令，重启业务服务后 Saga 继续完成 | PASS |
| 11 | 暂停 Inventory，验证 504、CIRCUIT_OPEN 503 和 Order 路由独立 | PASS |
| 12 | Inventory 恢复后熔断恢复，期间创建的 Kafka Saga 继续完成 | PASS |
| 13 | 强制终止 Order JVM，Docker 自动重启，RestartCount=1 | PASS |
| 14 | 完整重建容器后 PostgreSQL、Redis AOF、Kafka 日志位点保留 | PASS |
| 15 | 数据恢复后创建新订单，完整 Saga 再次成功 | PASS |

两侧正常流程各验证了两条 `PUBLISHED` Outbox 记录。重建时保留六笔订单，
Redis 持久化标记与 Kafka topic offsets 一致；重建后的新订单成为第七笔订单。
测试 SKU 初始库存 20，最终库存 11、预留库存 0，失败补偿和重复事件没有额外扣减。
成功流程订单：`a795b73d-c0a6-44c7-a016-223450015937`；
重建后订单：`0028c1ca-3178-4bdb-891a-8cbf1551df18`。

HTTP 记录包含九次业务 POST，均返回 201，最长 0.892 秒；故障注入/恢复等待期间
观察到四次 504 和 112 次 503。只读探测允许重试，测试脚本没有重发 POST。
全部默认 Compose 服务的网络连接与 `unless-stopped` 策略也已检查。

## 范围与运行边界

保留所有原始 `application.yaml`，本地 IntelliJ 配置未修改。
Docker 测试使用独立账号和随机端口，验证了自定义 PostgreSQL 用户的初始化。
Day12 响应/总超时保留 3s/7s；仅在测试环境将熔断最小调用数设为 4、开启等待设为 3s。

Smoke Test 先等待真实只读路由并初始化首次访问客户端。冷启动写请求可能超过预算，
返回 504 时事务仍可能已提交；恢复与查询方式见 [Docker runbook](day13-docker.md)。
本次额外运行了三项 Gateway 并发单元测试；原有 Day9–Day12 全套回归和可选监控栈
没有重新运行，不将此前 79 项测试的历史结果计入此次验证。
