# MindCare Agent 验收报告

日期：2026-09-11
基线：`563858e44610bf3187555c16bfee7041dd177c6a`
默认模式：`AGENT_MODE=legacy`
范围：本地合成数据和隔离服务；没有生产部署、真实学生数据或真实通知。

## 结论

确定性实现和 legacy 回归已通过，标准 MCP 协议探针已通过。真实 Ollama 探针也确实走过
SSE、ReactAgent 和工具回填链路，但指定的 Qwen3.5-9B Q4_K_M 模型没有达到计划准入阈值，
所以 `toolCallingVerified` 保持 `false`，不能把本次改造描述为模型准入完成。

## 验证证据

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| P0 基线 | 244 tests，0 failures/errors | 基线 `mvn ... test` |
| Java 全量回归 | 268 tests，0 failures/errors/skipped | `mvn --batch-mode --no-transfer-progress "-DforkCount=0" -q test` |
| SAA 编译/框架闭环 | 通过 | `SaaFrameworkCompatibilityTests` 与 runtime 定向测试 |
| MCP SDK 单元/网络 | 6 tests 通过；`probe.json` 全部通过 | `benchmarks/results/agent-probe/probe.json` |
| P6 运行状态/流式/多模态 | 14 个定向测试通过 | `AgentStreamingTests` 等 |
| 观测/前端/状态 | 13 个定向测试通过；旧 benchmark 26 个通过 | `OperationalMetricsTests` 等 |
| 真实模型 P2 | 30 条（10 类×3）运行；工具事件形状通过率 0.5417，多步 0.0，零工具 1.0；工具名/参数合法率未从公开 SSE 可测 | `benchmarks/results/agent-probe/model-probe.json` |
| P8 真实模型扩展 | 120 条（40 类×3）运行；工具事件形状通过率 0.6667，多步 0.1667，安全重复上限通过；工具名/参数合法率未从公开 SSE 可测 | `benchmarks/results/agent-probe/model-probe-40.json` |
| MySQL smoke | 当前阻塞 | 退出码 1：Docker Desktop Linux engine named pipe 不可用，无法启动隔离 MySQL；未触碰业务库 |

真实模型配置摘要：`multimodalAgent-qwen3.5-9b-benchmark:latest`，digest
`6fe901cba8390b59aa17810896ee02df019d76542960b103ca2318097ebc6923`，Ollama `0.30.10`，
GGUF / Qwen35 / 9.0B / `Q4_K_M`，temperature `0.35`，max tokens `512`，context window `4096`。
模板 SHA256：a4aee8afcf2e0711942cf848899be66016f8d14a889ff9ede07bca099c28f715。
摘要文件只保留事件类型、计数、状态、延迟和上述可复现元数据，不保存原文、token、原始
工具参数或工具结果。

## 已覆盖的安全行为

- `search_knowledge`、`recall_memory`、`get_support_status` 双重白名单和参数校验；
- MCP `/agent-mcp` 使用标准 SDK，验证 `initialize`、`notifications/initialized`、`tools/list`、
  `tools/call`、`isError`、未认证和未知参数拒绝；
  两个学生的标准 SDK 客户端并发调用仍按各自身份隔离；MCP 服务端独立执行同意检查。
- 用户/会话/run 隔离、同会话互斥、取消后不保存部分答案、助手消息只成功保存一次；
- 风险下限、HIGH 不召回长期记忆、报告/投递与 SSE 解耦；
- SSE 保留 `meta/token/error/done`，新增公开 `status/tool_start/tool_result`，不展示内部参数、
  思考或工作人员备注；
- V7 只保存运行元数据和稳定错误码，不保存 prompt、原始参数、原始结果、令牌或思考过程；元数据默认保留 7 天、可配置并按先工具后 run 清理，终态 run 拒绝迟到事件回写。

## 未通过/后续

1. 更换或调优工具调用模型后，重新运行 10 类×3 和 40 类×3；达到工具参数合法率至少 95%、
   多步成功率至少 90% 且补齐工具名/参数合法率观测后，才可将 `AGENT_TOOL_CALLING_VERIFIED` 显式改为 `true`。
2. Docker Desktop 恢复后执行 `scripts/mysql-migration-smoke.ps1`，确认 V0–V7 新建和升级迁移。
3. 多实例部署前将当前进程内 session lease 替换为 Redis/数据库租约；本次不把单 JVM Map
   当作分布式互斥。
