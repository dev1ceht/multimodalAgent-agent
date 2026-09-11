# Knowledge Kafka + MinIO 验收与运维手册

本文对应 `KNOWLEDGE_INGESTION_MODE=kafka-minio`，用于本地或单机验收。Compose 中的 Kafka、MySQL、MinIO、Qdrant 使用持久卷，但 Kafka 是单 broker 配置，不代表生产高可用；生产环境应替换为受管 Kafka、MinIO 和 Qdrant，并使用独立凭证。

## 启动完整环境

先准备真实可用的 Embedding 服务。知识版本只有在 Embedding、Qdrant 写入和计数校验都成功后才会变为 `ACTIVE`；空的 `DASHSCOPE_API_KEY` 不能完成真实检索验收。

```powershell
Copy-Item .env.example .env
# 编辑 .env：至少替换 JWT_SECRET、AUDIT_RESOURCE_HASH_SECRET、MySQL/Neo4j/MinIO/Grafana 密码，填写 DASHSCOPE_API_KEY。

docker compose --env-file .env config
docker compose --env-file .env up -d --build
docker compose --env-file .env ps
```

应用容器使用容器内地址：`kafka:9092`、`minio:9000`、`qdrant:6333`、`mysql:3306`。宿主机调试地址分别是 Kafka `127.0.0.1:29092`、MinIO API `127.0.0.1:9000`、MinIO Console `http://127.0.0.1:9001`、Qdrant `127.0.0.1:6333`。Kafka topic 和 MinIO bucket 由 `kafka-init`、`minio-init` 创建，应用通过 `depends_on` 等待初始化完成。

检查启动结果：

```powershell
Invoke-WebRequest http://127.0.0.1:9090/actuator/health
docker compose --env-file .env logs --no-color kafka-init minio-init app
docker compose --env-file .env exec kafka /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
docker compose --env-file .env exec mysql mysql -u$env:MYSQL_USER -p$env:MYSQL_PASSWORD $env:MYSQL_DATABASE -e "SELECT version, description FROM flyway_schema_history ORDER BY installed_rank;"
```

如果 `.env` 中未开启演示账号，需要使用已有管理员账号。仅在隔离验收环境可设置 `COMPOSE_DEMO_ACCOUNTS_ENABLED=true`，然后使用 `admin/admin123` 获取 token；不要在生产环境启用或保留演示密码。

## 端到端验收

```powershell
$login = curl.exe -s -X POST http://127.0.0.1:8080/api/auth/login `
  -H 'Content-Type: application/json' `
  -d '{"username":"admin","password":"admin123"}' | ConvertFrom-Json
$token = $login.accessToken
$key = [guid]::NewGuid().ToString()

curl.exe -i -X POST http://127.0.0.1:8080/api/admin/knowledge/file `
  -H "Authorization: Bearer $token" `
  -H "Idempotency-Key: $key" `
  -F 'file=@.\docs\research\campus-mental-health-knowledge-sources.md;type=text/markdown' `
  -F 'source=campus-mental-health-knowledge-sources.md'
```

响应应为 `202`，包含 `uploadId`、`statusUrl` 和 `status=STORED`（或排队中的状态），不应包含 `chunks=0` 之类的同步成功信号。保存 `uploadId` 后轮询：

```powershell
$uploadId = '<response.uploadId>'
curl.exe -s -H "Authorization: Bearer $token" `
  "http://127.0.0.1:8080/api/admin/knowledge/uploads/$uploadId"
```

最终应观察到上传状态 `PARSED`，发布状态 `ACTIVE`，随后在管理页面或实际知识检索接口中检索到上传正文。原件下载接口只接受管理员 token，并从私有 bucket 代理返回：

```powershell
curl.exe -f -L -H "Authorization: Bearer $token" `
  "http://127.0.0.1:8080/api/admin/knowledge/uploads/$uploadId/original" `
  -o .\data\accepted-original.bin
```

用本地 SHA-256 与上传前文件比较，且检查对象不会出现在公开 URL：

```powershell
Get-FileHash .\docs\research\campus-mental-health-knowledge-sources.md -Algorithm SHA256
Get-FileHash .\data\accepted-original.bin -Algorithm SHA256
```

## 必测故障场景

- 使用同一个 `Idempotency-Key` 和同一文件再次提交，必须返回同一个 `uploadId`；更换文件内容或 source/替换参数后复用该 key 必须返回 `409`。
- 上传成功后立即停止 Kafka：`docker compose --env-file .env stop kafka`。上传只能停留在 `STORED`/Outbox 重试状态，不能提前变成可检索；恢复 `docker compose --env-file .env start kafka` 后应继续处理。
- 停止 MinIO 后上传：`docker compose --env-file .env stop minio`。不得返回虚假 `STORED`，应记录 `STORAGE_FAILED`/明确错误；恢复 MinIO 后用原文件和同一个幂等 key 显式重试上传。
- 在解析或索引阶段停止应用并重新启动：`docker compose --env-file .env restart app`。Inbox/业务租约恢复后不能重复创建文档或版本，旧 ACTIVE 版本不能被失败尝试覆盖。
- 暂停 Qdrant：`docker compose --env-file .env stop qdrant`，确认索引失败时旧 ACTIVE 版本仍可用；恢复后通过版本重试接口只重建索引，不重新上传或重新解析原件。
- 未认证请求应为 `401`，学生 token 访问上传、状态、重试和原件下载应为 `403`；不能通过猜测 uploadId 读取他人的原件。

数据库侧可观察交接状态（不要把 payload 正文写入日志）：

```powershell
docker compose --env-file .env exec mysql mysql -u$env:MYSQL_USER -p$env:MYSQL_PASSWORD $env:MYSQL_DATABASE -e "SELECT id, status, dispatch_generation, attempts, last_error_code FROM knowledge_uploads ORDER BY created_at DESC LIMIT 10;"
docker compose --env-file .env exec mysql mysql -u$env:MYSQL_USER -p$env:MYSQL_PASSWORD $env:MYSQL_DATABASE -e "SELECT event_id, event_type, status, attempts FROM knowledge_outbox_events ORDER BY created_at DESC LIMIT 10;"
docker compose --env-file .env exec mysql mysql -u$env:MYSQL_USER -p$env:MYSQL_PASSWORD $env:MYSQL_DATABASE -e "SELECT event_id, event_type, status FROM knowledge_inbox_events ORDER BY created_at DESC LIMIT 10;"
```

## 迁移 smoke 与 legacy 回滚

迁移脚本会启动独立 MySQL、运行应用 `ddl-auto=validate`，并验证 Flyway V0 至 V8 及 Kafka/MinIO 知识流水线字段：

```powershell
pwsh -File .\scripts\mysql-migration-smoke.ps1
```

如果本机没有 Docker 或 `mysql` 客户端，命令应视为未执行，不得用 H2 结果代替真实 MySQL 验收。

回滚优先切换配置，不删除 Kafka 消息、MinIO 原件或 V8 表：

```powershell
# 在 .env 中设置：
COMPOSE_KNOWLEDGE_INGESTION_MODE=legacy
COMPOSE_RAG_RETRIEVAL_MODE=LOCAL_BASELINE
COMPOSE_USE_QDRANT=false

docker compose --env-file .env up -d --build app
```

切换前暂停新的知识写入，等待 Kafka Worker 租约退出；未完成的 upload 保留在数据库中，后续恢复时继续处理。不要用 `docker compose down -v` 作为回滚步骤，因为它会删除 MySQL、Kafka、MinIO 和 Qdrant 的持久数据。

## 监控与清理边界

应用指标和日志应按 `eventId`、`uploadId`、`taskId`、`versionKey` 关联；正文、文件字节、MinIO 密钥和内部 object key 不写入日志。重点关注 Outbox 积压、Inbox queued/running、解析/索引失败、MinIO 失败、DLT 和版本激活失败。

当前实现不会自动删除失联 MinIO 原件。任何清理任务都必须先生成报告，确认宽限期已过、无有效租约且没有 upload/document/version 引用，再单独审批删除。
