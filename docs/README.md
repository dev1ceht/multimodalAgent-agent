# 文档导航

回到[项目首页](../README.md)。当前启动步骤以[运行与配置](getting-started.md)、环境模板及代码为准；计划与验收记录描述各自记录时的状态。

## 使用与部署

| 文档 | 内容 |
| --- | --- |
| [运行与配置](getting-started.md) | 本地启动、模型、Docker 与工具模式 |
| [Agent 运行手册](runbooks/mindcare-agent.md) | SAA 模式、工具验证、预算与回滚 |
| [上下文与摘要](runbooks/context-budget-and-summary.md) | 长对话预算、滚动摘要与排障 |
| [知识导入](knowledge-kafka-minio-runbook.md) | Kafka / MinIO 导入链路 |
| [MySQL 部署](runbooks/mysql-production-rollout.md) | 迁移与数据库上线 |
| [监控](runbooks/observability.md) | Prometheus、Alertmanager 与 Grafana |
| [日志与追踪](runbooks/logs-and-traces.md) | Loki、Tempo 与 Alloy |

## 开发与评测

- [领域术语与评测约定](../CONTEXT.md)
- [架构决策记录](adr/)
- [RAG 评测](../benchmarks/README.md)与 [Agent 能力探针](../benchmarks/agent/README.md)
- [Agent 验收报告](reports/mindcare-agent-acceptance.md)与[长对话验收报告](reports/context-long-dialogue-acceptance.md)
- [Qwen3.5-9B 微调](qwen35-9b-bf16-lora-finetune-guide.md)与 [Qwen2.5-7B 对照模型](qwen25-7b-lora-finetune-guide.md)

## 设计与历史资料

以下资料保留设计依据和实施过程，不作为新用户启动入口：

- [实施计划与进度](plans/)
- [原始平台设计](superpowers/specs/2026-07-02-campus-mental-health-platform-design.md)
- [知识来源研究](research/campus-mental-health-knowledge-sources.md)
- [AgentScope 框架评估](research/agentscope-framework-assessment.md)
