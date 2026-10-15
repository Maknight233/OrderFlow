# Docker 运行与验证

四个 Java 服务均使用 Maven / Java 21 构建阶段和 Temurin Java 21 JRE
运行阶段，最终镜像仅包含应用 JAR、JRE 和健康检查使用的 curl。
运行用户为 UID 10001，JVM 以 exec 形式启动，由 Docker init 转发信号。
构建上下文通过 `.dockerignore` 排除 Git、IDE、测试产物和本地环境文件；
BuildKit 缓存 Maven 下载。

## 一键运行

需要 Docker Desktop（Linux containers）及 Compose v2.20+；宿主机无需 Java/Maven。
首次构建需要访问 Maven Central、Docker Hub 和 Ubuntu 软件源。

```powershell
Copy-Item .env.example .env
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 300
docker compose ps
docker compose logs -f order-service inventory-service
```

`.env` 已加入 Git 忽略规则。默认账号仅用于本地开发；可在首次启动前修改
`POSTGRES_USER`、`POSTGRES_PASSWORD`，两个数据库会归该用户所有。
已有数据库卷的账号不会随 `.env` 修改，需按 PostgreSQL 的方式修改数据库角色。
数据库名固定为 `orderflow` 和 `inventory_db`，Flyway 自动创建各自的业务表。

| 服务 | 宿主机地址（默认） | Docker 内部地址 |
| --- | --- | --- |
| Gateway | localhost:9000 | gateway-service:9000 |
| Eureka | localhost:8761 | discovery-service:8761 |
| Order | localhost:8081 | order-service:8081 |
| Inventory | localhost:8082 | inventory-service:8082 |
| PostgreSQL | localhost:5432 | postgres:5432 |
| Redis | localhost:6379 | redis:6379 |
| Kafka | localhost:9092 | kafka:29092 |

全部容器连接项目内的 `orderflow` bridge 网络；网络、容器和卷都由 Compose
项目名隔离，没有固定 `container_name`。Eureka 注册服务 DNS 名称，Gateway
继续通过 `lb://order-service` 和 `lb://inventory-service` 路由。

Kafka 使用单节点 KRaft，INTERNAL 和 EXTERNAL 分别发布容器及宿主机地址；
CONTROLLER 独立使用 29093，不发布到宿主机。修改宿主机 Kafka 端口时使用
`KAFKA_PORT`；`KAFKA_EXTERNAL_HOST` 默认 localhost。`KAFKA_CLUSTER_ID` 在同一
数据卷的整个生命周期内必须保持一致。该配置用于单机开发，不提供多副本容灾。

## 配置与 IntelliJ

所有 `application.yaml` 保持原样。Compose 注入 `SPRING_PROFILES_ACTIVE=docker`，
加载各服务的 `application-docker.yaml`：

| 环境变量 | 用途 |
| --- | --- |
| DB_URL / DB_USERNAME / DB_PASSWORD | JDBC URL 与数据库账号 |
| KAFKA_BOOTSTRAP_SERVERS | Kafka 内部 bootstrap 地址 |
| REDIS_HOST / REDIS_PORT | Redis 内部地址，端口固定为 6379 |
| EUREKA_URL / SERVICE_HOSTNAME | 注册中心与注册实例 DNS |
| TRACING_ENABLED / ZIPKIN_ENDPOINT | 可选 Zipkin 导出 |
| GATEWAY_* | Day12 超时、GET 重试及熔断设置，见 `.env.example` |
| IMAGE_PREFIX / IMAGE_TAG | 构建、使用的镜像名称与标签 |

Compose 文件设置数据库、Kafka、Redis 和 Eureka 的内部环境变量；`.env` 中
的 `REDIS_PORT` 等端口变量仅控制宿主机映射，不改变容器内部端口。
如需外部基础设施，可通过 Compose override 替换对应的服务 environment。

IntelliJ 使用默认 profile、本机端口和原有账号：

```powershell
# 从完整容器模式切换时，先停止 Java 容器，释放端口和 Eureka 注册。
docker compose stop gateway-service order-service inventory-service discovery-service
docker compose up -d --wait postgres redis kafka
# 在 IntelliJ 依次启动 Discovery、Order、Inventory、Gateway。
```

Docker 默认通过 `management.tracing.export.zipkin.enabled=false` 关闭 Zipkin 导出，
保留进程内 tracing 和持久化 trace context，使七服务栈可独立运行。配置项参见
[Spring Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)。
需要原有监控时，可单独运行
`docker compose -f docker-compose.observability.yml up -d`，再设置
`TRACING_ENABLED=true` 并重新执行完整栈的 `up -d`。
默认 Zipkin 地址指向 `host.docker.internal:9411`，Compose 同时添加 Linux host-gateway
映射。原有 Prometheus 配置仍通过宿主机发布端口采集，保持默认业务端口即可。

## 健康检查与恢复

PostgreSQL 检查 TCP 就绪和 `inventory_db` 查询，确保初始化已完成；
Kafka 使用内部 Listener 列出 topics；Redis 使用 PING。
Java 使用 `/actuator/health/readiness`，Order/Inventory readiness 包含数据库，
不包含 Redis 或 Kafka，因此缓存降级与 Outbox 持久化仍可用。
Discovery 就绪后启动业务服务，所有业务服务健康后启动 Gateway。
Eureka 注册和客户端缓存可能稍后才收敛，Smoke Test 会另外等待两个真实 Gateway
只读路由，并初始化首次访问才建立的 Redis/JPA/HTTP 客户端。资源紧张时冷启动首个
写请求可能超过 Day12 超时预算，但数据库事务仍然提交；客户端遇到 POST 的 504
不能直接重发，应先查询业务结果。这一限制与 Day12 保持一致，Smoke Test 不重试 POST。

`depends_on: service_healthy` 控制初次启动顺序；运行中的依赖故障由应用重连、
Outbox 和 Saga 恢复机制处理。`restart: unless-stopped` 在进程退出时重启容器，
不会单独因为 health 状态变为 unhealthy 就重启。参见
[Compose 启动依赖说明](https://docs.docker.com/compose/how-tos/startup-order/)和
[restart 配置](https://docs.docker.com/reference/compose-file/services/#restart)。

```powershell
docker compose restart order-service inventory-service
docker compose down
docker compose up -d --wait --wait-timeout 300
```

`postgres_data` 保存数据库，`redis_data` 保存 AOF，`kafka_data` 保存 KRaft 元数据、
消息及 consumer offsets。`down` 保留这些卷；`down --volumes` 会删除当前项目的数据，
仅在明确需要清空时使用。PostgreSQL 初始化脚本只在空卷上执行。
长时间 Kafka 故障可能耗尽 Outbox 最大尝试次数，此时按
[Day9 恢复 API](day9-saga-workflow.md)重试失败事件，避免直接重建数据卷。

## 自动化 Smoke Test

需要 Python 3.10+，仅使用标准库，无需 pip：

```powershell
python scripts/docker-smoke.py
# 复用已经构建的镜像：
python scripts/docker-smoke.py --skip-build
# 保留故障现场（成功和失败均保留）：
python scripts/docker-smoke.py --skip-build --keep
```

脚本生成唯一 `orderflow-smoke-<id>` 项目、独立数据卷、随机空闲宿主机端口及
数据库密码，显式使用 `.env.example` 并覆盖测试环境，不读取本地 `.env`。
镜像可复用；容器和数据与开发环境隔离。测试保留 Day12 的 3 秒响应和 7 秒总超时，
仅缩短熔断最小调用数与开启等待，不修改应用默认参数。端口选择和容器绑定之间存在很小的竞争窗口；若宿主机其他
程序恰好占用端口，可重新运行。

检查包括：七容器健康、Java 21/非 root、Kafka 外部 metadata、Eureka 路由、
Saga 成功/多 SKU 失败补偿、两侧 Outbox PUBLISHED、重复命令和结果（等待 consumer
offset 提交后核对幂等表与库存）、Redis TTL/离线回源、Kafka 离线重试与恢复、
业务容器重启后的 Saga 继续执行、暂停 Inventory 后的 Day12 504/503/独立熔断及恢复、
JVM 崩溃后的 Docker 自动重启、完整 down/up 后三个数据卷持久化及新订单执行。

每次运行保存 `report.json`、实时 `compose.log`、`http.jsonl` 请求耗时及 `services.log` 至
`integration-tests/target/docker-smoke/<project>/`。失败返回非零退出码；默认无论成功
失败都仅清理本次唯一测试项目及数据卷，`--keep` 可保留现场。
原有 Day9–Day12 测试入口保持不变，可按各自 runbook 运行更广的并发、乱序、DLT
及 HTTP 故障回归。
