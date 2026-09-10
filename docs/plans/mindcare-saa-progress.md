# MindCare SAA 执行进度

最后更新：2026-09-11

## 当前状态

- 计划：[详细执行计划](mindcare-spring-ai-alibaba-execution-plan.md)。
- 已决策：Spring AI Alibaba ReactAgent、单 Agent、Java 17、复用当前业务；默认 `AGENT_MODE=legacy`。
- 原始基线：`563858e44610bf3187555c16bfee7041dd177c6a`。
- 当前阶段：P8 全量回归与交付；P2 真实模型准入明确阻塞，不将 mock/scripted 结果冒充通过。
- 已完成：SAA 依赖与 ReactAgent runtime、聊天分流、固定风险流程、标准 MCP、SSE/取消/运行记录/多模态、指标、7 天可配置元数据清理、终态拒写、探针、runbook 和验收报告。
- 未完成：指定 Qwen3.5-9B 模型未达到多步准入阈值；本机 Docker daemon 不可用，MySQL V0–V7 smoke 尚未执行。

## 阶段状态

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| P0 | 测试基线 | 已完成：基线 244 tests，0 failures/errors |
| P1 | 依赖升级和框架探针 | 已完成：Boot 3.5.8 / Spring AI 1.1.2 / SAA 1.1.2.2 编译并通过 scripted ReactAgent 闭环 |
| P2 | 真实模型准入 | 阻塞：10 类×3 与 40 类×3 已真实运行；多步事件形状成功率分别为 0.0 和 0.1667，且工具名/参数合法率在公开 SSE 中不可测 |
| P3 | 只读 Agent Runtime | 已完成：SAA ReactAgent、预算、白名单工具、答案门禁和受控 HIGH 路径 |
| P4 | 聊天和风险策略 | 已完成：默认 legacy，SAA 接入固定风险/报告/投递流程，同意和风险下限保留 |
| P5 | 标准 MCP | 已完成：标准 SDK `/agent-mcp`、每请求隔离身份、schema/isError/鉴权探针和两个学生并发隔离 |
| P6 | SSE/状态/多模态 | 已完成：公开事件兼容、初始 meta/status 前置、运行表、取消/互斥、实际感知摘要和迁移资源测试 |
| P7 | 评测和观测 | 已完成：模型/工具/拒绝/重复抑制/预算/延迟指标、状态字段、准入未测字段显式记录、旧 benchmark 语义保持 |
| P8 | 全量验证和交付 | 部分完成：40×3 真实样本、Java 全量、文档和回滚说明完成；模型准入与 MySQL smoke 仍阻塞 |

## 依赖与运行配置

- Java `17.0.18`，Maven `3.9.6`。
- Spring Boot parent `3.5.8`；Spring AI `1.1.2`；SAA BOM/Agent Framework `1.1.2.2`。
- MCP Java SDK `1.1.4`，协议传输锁定 `2025-03-26`。
- 真实模型：`multimodalAgent-qwen3.5-9b-benchmark:latest`；Ollama `0.30.10`；digest
  `6fe901cba8390b59aa17810896ee02df019d76542960b103ca2318097ebc6923`；GGUF/Qwen35/9.0B/
  `Q4_K_M`；temperature `0.35`；max tokens `512`；context `4096`；template SHA256 a4aee8afcf2e0711942cf848899be66016f8d14a889ff9ede07bca099c28f715。
- 真实探针使用本机隔离 H2、学生合成账号和本地 Ollama；`AGENT_TOOL_CALLING_VERIFIED=false`。
- 新增表为 V7：`agent_runs`、`agent_tool_executions`；不保存 prompt、原始参数、原始结果、令牌或思考过程。

## 已执行命令与证据

- `mvn --batch-mode --no-transfer-progress "-DforkCount=0" test`（基线）：244 tests，0 failures/errors。
- Maven compile/package 与 SAA 依赖解析通过；编译仅有既有/版本 API deprecation warning。
- `mvn ... -Dtest=AgentMcpSdkServerTests,AgentMcpIntegrationTests test`：6 tests 通过。
- `mvn ... -Dtest=AgentSessionIsolationTests,AgentRunPersistenceServiceTests,AgentMultimodalTests,AgentStreamingTests,FlywayMigrationResourceTests test`：14 tests 通过。
- `mvn ... -Dtest=AgentStatusControllerTests,OperationalMetricsTests,FrontendWorkspaceResourceTests,AgentRunPersistenceServiceTests,AgentStreamingTests test`：13 tests 通过。
- `mvn --batch-mode --no-transfer-progress "-DforkCount=0" -q test`：74 suites、268 tests，0 failures/errors/skipped。
- `python -m pytest benchmarks/test_benchmark.py`：26 tests 通过；`python -m py_compile benchmarks/agent/*.py` 通过。
- `python benchmarks/agent/probe.py --base-url http://127.0.0.1:18080 --grant-consent --require-tool-success`：全部通过，证据为 `benchmarks/results/agent-probe/probe.json`。
- `python benchmarks/agent/model_probe.py ... --limit 10 --repetitions 3`：30 条真实样本；工具事件形状通过率 0.5417，零工具 1.0，多步 0.0，安全重复上限通过；工具名/参数合法率未测，准入字段为 false，证据为 `benchmarks/results/agent-probe/model-probe.json`。
- `python benchmarks/agent/model_probe.py ... --limit 40 --repetitions 3 --output benchmarks/results/agent-probe/model-probe-40.json`：120 条真实样本；工具事件形状通过率 0.6667，零工具 1.0，多步 0.1667，安全重复上限通过；工具名/参数合法率未测，准入字段为 false，证据为 `benchmarks/results/agent-probe/model-probe-40.json`。

## 具体阻塞与解释

1. 真实模型准入：基础单工具场景可以稳定走出 `tool_start/tool_result`，但指定模型对需要改写/失败修正/两步以上工具的场景不稳定；P2 计划阈值（工具参数合法率至少 95%、多步至少 90%）未满足。因此状态端点的 `toolCallingVerified` 保持 false。
2. MySQL smoke：本机 `docker` 命令存在，但 Docker Desktop Linux engine named pipe 不可用，无法启动隔离 MySQL；没有触碰业务数据库。恢复 Docker 后运行 `scripts/mysql-migration-smoke.ps1`。
3. 会话互斥：当前实现是单 JVM 租约，适合本地/单实例验证；多实例生产前必须换成 Redis/数据库租约。

## 交付文件

- 运行手册：[docs/runbooks/mindcare-agent.md](../runbooks/mindcare-agent.md)。
- 验收报告：[docs/reports/mindcare-agent-acceptance.md](../reports/mindcare-agent-acceptance.md)。
- 标准 MCP 探针：[benchmarks/agent/probe.py](../../benchmarks/agent/probe.py)。
- 真实模型探针：[benchmarks/agent/model_probe.py](../../benchmarks/agent/model_probe.py)。
- 脱敏证据：`benchmarks/results/agent-probe/`（如被 gitignore 忽略，则以本地运行产物为准）。

## 下一条动作

Java/Python 回归已完成，真实模型准入与 MySQL smoke 的阻塞已记录；完成最终 diff 检查后提交当前分支。

## 阶段记录

### P0 / P1 / P2–P8：2026-09-11

- 实施分支：`codex/gauzmem-long-term-memory`。
- 接手前已有未提交改动：`README.md` 与 `docs/plans/`；已保留并把 README 状态同步为当前实现。
- 计划依赖与原生 API 以锁定版本编译为准；SAA `ReactAgent` 使用 `getAndCompileGraph()`，底层 ChatModel 关闭 internal tool execution，避免重复工具循环负责人。
- 真实模型不达准入阈值，故不修改 `AGENT_TOOL_CALLING_VERIFIED` 默认值，不做生产开启。

### 下一次接手

优先读取本文件的“当前状态”和“具体阻塞与解释”，不要重复已完成的基线和 40×3 探针；先查看 `git status --short` 与最新测试结果。
