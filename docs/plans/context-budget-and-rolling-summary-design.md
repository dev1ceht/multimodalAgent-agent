# 上下文预算与滚动摘要实施方案

日期：2026-09-19。状态：P1–P3 已落地；P4 的确定性长对话验收、故障恢复测试、删除联动、低基数指标、告警和回滚手册已完成。真实部署模型的语义质量与 provider usage 校准仍是上线门槛。

## 1. 决策与范围

采用“按模型计算 Token 预算 + 会话滚动摘要 + 近期消息原文 + 按需长期记忆召回”。摘要异步生产，聊天请求不等待摘要模型。每次真正调用模型前执行最终预算检查。

首期复用 MySQL、Redis、现有模型调用适配器及数据库任务轮询模式，不引入 Kafka、不修改长期 Facts/Topics 的写入与检索算法。保留原始聊天记录。摘要只服务当前会话，不自动写入跨会话长期记忆。

## 2. 已核实的现状与接入位置

| 现有代码 | 现状 | 改造点 |
|---|---|---|
| DatabaseConversationMemory / ShortTermMemoryService | historyLimit×2 条消息；Redis 默认 24h；MySQL 回源 | 保留旧模式；新模式读取带消息 ID 的会话快照 |
| AgentConversationService.prepare | 获取历史后写入当前消息，再执行路由、评估 | 固定本轮历史快照；为路由、评估和回答分别组装上下文 |
| SaaMindCareAgentRuntime.BudgetedChatModel.call | 控制次数与截止时间，没有输入 Token 控制 | 每一次 delegate.call 前检查完整 Prompt，包括工具定义和工具往返 |
| DefaultConversationPreparation / ConversationPromptBuilder | legacy 路由与最终回答装配 | 接入相同预算模块，避免只覆盖 SAA |
| JsonAgentToolCallback | 结果过长替换成错误；字符总预算 | 保留硬限制；工具内部先对结构化结果按条目减量 |
| ChatMessageRepository | 主要按 createdAt 排序 | 新增按会话消息 ID 水位分页，建立 (session_id,id) 索引 |

不能仅对 recentHistory 返回的 20 条消息做摘要：已退出窗口的信息必须从 MySQL 按水位读取。Redis 目前的消息结构没有 ID，不能直接作为摘要覆盖范围的可信来源。

## 3. 必须满足的不变量

1. 当前用户输入只出现一次；摘要只覆盖本轮历史快照内已提交的旧消息。
2. 每次模型请求：输入 Token + 输出预留 + 安全余量 <= 该模型的有效上下文容量。
3. 系统指令、工具定义、当前输入和受控风险流程不能被静默截断。若仅这些必需内容就超限，返回明确的输入过长/上下文容量错误。
4. 摘要和检索结果作为不可信数据注入，不能转换成新的 system 指令。助手建议不能被摘要成用户事实。
5. tool_call 与相应 tool_result 成组保留或移除；不得破坏 JSON 或生成悬空调用。
6. 摘要失败不推进覆盖水位；新摘要提交前不覆盖旧摘要；旧任务不得覆盖新版本。
7. 同一请求使用固定摘要版本、历史上界；后台更新不改变执行中的上下文快照。
8. 当前输入与现有外部风险下限保持有效；历史摘要不能降低风险判定。HIGH 流程继续禁止长期记忆召回。

## 4. Token 预算与裁剪规则

为每个实际模型配置 ModelContextProfile：modelKey、contextWindow、maxOutputTokens、safetyMargin、tokenCounterVersion。路由、评估、回答、摘要可能使用不同模型，不能共享一个未经验证的容量。

预算公式：

```text
inputCeiling = contextWindow - maxOutputTokens - safetyMargin
variableBudget = inputCeiling - tokens(system + toolSchemas + currentInput + protocolOverhead)
variableBudget 用于：摘要、近期历史、检索证据、当前 run 的工具往返
```

完整序列化 Prompt 是最终核算对象；上述拆分用于分配预算，不应忽略消息封装开销。输出预留须同步设置到实际请求的输出上限参数。模型容量通过部署配置确认，不凭名称猜测。

计数优先使用与实际模型匹配的 tokenizer。可复用仓库依赖时优先复用，新增库前单独验证中文、表情、工具 Schema 和模型聊天模板。未匹配时采用有余量的估算并标记 estimated；不得宣称绝对精确或依赖“字符数/4”。用服务端返回的 input usage 校准估算，溢出视为需修复的计数配置问题。

建议起始参数（待评测，不是现有配置或某个模型的容量承诺）：

| 参数 | 建议值 | 含义 |
|---|---:|---|
| summaryMaxTokens | 1200 | 摘要含元数据与来源引用的上限 |
| recentTargetTurns | 4 | 优先保留最近 4 个对话组，容量不足允许减少 |
| retrievalMaxTokens | 2500 | 长期记忆和知识证据共享上限，可借用未用预算 |
| compactTriggerRatio | 0.80 | 可变上下文接近可用预算 80% 时提出摘要需求 |
| summaryBatchMaxMessages | 100 | 单次候选消息扫描上限，同时受摘要输入 Token 限制 |
| summaryTimeout | 15s | 后台摘要单次超时 |
| summaryMaxAttempts | 3 | 后台有限重试 |

分配顺序：先容纳必需内容，再为近期完整对话组分配预算，然后分配摘要及按相关性排序的证据；剩余预算可继续补充历史。最近 4 组是目标而非硬保证，超大历史消息按整组省略，明确记录覆盖缺口。当前用户消息不得靠截断“修复”。

Agent 循环中新增工具结果后，再核算完整 Prompt：先减去低相关证据/冗余条目，再删除最旧非必需历史组；必要时移除早期已完成的完整工具往返组。保留最新工具往返；若必需集合仍超限，结束本轮并给出受控失败，不额外调用摘要模型来延长主链路。

工具返回按结构化条目裁剪，再序列化、计数；保留状态、证据 ID 和来源，不 substring 整段 JSON。JsonAgentToolCallback 的字符限制仍是额外防线。若移除工具证据，回答校验应以最终模型可见证据 ID 集合为准，不能只凭该 run 曾经检索过就允许引用。

## 5. 会话摘要的数据格式

采用受 JSON Schema 约束的结构化摘要，空字段允许省略：

```json
{
  "schemaVersion": "conversation-summary-v1",
  "topics": [{"text": "近期学业安排", "sourceMessageIds": [21]}],
  "userGoals": [{"text": "希望调整复习时间", "sourceMessageIds": [23]}],
  "constraints": [{"text": "晚间需要留出社团活动时间", "sourceMessageIds": [25]}],
  "events": [{"text": "用户描述的事件", "time": "用户原话或未知", "sourceMessageIds": [27]}],
  "attemptedActions": [{"text": "用户明确表示已尝试的方法", "sourceMessageIds": [29]}],
  "assistantSuggestions": [{"text": "助手提出但用户尚未确认执行的建议", "sourceMessageIds": [30]}],
  "openQuestions": [{"text": "仍待用户补充的信息", "sourceMessageIds": [30]}],
  "corrections": []
}
```

摘要输入为“旧摘要 + 其水位之后的一段连续原始消息”，输出为新摘要；不把工具结果和长期召回文本反复写入会话摘要。历史 SYSTEM 多模态消息只作为后端分析数据处理，并标明来源，不能作为用户亲口陈述。

服务端校验 Schema、长度、来源 ID 范围、来源用户/会话和角色。旧摘要沿用的引用允许保留，但必须属于旧版已验证来源集合；新增引用必须来自本批消息。来源引用帮助追踪，不能证明语义无误，仍需质量评测。

修正处理：用户明确纠正的信息更新为当前状态，并保留必要的时间/修正关系；无法判断时记录冲突，不自行选真值。不增加诊断、人格推断或未经用户确认的执行结果。不让摘要生成模型决定业务风险等级。

摘要超长或非法时本次任务失败，保留旧版本，有限重试。首期不允许无上限递归摘要；后续发现累计失真时，可增加从原文分块重建流程，不纳入首期。

## 6. 存储、任务和并发

新增迁移文件，版本号实施时按最新 Flyway 迁移递增，不改旧迁移。

### conversation_context_summaries：每会话当前有效快照

| 字段 | 用途 |
|---|---|
| session_id PK, user_id | 身份范围与唯一性 |
| version BIGINT | CAS 乐观更新版本，初始 0 |
| covered_through_message_id BIGINT | 已覆盖的连续消息前缀，初始 0 |
| summary_json LONGTEXT | 结构化摘要 |
| token_count, token_counter_version | 计数与失效判断 |
| schema_version, model_key, updated_at | 来源、升级与审计 |

### conversation_context_jobs：每会话一行合并需求的持久任务

| 字段 | 用途 |
|---|---|
| session_id PK, user_id | 合并同会话请求，限制队列膨胀 |
| desired_through_message_id | 已提交且可压缩的最大目标水位，只增不减 |
| status, attempts, next_attempt_at | PENDING / PROCESSING / RETRY_WAIT / IDLE / FAILED |
| lease_token, lease_until | 多实例领取和宕机恢复 |
| claimed_base_version, claimed_target_id | 本次固定输入版本及目标范围 |
| last_error_code, updated_at | 运维诊断，不保存敏感原文日志 |

另为 chat_messages 增加 (session_id,id) 索引。新读取必须校验 user_id，按 id 排序，不仅按可能相同的 createdAt 排序。

并发流程：

1. 主流程在消息事务提交后，以 max 合并 desiredThrough；未提交消息不得成为摘要输入。
2. worker 短事务领取租约并固定 baseVersion、coveredThrough 和 target，提交事务后调用模型。
3. target 只能选在完整旧对话组结束处，保留近期对话。批次受消息数和输入 Token 双限制。
4. 结果校验后，在同一短事务校验租约并执行 version=baseVersion 且 coveredThrough=oldWatermark 的 CAS；成功才更新版本及水位。
5. 同一事务将任务置为 IDLE，若期间 desiredThrough 已提高则置回 PENDING。CAS 失败丢弃旧结果并重新读取，不覆盖新摘要。
6. 超时使用退避重试，租约需覆盖模型超时与提交余量；失败达到上限保留错误等待人工或新需求重置。定时扫描已提交、存在积压的会话，修复“提交成功但未入队”的窗口。

摘要水位要求不会出现低 ID 的迟提交消息被漏掉。SAA 已有会话租约，实施时让 legacy 的会话写入也遵循同一串行化规则，并核查所有聊天写入口；若不能统一，则先增加会话内提交有序序号，禁止直接假定全局自增 ID 等于提交顺序。

第一版摘要直接读 MySQL 一行，避免额外缓存一致性复杂度。Redis 历史缓存升级到独立 v2 key，包含消息 ID/角色/内容及窗口覆盖信息；不完整或不满足快照要求就回源。后续再加入按 session+version 的摘要缓存。

## 7. 模块接口及调用顺序

新增 service/context 包，提供一个小的 ContextManager 门面；计数、数据读取、摘要工作器、分组裁剪在模块内部实现，不向业务层暴露多个协调步骤。以下为设计草案，不假定框架新增了某个 Hook：

```java
ContextSnapshot load(ConversationIdentity identity);
ContextPlan fit(ContextSnapshot snapshot, ModelInvocation invocation);
void requestCompaction(ConversationIdentity identity, long committedThroughId);
```

- load：获取已验证身份下固定的历史上界、摘要版本、带 ID 的近期消息；有界分页，不整表加载。
- fit：针对完整待发送请求及其模型 profile，返回可发送消息、可见证据集合、计数和缺口元数据，或者明确的 TooLarge；不调用模型、不写摘要。
- requestCompaction：事务提交后合并后台需求，失败不阻断已完成的聊天；由持久扫描修复遗漏。

ContextSnapshot 不跨请求复用；摘要提交后新请求才看见。ModelInvocation 应包含用途（ROUTING / ASSESSMENT / ANSWER）、真实系统提示词、模型配置、工具定义、当前输入和执行中工具往返。SAA Message 与现有 AiMessage 的转换在适配器中完成。

SAA 主链路：load 固定旧历史 → 写入当前 USER / 多模态消息 → 路由和评估分别 fit → 构造 AgentRequest（带 contextSnapshot）→ 每次 BudgetedChatModel.call 对实际 Prompt 再 fit → 执行模型/工具 → 成功答案落库 → 提交后提出压缩需求。

legacy 主链路：同样固定快照；路由/评估分别 fit，ConversationPromptBuilder 生成最终 Prompt 后再 fit；流式输出前完成检查。AiClient 的入口覆盖 complete、completeJson、stream，不能只限制最终回答；实现时避免被摘要 worker 的调用递归触发会话摘要。

用途策略：路由和评估保留当前输入与近期原文，可使用同一结构化摘要补充历史，但摘要不能改变角色/来源；固定的外部风险下限依然独立计算。HIGH 分支不等待摘要更新，不进入自由记忆召回。

异步摘要尚未覆盖某段历史时，选择近期原文和旧摘要；任何中间缺口写入 ContextPlan 与指标，必要时在模型数据区标注“部分历史未包含”。不得把水位跳过缺口，也不得声称摘要覆盖了全部历史。

## 8. 失败、迁移与开关

| 情况 | 处理 |
|---|---|
| Redis 失败 | MySQL 有界回源 |
| 摘要超时、格式错误、无效引用 | 保留旧摘要，退避重试，当前对话继续 |
| 摘要落后 | 保留近期原文、记录缺口；后台逐批追赶 |
| MySQL 摘要读取失败 | 能读到历史则仅按预算裁剪；历史也不可用则受控失败 |
| 当前输入及固定开销超预算 | 请求模型前报明确错误，不截断当前输入 |
| 工具 JSON 过大 | 按条目减量，失败则返回现有错误信封 |
| 摘要并发冲突/租约失效 | 丢弃结果，不推进版本与水位 |
| 模型更换 | 重新计算 Token；摘要内容可兼容时保留，Schema 不兼容则重建 |

配置模式：window（现状）、budget（只做 Token 裁剪）、summary（预算+摘要）。先 shadow 记录预算不改变请求，再按稳定的会话哈希灰度；同一会话保持一致。关闭摘要可回到 budget，不能因关闭摘要而关闭输入硬检查。window 仅用于完整回滚，明确恢复其旧有容量风险。

历史会话按需建摘要，不全库立即回填。单批中出现过大原始消息时，worker 按 Token 切片，保留原消息引用和分片进度，全部分片处理成功才越过该消息水位；无法可靠处理则暂停该会话压缩并告警，不静默跳过。后台配置独立并发与调用速率，避免影响在线模型配额。

会话删除时联动删除摘要与任务，禁止后台旧任务重新创建已删除会话的摘要。日志记录 ID、长度、耗时、错误码，不记录摘要正文。

## 9. 测试与验收

通过 ContextManager 的接口做主要行为测试；数据库并发和实际框架协议保留集成测试，不用大量只镜像私有算法的测试。

| 类别 | 必须覆盖 |
|---|---|
| 预算 | 中英混合、超长输入、Schema 开销、不同模型容量、输出预留、历史整组淘汰 |
| Agent 循环 | 首次及每次工具后模型调用都受限；工具调用配对；裁剪后引用 ID 有效 |
| 摘要内容 | 早期目标/约束保留、未解决问题、用户纠正、助手建议未被写成用户行动 |
| 水位 | 当前消息不重复、同时间戳排序、连续范围、分片恢复、旧任务 CAS 失败 |
| 隔离与权限 | 跨用户/会话引用拒绝、摘要提示注入、高风险流程不等待摘要/不召回长期记忆 |
| 恢复 | 缓存失效、worker 崩溃、超时、格式错、任务提交遗漏修复、会话删除 |
| 路径 | SAA 与 legacy；路由、评估、最终回答三种用途 |

构造至少 50 条 30–100 轮的合成长对话，包含早期约束、后续纠正、工具密集和长单消息；固定模型、参数与问题，对比 window / budget / summary。不要使用未经处理的真实敏感对话。

建议发布门槛（目标，尚无实测结果）：协议/越权/水位测试全部通过；测试请求不出现上下文超限；标注关键约束保留率 >=95%；无用户事实与助手建议混淆的严重错误；摘要主张可追溯。相对 window 的质量、Token、首字延迟和后台成本必须一起报告，不能预设摘要一定降低 Token。

指标：每次调用 estimated/actual inputTokens、contextMode、summaryVersion、coveredThrough、omittedMessageCount、coverageGap、summaryLagMessages、compactionLatency、failureRate、CASConflictCount、workerQueueDepth、toolEvidenceDropped、上下文超限次数。标签不使用 userId/sessionId 等高基数字段；单次追踪可以保存受控 ID。

## 10. 实施顺序与交付物

1. **P0 预算探针**：确认实际模型容量与计数方式；对 SAA 实际 Prompt、legacy 三种调用做 shadow 计数；验证工具 Schema 被计入。
2. **P1 预算裁剪**：ContextManager.fit、两种消息适配器及工具分组；接入所有模型出口；先交付 budget 模式及降级行为。
3. **P2 持久摘要**：消息水位查询、会话写入串行化、两张表与索引、worker/租约/CAS、结构化校验；摘要先后台运行不注入，观察质量与积压。
4. **P3 注入与灰度**：ContextSnapshot、按需初始化、摘要数据区、缺口指标、SAA/legacy 接入；按会话开启 summary。
5. **P4 验收与运行手册**：长对话对比报告、故障恢复测试、删除联动、配额与告警、开关回滚说明。

P0 先做一个小型集成探针验证锁定版本 SAA 在 ChatModel.call 提供的 Prompt 是否包含全部工具定义、工具消息及 options；若缺失，从 builder 注册工具处显式传入计数元数据。不要在未经验证的框架 Hook 名称上安排实施依赖。

首期完成标准：两条聊天路径所有相关模型请求有预算门禁；后台摘要可恢复且不阻塞请求；摘要可追溯、按用户隔离；评测报告达到发布门槛。长期记忆语义去重、事实淘汰、摘要全量重建单列后续工作。
