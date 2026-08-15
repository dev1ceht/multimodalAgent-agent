# AgentScope 框架适配评估

- 评估日期：2026-08-13
- 评估对象：当前 `multimodalAgent` Java/Spring 校园心理健康项目
- 结论：**可以采用 AgentScope，但建议采用 AgentScope Java 2.0 做有边界的增量试点，不建议用 Python sidecar 重写，也不建议让 ReAct Agent 接管心理安全与告警事务。**

## 1. 当前项目的关键架构约束

当前项目是 JDK 17、Spring Boot 3.3.5、Project Reactor 和 Spring AI 1.0.0 应用。模型层已经通过 `AiClient` 隔离 Ollama、OpenAI-compatible 和 mock 实现；聊天通过 Reactor/SSE 流式返回。

这里的“agentic”并不是一个自由运行的通用 ReAct Agent，而是受控的业务编排：

1. 路由同时输出 `needsRag` 与 `riskLevel`，两个维度相互独立。
2. `AgenticRagService` 负责查询规划、检索、证据复核和一次有界补充检索，检索失败会显式 fail closed/degrade。
3. 多模态附件先经 Whisper/MediaPipe 类适配器提取信号，再由确定性融合服务给出风险下限。
4. 风险只允许向上抬升；达到策略阈值后，报告、个案和投递任务由数据库事务和后台任务处理。
5. Excel/邮件/MCP 投递具有幂等键、租约、重试和持久化状态，不依赖模型或 SSE 是否成功完成。
6. 离线门禁要求 `safetyGatePass=true`、高风险召回率 1.0，安全失败不可由其他质量指标补偿。

因此，任何框架迁移都必须保留这些确定性边界。

## 2. AgentScope 当前版本判断

AgentScope 已有 Python、Java 和 TypeScript 三种独立实现。当前仓库应优先选择 **AgentScope Java 2.0**：

- AgentScope Java 2.0.0 于 2026-07-10 GA，官方称其为 2.0 线首个 production-ready 版本；要求 JDK 17+，底层采用 Reactor，可嵌入 Spring Boot、Quarkus 或 Micronaut。[Java 2.0 发布说明](https://java.agentscope.io/v2/en/docs/others/release-notes.html) [Java 官方首页](https://java.agentscope.io/)
- Java 2.0 把 `ReActAgent` 定位为无状态的推理/工具循环，把 workspace、持久化、记忆压缩、子 Agent、沙箱等放入 `HarnessAgent`。状态可按 `(userId, sessionId)` 保存，适合横向扩展。[Agent 文档](https://java.agentscope.io/v2/en/docs/building-blocks/agent.html) [Harness 架构](https://java.agentscope.io/v2/en/docs/harness/architecture.html)
- Python 主线也已经进入 2.x；截至本次评估，PyPI 最新为 2.0.6。旧 0.x、1.x 示例的包名和 API 差异很大，不能混用；旧 `agentscope-runtime` 也已建议迁移到 2.x。[Python PyPI](https://pypi.org/project/agentscope/) [Python releases](https://github.com/agentscope-ai/agentscope/releases) [旧 Runtime 仓库](https://github.com/agentscope-ai/agentscope-runtime)
- 两个主仓库均采用 Apache-2.0 许可证。[AgentScope Java 仓库](https://github.com/agentscope-ai/agentscope-java) [AgentScope Python 仓库](https://github.com/agentscope-ai/agentscope)

如果引入 Python 版，需要增加独立服务、跨进程流式协议、分布式追踪、超时、部署和敏感数据边界。当前项目已经是完整 Java 平台，这些成本没有对应收益，因此不推荐 Python sidecar。

## 3. 能力匹配

| 当前需求 | AgentScope Java 2.0 能力 | 适配结论 |
| --- | --- | --- |
| JDK 17 / Reactor / Spring Boot | 要求 JDK 17+，采用 Reactor；官方说明可直接嵌入 Spring Boot | 高匹配 |
| Ollama、OpenAI-compatible、Qwen/DashScope | 有独立 provider extension；支持 OpenAI-compatible endpoint、DashScope 和 Ollama | 高匹配 |
| 流式输出 | `streamEvents()` 提供类型化 Agent 事件 | 可通过适配器映射到现有 `meta/token/error/done` SSE，不必改前端协议 |
| 结构化 JSON | `call` 支持 Java class/JSON Schema；2.0 GA 支持原生结构化输出与工具调用协同 | 可替代部分手写 JSON 提取，但仍应保留服务端 schema 与领域校验 |
| 图像/音频/视频 | 消息和 formatter 支持统一数据块；DashScope 等 provider 支持多模态 | 可用，但当前 Whisper/MediaPipe/融合链路更可控，首期不应替换 |
| 工具与 MCP | Toolkit、工具分组、读写属性、权限引擎；MCP 支持 STDIO、SSE、Streamable HTTP | 适合只读知识检索工具；高风险写工具必须禁用自主调用 |
| 会话与记忆 | AgentStateStore、Redis 等分布式状态、Harness 记忆与压缩 | 能力重叠；首期保留当前 DB/Redis 记忆作为唯一事实来源 |
| 可观测性 | OpenTelemetry middleware 覆盖 Agent、模型和工具 span | 可接入当前 OTLP/Tempo 链路，但须避免把心理敏感原文写入 span |
| RAG | 有 RAG 抽象，但 Java 2.0 FAQ 明确 knowledge base、document reader 等组件仍在完善 | 不替换当前 Elasticsearch 混合检索、证据溯源和质量门禁 |
| 多 Agent / 子 Agent | 支持声明式子 Agent、同步或后台委派、事件转发 | 技术可行，但当前学生安全主链路没有拆成多 Agent 的必要 |

主要官方能力依据：[模型](https://java.agentscope.io/v2/en/docs/building-blocks/model.html)、[工具与 MCP](https://java.agentscope.io/v2/en/docs/building-blocks/tool.html)、[中间件与 OTel](https://java.agentscope.io/v2/en/docs/building-blocks/middleware.html)、[Java 2.0 FAQ](https://java.agentscope.io/v2/en/docs/others/faq.html)。

## 4. 推荐采用边界

### 适合交给 AgentScope 的部分

- 低风险、只读的 Agentic RAG 查询规划与检索工具选择。
- 模型 provider、类型化事件、结构化输出和统一工具描述。
- 有最大迭代次数、工具白名单和超时限制的 ReAct PoC。
- Agent/模型/工具层的 OpenTelemetry span。
- 将来确有复杂任务需求时的内部管理助手或受控子 Agent。

### 必须继续由现有业务代码掌握的部分

- 风险等级下限、风险只能上调的合并规则。
- 是否创建报告/个案、人员可见范围、同意与审计。
- Excel/邮件/告警投递的事务、幂等、租约、重试和 SLA。
- Elasticsearch BM25 + KNN + RRF、证据溯源、证据质量门禁和 fail-closed 行为。
- 多模态融合权重、人工复核边界和安全回归门禁。

尤其不要把 `send_alert`、`open_risk_case`、`close_case`、`export_sensitive_report` 一类工具直接暴露给学生对话 ReAct Agent。框架的权限引擎是额外防线，不能替代领域授权和事务约束。

## 5. 三种采用方案

| 方案 | 价值 | 风险/成本 | 判断 |
| --- | --- | --- | --- |
| A. 仅替换 Spring AI 模型适配层 | 获得统一 provider 和事件模型 | 与现有 `AiClient` 价值重叠，迁移收益有限 | 可做但不优先 |
| B. 在现有 Java 服务内增加“只读、有界 RAG Agent” | 验证 ReAct、工具权限、结构化输出和观测，同时保留安全边界 | 需要 SSE/消息/trace 适配和依赖收敛测试 | **推荐** |
| C. 用 HarnessAgent/多 Agent 重写整个聊天与风险流程 | 形式上更 agentic | 双重记忆、事务边界丢失、延迟与安全不确定性最大 | **不推荐** |

## 6. 建议 PoC 路线

1. 依赖只引入 `agentscope-core` 和所需模型 extension，固定 Java 2.0 GA 版本；首期不引入 `HarnessAgent`、文件/命令工具或 Python Runtime。Spring Boot 应优先显式构造模型，避免一开始同时启用多套自动配置。
2. 新建独立 `AgentRuntime`/`RagPlanningAgent` 接口，不改变 `AiClient` 和现有默认实现；通过 feature flag 做旁路或 A/B。
3. 仅注册一个只读 `searchKnowledge(query, topK)` 工具，内部继续调用现有 `EvidenceRetriever`；标记只读，设置白名单、最大迭代次数、总超时和字符预算。
4. Agent 只返回结构化的查询计划/证据复核结果；最终风险判级、prompt 构造、回答流、报告与告警仍走现有服务。
5. 把 AgentScope `AgentEvent` 映射为现有 SSE 事件，把 OTel span 接入现有 traceId；对 prompt、tool input/result 做脱敏或不采集。
6. 运行现有 190 条 stage、80 条 e2e 和回归 gate。至少满足：`safetyGatePass=true`、`highRiskRecall=1.0`、风险准确率不低于 0.95、路由/RAG 路由不低于 0.90、错误率不高于 0.02，并比较 TTFT、总延迟、token/调用轮数和检索召回。
7. 只有 PoC 在安全、质量和运维指标上不退化，才考虑把更多低风险编排迁入 AgentScope。

## 7. 风险清单

- AgentScope Java 2.0 GA 发布时间较新，应固定版本并做 Maven dependency convergence，重点检查 Reactor、Jackson、OpenTelemetry 与当前 Spring Boot BOM 的冲突。
- ReAct 会增加模型轮数、延迟、token 消耗和不可预测分支；必须有 max iterations、timeout、预算和工具白名单。
- AgentScope 记忆与当前数据库/Redis 记忆同时启用会形成双重事实来源，并可能扩大敏感数据留存范围。
- Studio/trace 很适合调试，但心理健康原文、风险标签和工具结果必须按现有隐私策略脱敏。
- 多模态“消息可承载某种数据”不等于当前 Ollama 模型能可靠理解该模态；需按模型逐项验证。
- Java 2.0 原生 RAG 组件仍在演进，不应为了框架统一而放弃当前已评测的检索和证据治理。

## 8. 附带安全发现

仓库 `application.yml` 的 embedding API key 具有一个非空默认值，看起来可能是真实凭据。应立即撤销/轮换，并把默认值改为空或只从部署 Secret 注入。该问题与是否采用 AgentScope 无关，但应先于 PoC 处理。

