# MySQL 生产迁移与回滚运行手册

## 目标

本项目的 MySQL profile 使用 `ddl-auto=validate`，数据库结构只由 Flyway 管理。发布前必须确认迁移历史、应用启动和数据库备份都可追溯。

## 迁移路径

- 新建空库：按 `V0 → V1 → V2 → V3 → V4` 顺序执行。`V0` 创建基础表，`V1` 是基线标记，`V2` 增加案件并发/SLA 字段，`V3` 增加逾期升级字段和投递任务关联，`V4` 为知识库文档增加乐观锁版本列。
- 已由 Hibernate 创建的存量非空库：使用 MySQL profile 的 `baseline-on-migrate=true` 在版本 1 建立基线，跳过 `V0`/`V1` 的表创建内容，然后执行 `V2`、`V3`、`V4`。
- 不要修改已经执行过的迁移文件，不要在生产环境执行 `flyway clean`，也不要手工删除 `flyway_schema_history`。

## 发布前检查 / Backup checklist

1. 确认已完成 MySQL 全量备份，并记录备份文件、时间、库名和恢复演练结果。
2. 在发布环境使用 PowerShell 7 或 Windows PowerShell 执行冒烟脚本；脚本启动临时 MySQL、启动应用并检查 `/actuator/health`、Flyway 版本 `0/1/2/3` 及关键字段：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\mysql-migration-smoke.ps1
   ```

3. 生产发布前核对 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、`SPRING_PROFILES_ACTIVE=mysql`，确认应用日志没有 Flyway validation error。
4. 先发布一台实例观察迁移和健康状态，再滚动发布其余实例。应用必须等待 MySQL 和 Redis healthcheck 通过后再启动。

## CI 质量门禁

`.github/workflows/ci.yml` 在 push、pull request 和人工触发时运行两个独立任务：

- `Java tests` 在 Java 17 上执行完整 Maven 测试套件。
- `MySQL migration smoke` 在临时 MySQL 8.4 上启动 MySQL profile，验证应用健康状态、Flyway `V0 → V4` 顺序和关键字段。

两个任务都必须通过后才能合并。任务失败时，从对应的 GitHub Actions artifacts 下载 `surefire-reports` 或 `mysql-migration-smoke-logs`；日志保留 7 天，禁止把数据库口令或用户数据写入 artifact。

CI 冒烟覆盖新建空库路径。存量非空库的版本 1 baseline 路径仍须在隔离的预发布数据库上按本手册完成备份、基线和恢复演练。

仓库管理员应在默认分支保护中把 `Java tests` 和 `MySQL migration smoke` 配置为 required checks；工作流本身不执行生产部署，也不接触生产凭据。

## 迁移后检查

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SHOW COLUMNS FROM risk_cases;
SHOW COLUMNS FROM delivery_tasks;
```

确认最新迁移成功，`risk_cases` 包含 `version`、`sla_due_at`、`overdue_escalated_at`，`delivery_tasks` 包含 `risk_case_id`，随后检查应用健康端点：

```text
GET /actuator/health
```

## 失败处理与回滚 / Rollback

- Flyway 失败时停止继续扩容，保留应用日志和 `flyway_schema_history`，先判断失败发生在哪个版本；修复必须通过新的前向迁移完成。
- 如果应用尚未完成切流，可停止新版本并恢复上一版本应用。V2/V3/V4 都是向前兼容的 schema 扩展（新增列、索引、外键或可空关联），旧应用通常可以读取同一库，但仍必须按发布前兼容性验证结果决定是否回退。
- 如果迁移已经改变数据或旧版本无法兼容，使用发布前备份恢复到隔离库，验证后再切换连接；不要直接删除新增列或回滚 Flyway 历史。
- 恢复后重新执行健康检查、登录检查、风险案件查询和投递任务查询，并记录恢复时间、责任人和最终数据库版本。

## 日常操作边界

生产库账号只授予应用所需权限；迁移账号、应用账号和备份账号分离。升级通知失败由持久化投递队列重试，不能通过修改案件状态来“清除”告警。
