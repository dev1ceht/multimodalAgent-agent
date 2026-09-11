# MindCare Spring AI Alibaba 改造执行计划

> 状态：待实施；本文不代表功能已经实现。编写日期：2026-09-10。
> 原始代码基线：`563858e44610bf3187555c16bfee7041dd177c6a`。
> 已确定选型：Spring AI Alibaba（SAA）ReactAgent，Java 17，单 Agent。
> 使用方式：接手模型先读本文和同目录 progress 文档，再从首个未完成阶段实施。

## 1. 目标与交付范围

把当前固定编排的心理关怀应用改造成真实的 Agent：LLM 决定是否调用工具、工具参数及下一步行动；后端执行工具，将结果返回同一循环，直到产生最终回答或达到终止条件。

必须完成：

- SAA ReactAgent 接入真实学生聊天入口，而不只是增加依赖或独立示例。
- 知识检索、长期记忆的自主选择和多轮调用，证据不足时可改写查询。
- 复用多模态感知、风险评估、报告投递及人工干预跟进流程。
- 至少一个标准 MCP 只读业务工具参与模型决策循环，并验证身份隔离。
- SSE 兼容、预算、取消、运行记录、测试、真实模型评测和回滚说明。

本次文档任务只生成计划。后续模型得到实施指令后应完成代码和验证，不重复讨论框架选型。不得把 mock 验证写成真实模型验收。

不包括：AgentScope、Python Agent sidecar、多 Agent Supervisor、任意 shell/SQL工具、任意邮件发送、自动诊疗、自动关闭风险事件、生产部署。保留既有 Python 感知/评测代码。第一版无需额外外层 Graph：固定 Java 业务流程包围 ReactAgent，ReactAgent 自带 Graph 运行时控制模型和工具循环。

## 2. 接手检查与源码定位

路径相对仓库根目录；当前 checkout 为 `D:\project\multimodalAgent`，程序不得硬编码此路径。

1. 检查当前适用 AGENTS.md（文档编写时未发现仓库内同名文件）。
2. 阅读 CONTEXT.md、本文和 `mindcare-saa-progress.md`。
3. 执行 git status，保留已有改动；检查基线以后发生的变化。
4. 阅读以下源码及对应测试。README 的旧行为描述与代码冲突时，以当前代码、测试和明确业务规范为依据，并记录冲突。

下表省略包根的 Java 路径均位于 `src/main/java/com/multimodalAgent/agent/`。

| 文件/目录 | 当前事实与改造用途 |
| --- | --- |
| `pom.xml` | Java 17、Boot 3.3.5、Spring AI 1.0.0；已有 Ollama/OpenAI |
| `service/ChatService.java` | prepare 后调用 response streamer |
| `service/chat/DefaultConversationPreparation.java` | 开会话、加载历史、写用户消息、决策、组 prompt |
| `service/chat/AiConversationAnalysis.java` | RequestRouter → 固定 RAG → 必要评估 |
| `service/chat/ConversationDecision.java` | 风险下限、报告落库、长期记忆召回；HIGH 不召回 |
| `service/chat/DatabaseReportLifecycle.java` | saveReportAndEnqueue |
| `service/chat/DefaultConversationResponseStreamer.java` | SSE 与最终助手消息持久化 |
| `service/ai/AiClient.java`、`AiMessage.java` | 仅文本/role-content，不能完整表示 tool calls |
| `service/ai/OllamaAiClient.java` | 当前请求/响应只映射文本与结构化输出 |
| `service/knowledge/retrieval/EvidenceRetriever.java` | 检索入口，READY/EMPTY/FAILED |
| `service/knowledge/retrieval/RetrievalQuery.java` | 非空查询，topK 1～20 |
| `service/knowledge/EvidenceQualityPolicy.java` | 确定性来源/内容/分数过滤 |
| `service/memory/LongTermMemoryRetriever.java` | 长期记忆召回接口 |
| `service/chat/DatabaseConversationMemory.java` | MySQL 对话、Redis 窗口、USER 异步编译长期记忆 |
| `service/ToolOrchestrationService.java` | 持久任务、租约、执行、重试 |
| `service/ExternalDeliveryTaskExecutor.java` | Excel、告警、超时升级工具分发 |
| `config/RiskCaseSlaProperties.java` | 目前仅 HIGH 可建事件、通知工作人员 |
| `service/mcp/`、`controller/McpController.java` | 简化 JSON-RPC `/mcp` 和已有投递适配 |
| `controller/AgentStatusController.java` | `/api/agent/status` |
| `dto/ChatStreamEvent.java` | meta/token/error/done |
| `src/main/resources/static/app.js` | 前端 SSE 消费；没有 frontend/npm 工程 |
| `src/main/resources/db/migration/` | 当前 V0～V6，新增迁移不得覆盖旧版 |
| `.github/workflows/ci.yml` | Java 测试、MySQL smoke、观测配置检查 |
| `benchmarks/README.md`、`benchmarks/run.py` | 当前链路评测和运行配置快照 |

## 3. 依赖基线及资料

官方 SAA `v1.1.2.2` tag POM 明确给出 Java 17、Spring AI 1.1.2、Boot 3.5.8、Extensions 1.1.2.2。以下是兼容试验起点，不表示这些版本永远最新或无需补丁更新。[固定版本 POM](https://raw.githubusercontent.com/alibaba/spring-ai-alibaba/v1.1.2.2/pom.xml)

| 依赖 | 初始目标 |
| --- | --- |
| Java | 17 |
| Spring Boot parent | 3.5.8，实施时核对同系列补丁兼容性 |
| SAA BOM / Agent Framework | 1.1.2.2 |
| Spring AI BOM | 1.1.2 |
| Extensions BOM | 需要扩展时才引入匹配的 1.1.2.2 |
| 模型 | 延用配置中的 Ollama，不自动切换 DashScope |

官网版本表仍列 1.1.2.0，release 页面已有 1.1.2.2。以发布 tag、实际解析和编译为准，不混用网页示例与不同版本 JAR。[版本表](https://java2ai.com/docs/versions/) · [发布记录](https://github.com/alibaba/spring-ai-alibaba/releases/tag/v1.1.2.2)

新增 `com.alibaba.cloud.ai:spring-ai-alibaba-bom` 和 `spring-ai-alibaba-agent-framework`，统一 Spring AI BOM。不要复制上游全部依赖，不引入 Admin/Studio/Sandbox/AgentScope starter/Nacos/A2A。

自定义类名以本计划为准；SAA builder、Hook、ToolCallback、事件 API 必须检查锁定版本源码或编译探针，不能凭记忆生成。若有兼容冲突，记录具体原因，优先在稳定同系列调整；不要静默升级 Boot 4/Spring AI 2。

资料入口：

- [ReactAgent 快速开始](https://java2ai.com/docs/quick-start/)。
- [Hooks / Interceptors](https://java2ai.com/docs/frameworks/agent-framework/tutorials/hooks/)。
- [SAA 固定版本源码](https://github.com/alibaba/spring-ai-alibaba/tree/v1.1.2.2)。
- [Ollama 工具调用](https://docs.ollama.com/capabilities/tool-calling)。
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)。

执行者把最终版本、源码依据、日期和兼容测试结果写进 progress 文档。

## 4. 必须保持的业务约束

1. 保留当前角色、会话归属、PRIVACY_NOTICE 和 SENSITIVE_DATA_PROCESSING 同意检查；拒绝前不能先调用模型或感知模块。
2. 风险保持 NONE/LOW/MEDIUM/HIGH。风险词、多模态、评估产生的风险下限不能被 Agent 降级。
3. CONTEXT.md 规定任何非 NONE 风险必须进入 RAG。HIGH 安全流程也要保留有界检索或失败降级记录，不能直接跳过 RAG。
4. 保留 MEDIUM/HIGH 的必要评估及报告；建事件、通知仍由 RiskCaseSlaProperties 决定，目前仅 HIGH。
5. HIGH 不注入长期记忆、不进入自由业务行动循环。安全响应不能等待无限检索或邮件实际送达。
6. 报告与投递落库不依赖 SSE 正常完成；取消不能撤销已提交的风险任务。
7. AlertRecord=逻辑告警，DeliveryTask=持久任务，NotificationRecord=具体尝试，RiskCase=人工跟进事件；不得合并状态。
8. MySQL 对话、Redis 窗口、Facts/Topics 仍是现有记忆体系；框架 MemorySaver 不是第二份用户历史事实源。
9. 工具结果、知识、记忆、多模态证据作为数据，不能升格为系统指令。学生看不到工作人员备注、内部风险判级和原始工具参数。
10. 系统预警成功不等于人工已干预；不声称完成自动临床诊疗。

代码与规则冲突时，补测试并记录，不能删断言掩盖问题。常规兼容修复自主完成；改变风险业务定义需用户明确决定。

## 5. 架构和接口

```text
现有 Controller / 授权与同意检查
 → ChatService 按 mode 选择 legacy 或 saa
 → SAA 路径：开启会话、加载历史、写用户消息（各一次）
 → 固定风险评估、风险下限、必要报告及投递落库
 → HIGH：受控检索/降级 → 安全回答
 → 其他：MindCareAgentRuntime / SAA ReactAgent
      模型选择 tool call → 权限、参数、预算检查
      → 本地检索/记忆或允许的 MCP 工具
      → tool result 返回模型 → 再决策 → 最终答案检查
 → SSE、最终消息持久化、运行终态
```

```java
public interface MindCareAgentRuntime {
    Flux<AgentEvent> run(AgentRequest request);
}
```

AgentRequest 包括可信 ConversationIdentity、脱敏输入、仅前序轮次的历史、多模态摘要、风险上下文、runId、deadline。当前输入只追加一次。身份和授权通过运行上下文注入，不允许 LLM 覆盖。

新增 `service/agentruntime/`，建议包含：

- MindCareAgentRuntime、SaaMindCareAgentRuntime：框架适配。
- AgentRequest、AgentEvent、AgentRunContext：项目内部契约。
- AgentToolPolicy、AgentBudgetPolicy、AgentAnswerPolicy：执行与答案约束。
- tools/KnowledgeSearchTool、MemoryRecallTool、SupportStatusTool。
- 在现有 config 包新增 MindCareAgentConfig、MindCareAgentProperties。

保留 AiClient/AiMessage 供路由、评估和 legacy 使用。SAA 内部使用该版本 Spring AI 原生 Message、AssistantMessage、ToolResponseMessage 和 ChatModel，不新造第二套跨供应商消息协议。

ChatService 增加 mode 分支；新增 AgentConversationService 组织 SAA 固定业务步骤。从既有分析/决策提取可复用风险逻辑，保留 legacy 调用者；不能先跑完整旧 preparation，再运行新 Agent，导致重复检索和记忆召回。

每轮仅一个负责人持久化用户消息、助手消息、报告、trace 结束。用测试证明各一次。

## 6. 工具设计

| 工具 | 模型参数 | 实现 |
| --- | --- | --- |
| search_knowledge | query，topK 可选 | EvidenceRetriever + EvidenceQualityPolicy，返回证据ID/来源/版本/片段 |
| recall_memory | query | 服务端 userId/sessionId 构造 LongTermMemoryQuery；HIGH 禁用 |
| get_support_status | 无 | 当前学生本人公开支持状态，无内部备注 |

query 初始上限 1000 字符；topK 默认现配置，上限受服务配置与 20 共同限制。参数类型、缺失、越界必须校验。未知字段（尤其 userId/recipient/url）拒绝，不静默用于权限决定。

统一逻辑结果含 status、data、errorCode、retryable。status 至少 success/empty/failed/denied；不能将检索异常伪装为空。tool call ID 只负责消息配对，不作业务幂等键。

工具注册时白名单筛选，执行时再次检查当前身份、同意和数据范围。跨异步线程显式传播可信上下文，不依赖 ThreadLocal 自动继承。

NONE 请求由模型决定是否检索和如何重查。LOW/MEDIUM 最终回答前必须有本轮检索尝试；模型直接结束则在剩余预算内要求检索，仍未执行则确定性检索或安全降级，标记 policy_enforced。不得把强制补偿计为模型自主成功。HIGH 保留固定受控流程。

证据硬过滤在 Java 完成；最终引用只能指向本轮返回且合格的证据ID。EMPTY 可改写，FAILED 可有界重试；失败时不能声称已有知识依据。

## 7. MCP 实施路线

当前 McpProtocolClient 做简化 HTTP POST initialize/list/call，没有完整互操作测试。现有 /mcp send_alert 是记录告警行为，不等于 SMTP 实际送达。不能把当前实现直接视为标准 SDK 可兼容。

1. 先完成本地 Java 工具闭环，避免把所有检索绕远程。
2. 使用所选 BOM 支持的标准 MCP SDK/Starter，增加隔离标准端点（建议 /agent-mcp，实际映射以框架配置确认），不覆盖旧 /mcp。
3. 把 get_support_status 暴露为标准只读工具，复用授权应用方法。
4. SAA 通过标准客户端发现、白名单过滤、调用该工具，把结果返回同一模型循环。
5. 用受保护的每请求认证上下文传播当前用户；不能共享全局学生身份，不能把 token 放入模型参数。若共享客户端无法安全传播身份，用按请求隔离客户端并及时关闭。
6. 服务端独立检查身份、有效同意与本人范围，不能信任请求体的 userId 或“已授权”标记。
7. 验证实际 initialize/initialized、list、call、isError、断连/超时和两个学生并发隔离；具体协议流程以锁定 SDK 的传输实现为准。

验收必须包含协议通信证据和 Agent 调用轨迹，日志不得包含令牌。HTTP 200 + isError=true 必须按工具失败处理。

本计划不新增模型驱动写入：原自动报告/Excel/告警通过既有持久投递流程完成。request_report_export 和 get_delivery_status 为后续可选扩展，需要另行确定权限、公开字段和幂等规则。禁止通过重新 saveReportAndEnqueue 复制报告来实现工具重试。

## 8. 预算、SSE、记忆与运行状态

以下是拟新增的项目配置，不是 SAA 原生字段：

```yaml
multimodal-agent:
  agent:
    mode: ${AGENT_MODE:saa} # saa | legacy（legacy 仅作显式回滚/对照）
    max-model-calls: ${AGENT_MAX_MODEL_CALLS:6}
    max-tool-calls: ${AGENT_MAX_TOOL_CALLS:8}
    max-identical-tool-calls: 2
    max-argument-repairs: 1
    timeout: ${AGENT_TIMEOUT:60s}
    tool-timeout: ${AGENT_TOOL_TIMEOUT:10s}
    max-tool-result-chars: 6000
    max-total-tool-result-chars: 18000
```

所有限制在执行前检查。模型/工具次数按 run 累计，多工具批次逐个计数；规范化参数后检测重复。不能每次 Hook 重置预算。Agent 60秒为初始调试预算；固定前处理设置独立上限并纳入请求级 deadline。单工具 timeout 取配置与剩余时间最小值，空闲超时不是总时限。

RUNNING 只能幂等进入 COMPLETED/DEGRADED/FAILED/CANCELLED/TIMED_OUT。中断进程遗留 run 标记 interrupted/failed，第一版不自动跨进程恢复/重放 Agent。取消后阻止新调用，释放资源；迟到结果不能覆盖终态。

SSE 保留 meta/token/error/done；可新增 status/tool_start/tool_result，只发送可公开摘要。正常连接至多一个终态；断开不保证 done 送达，但服务端必须终结 run。前端忽略未知事件，不展示模型思考、原始参数、内部风险。

先做非流式闭环探针，再接框架流事件。正式第一版缓存候选最终答案，经引用/策略检查后发送 token（可能单片段）；不要人工逐字拆分并宣称真实 token streaming。legacy 原流式保留。不得无条件额外模型重写通过检查的最终答案。

成功最终答案只保存一次；失败/取消不把部分文本、thought、tool result 当成功助手消息。已提交投递继续执行。

MySQL 仍为对话事实源，Redis 为窗口，Facts/Topics 编译复用。框架 run 内工具消息必须成对；checkpointer key 至少 userId/sessionId/runId，不使用固定 threadId。

同会话一次只允许一个活动 run，推荐第二请求返回冲突；多实例用 Redis/DB 租约，不以单 JVM Map 冒充全局互斥。

新增 agent_run 和 agent_tool_execution 元数据表：runId、用户/会话关联、mode、模型、prompt/schema版本、起止时间、状态、计数、工具名、耗时、稳定错误码；工具表唯一约束 (run_id, tool_call_id)，索引支持本人/会话/时间查询。不要保存原始参数、原文、令牌或思考过程。去重指纹默认运行内计算。

默认执行元数据保留7天、可配置；清理不影响报告或风险事件。当前迁移下一版本可用V7，执行时重新查空号；同步迁移测试/MySQL smoke/CI，不改已应用迁移。

/api/agent/status 增加 executionMode、agentModel、toolCallingVerified、schemaVersion。verified 必须基于指定模型实测记录，不能等于 provider != mock。

## 9. 分阶段实施

详细阶段与验收见下文。每阶段必须有代码、测试与进度证据；P2 模型受阻可继续离线 P3～P7，但不得通过真实模型最终验收。

### P0：建立基线

- 检查 git状态/HEAD、java与mvn版本，读取业务规范和相关测试。
- 按CI命令跑Java测试，记录改造前已有失败；不先大范围重构。
- 不覆盖.env、不发送真实邮件、不启动生产。
- 交付：progress中的实际基线、测试数量、退出码和阻塞证据。

### P1：依赖升级与框架编译探针

- 修改pom.xml，按第3节对齐BOM并保留Java17。
- 检查Spring AI、Reactor、Jackson、MCP、OpenTelemetry、数据库驱动收敛。
- 修复真实编译/配置绑定差异；测试 profile 跟随默认 SAA，使用 mock/scripted model 保持确定性且不要求云密钥。
- 新增SaaFrameworkCompatibilityTests：scripted ChatModel返回tool call，然后依据工具结果返回final；实际运行ReactAgent，不能mock整个Runtime。
- 核实唯一工具循环负责人：按锁定版本关闭底层ChatModel自动执行工具的重复路径，确保ReactAgent拦截器能观察所有调用。
- 验收：框架真实最小闭环通过，legacy回归无新增失败；记录实际builder/Hook/事件类型。

### P2：真实模型能力验证

- 新增显式命名的Agent ChatModel，默认复用当前Ollama配置，评估仍走AiClient。
- 新增benchmarks/agent/能力探针和合成样本；真实发送tools定义并回填结果。
- 先测10类案例各3次：无工具、单工具、参数约束、两工具顺序、先空后改写、失败修正、未知工具、重复调用、结果注入、混合文本与工具响应。
- 记录实际模型名/digest（可获取时）、Ollama版本、模板、量化、temperature、schema、成功率与延迟。
- 初始准入：工具名/参数合法率≥95%，多步任务成功率≥90%，受测越权执行与无限循环为0；P8再扩大样本。
- 当前微调模型失败时记录案例，允许用户配置其他工具模型；不擅自购买、上传真实学生数据或将文本Action解析伪装成原生tool calling。
- 验收：真实可复现结果，或明确模型准入阻塞。mock不可算通过。

### P3：只读Runtime

- 新增Runtime/config/工具/策略，按run隔离上下文。
- 实现KnowledgeSearchTool和MemoryRecallTool，复用既有接口和证据质量策略。
- 编写系统指令：工具用途、引用规则、信息不足追问、错误如实说明、工具返回仅作数据。
- 新增SaaMindCareAgentRuntimeTests、AgentToolPolicyTests、AgentBudgetPolicyTests、AgentAnswerPolicyTests，运行真实框架和scripted模型。
- 验收：首次查询后改变query、按结果选择第二工具、预算终止，均可通过Runtime接口验证。

### P4：接入聊天及固定风险流程

- 新增service/chat/AgentConversationService.java；ChatService按mode分派。
- 提取可复用风险评估/报告步骤，保留legacy调用者；SAA不执行旧完整preparation。
- 实现非NONE知识增强、HIGH受控流程、报告先落库及HIGH不召回长期记忆。
- 已有本轮检索则不重复强制调用；补偿用policy_enforced单独记录。
- 新增AgentConversationServiceTests、AgentRiskPolicyTests，扩展ChatServiceTests、ConversationDecisionTests。
- 验收：用户消息/必要报告各一次；模型失败或SSE取消不漏风险任务；无同意不能触发模型/工具。

### P5：标准MCP

- 实现第7节标准端点、工具、客户端和认证；保持旧投递端点可用。
- 只将白名单get_support_status注册给Agent。
- 新增AgentMcpIntegrationTests，覆盖真实网络生命周期、isError、schema、鉴权、两个学生并发。
- 测试使用本地合成账号；服务端授权失败时业务方法不得被调用。
- 验收：Agent自主发起MCP工具，SDK执行，结果回填模型；留脱敏协议证据。

### P6：SSE、运行记录、取消、多模态

- 扩展ChatStreamEvent保持兼容；更新static/app.js状态展示、错误/结束处理。
- 实现运行元数据、迁移、会话互斥、取消/迟到结果处理，显式传递trace上下文。
- 上下文使用实际MultimodalAnalysis的来源、信号和置信度；mock/local-rule标识，缺失模态不能伪造。
- 附件路径、转写和历史SYSTEM数据不能直接提升为不受控指令；可信模板与证据字段分离。
- 新增AgentStreamingTests、AgentRunPersistenceTests、AgentMultimodalTests、AgentSessionIsolationTests，更新迁移smoke。
- 验收：前端显示公开步骤及最终答案；断开后不再启工具；会话隔离；缺模态/冲突信号处理正确。

### P7：评测与观测

- 扩展AgentStatusController的非敏感配置，记录框架/模型/工具schema/模式及知识版本。
- 复用EvaluationTraceService、OperationalMetrics，新增模型/工具次数、拒绝、重复抑制、预算终止、首状态与首答案延迟、总时长。
- Prometheus标签只用工具名/状态等低基数字段，不用userId/runId。
- 保留旧benchmark样本和评分语义，新增agent suite；旧固定阶段字段不能冒充自主规划指标。
- legacy/saa对照固定模型与知识版本，分别记录policy_enforced和自主工具调用。
- 验收：能区分决策、检索、工具、引用错误；原评测仍可运行。

### P8：全量验证、灰度与交付

- 完成第10节矩阵；跑全量Java测试、Python评测器测试、MySQL新建/升级迁移smoke。
- 隔离本地环境AGENT_MODE=saa，通知使用Mailpit/log；至少40个合成Agent场景每例3次，其中至少10个多步调整行动场景。
- 同配置legacy对照，记录工具成功率、检索质量、风险回归、平均工具数、P50/P95延迟、硬件和冷热启动条件。
- 使用P2准入阈值；全部确定性测试通过，受测越权/串号/重复业务写入/风险漏处理为0，原benchmark阈值不得降低。
- 交付docs/runbooks/mindcare-agent.md、docs/reports/mindcare-agent-acceptance.md，更新README当前架构与局限。
- 默认SAA；runbook说明默认启动检查、显式切回AGENT_MODE=legacy并重启的回滚步骤。生产开启不在本次实施范围。
- 回滚不做数据库down migration，不删除业务任务。已有字段/新表保持向后兼容。
- 验收：他人可以启动、验证、回滚；尚未验证的模型/服务明确标为未通过，简历不得声称已完成。

## 10. 验收矩阵

| ID | 场景 | 必须结果 |
| --- | --- | --- |
| A01 | NONE闲聊 | 可零工具回答，不强制检索 |
| A02 | 知识查询 | 模型选择search并利用结果 |
| A03 | 首次EMPTY | 模型改变query再查，非Java固定两次 |
| A04 | 历史咨询 | recall结果影响后续search |
| A05 | 检索异常 | failed与empty分开，不编造证据 |
| A06 | 无来源/低分 | 硬过滤，不被模型绕过 |
| A07 | 假引用ID | 修正或降级，不直接输出 |
| A08 | 非NONE直接final | 有界强制知识增强/降级，标记policy_enforced |
| A09 | HIGH且模型/MCP失败 | 风险下限、报告投递、安全降级保留 |
| A10 | HIGH回忆请求 | 不执行长期召回 |
| A11 | MEDIUM | 保留报告，不擅自建HIGH事件/通知 |
| A12 | 未知工具/错误参数 | 执行前拒绝，参数修正≤1次 |
| A13 | 注入userId/recipient/url | schema/授权拒绝，无越权 |
| A14 | 结果内恶意指令 | 不扩工具权限、不发邮件/泄露历史 |
| A15 | 重复/大批tools | 全部受统一预算约束 |
| A16 | 空final | 明确失败/降级，不正常完成 |
| A17 | 超时后迟到结果 | 不新调用、不覆盖终态/重复保存 |
| A18 | SSE取消 | run终结，已提交投递不撤销 |
| A19 | 两学生并发 | MCP/记忆/上下文严格隔离 |
| A20 | 同会话并发或重复订阅 | 不重复启动或保存用户消息 |
| A21 | 图片/音频/缺模态 | 与实际感知模式一致 |
| A22 | queued或投递失败 | 不声称已发送/人工已跟进 |
| A23 | legacy | 原测试和SSE继续工作 |
| A24 | MCP isError | 200响应也按失败处理 |
| A25 | 进程中断 | 不自动重放动作，遗留run状态明确 |
| A26 | 无同意/撤回同意 | 阻止后续处理，不依赖旧授权缓存 |

确定性测试使用合成数据和fake后端，不访问真实收件人/学生。真实模型测试独立运行；固定工具顺序的scripted测试只能验证执行框架，不能证明模型会规划。

## 11. 验证命令

在仓库根目录执行；PowerShell的-D参数加引号。当前是静态前端，不执行npm。

```powershell
git status --short
git rev-parse HEAD
java -version
mvn -version
mvn --batch-mode --no-transfer-progress "-DforkCount=0" test
mvn --batch-mode --no-transfer-progress dependency:tree "-DoutputFile=target/saa-dependency-tree.txt"
mvn --batch-mode --no-transfer-progress help:effective-pom "-Doutput=target/saa-effective-pom.xml"
```

新增测试存在后可定向运行，名字以实际代码为准；不设置“未找到测试也通过”：

```powershell
mvn --batch-mode --no-transfer-progress "-Dtest=SaaFrameworkCompatibilityTests,SaaMindCareAgentRuntimeTests,AgentToolPolicyTests,AgentBudgetPolicyTests" test
python -m pytest benchmarks/test_benchmark.py
```

MySQL smoke先读scripts/mysql-migration-smoke.ps1，确认隔离实例而非业务库，扩展迁移断言后运行：

```powershell
.\scripts\mysql-migration-smoke.ps1
```

现有评测命令见benchmarks/README.md。新增agent suite命令实现后写benchmarks/agent/README.md，不假装CLI已存在。

每阶段记录命令、退出码、测试数量/失败、证据路径、模型/模式、未验证项。编译不等于集成成功；单次模型响应不等于多轮工具成功。本次文档编写未运行上述实现测试。

## 12. 交接方式

唯一进度记录为mindcare-saa-progress.md。换模型前更新阶段、文件、测试、具体失败和下一条动作。不要因换模型重复全部已完成步骤，但必须复核当前git diff。

建议技能（以当前执行环境为准）：codebase-design用于Runtime接口与复用；diagnosing-bugs用于依赖/并发/取消故障；code-review-expert用于实现后的授权、事务、状态检查。技能不意味着重新讨论已确定技术路线。本计划不要求多Agent并行实施。

可复制给接手模型：

```text
请实施 docs/plans/mindcare-spring-ai-alibaba-execution-plan.md。
先读适用AGENTS.md、CONTEXT.md和docs/plans/mindcare-saa-progress.md，检查git状态。
技术路线已确定为Spring AI Alibaba ReactAgent，从首个未完成阶段继续。
完成代码、测试和执行记录；保留风险下限、非NONE必须RAG、投递与SSE解耦、用户隔离。
不要只给新方案，不删除用户改动，不用mock冒充真实模型验收。
模型服务/凭据阻塞时继续离线实现和测试，明确留下真实模型验收阻塞。
不部署生产，不发送真实通知，不购买服务。每阶段更新progress，最后报告验证结果与未完成项。
```
