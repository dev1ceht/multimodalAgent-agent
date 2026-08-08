# multimodalAgent Evaluation

## Delivery domain

- **AlertRecord** means the logical alert for one recipient. It describes the current business status of that alert, not every delivery attempt.
- **DeliveryTask** means durable work that can be claimed and retried. Its idempotency key identifies the logical delivery across retries.
- **NotificationRecord** means one concrete attempt to deliver an alert. A logical alert can have multiple notification records when retries occur.
- **Notification attempt status** describes that attempt only; it can be pending, successful, failed, or unknown when its lease expires before an outcome is observed. It must not replace the logical alert status or the delivery task status.

This context defines the language used to compare language models within the project's retrieval-augmented campus mental-health workflow.

## Language

## Authorization domain

- **UserAccount** is the authentication identity. It is not itself a student record or an authorization scope.
- **StudentProfile** is the student's academic identity, linking a user to a department, major, class, grade, student number, and lifecycle status.
- **StudentProfile sensitive contact fields** are stored as masked/encrypted representations; APIs never return a raw phone number.
- **CounselorAssignment** is an enabled responsibility scope for a counselor. It can target a department, major, class, or grade.
- **ConsentRecord** is an immutable, versioned student consent history. Granting a newer version revokes the active version for the same purpose.
- **Chat access** is student-only and requires active `PRIVACY_NOTICE` and `SENSITIVE_DATA_PROCESSING` consent before model or multimodal processing begins.
- **Profile masking** is normalized at write time and re-applied at read time so legacy clear-text contact values cannot leak through the profile API.
- **Audit reason** is a stable, non-sensitive action code attached to every new audit event; raw request content is never used as the reason.
- **Sensitive report access** is role-plus-scope based: students can view their own reports; counselors need a matching assignment; psychology-center reviewers can view high-risk reports in this phase; system administrators do not gain sensitive access from administration privileges alone.
- **School administrator aggregation** is a separate read model and is not equivalent to raw report access.

## Risk-management domain

- **RiskCase** is the durable human-follow-up case opened from a high-risk student assessment. It snapshots the triggering report's risk level and owns the case lifecycle.
- **Referral** is a directed handoff from a risk case to a care destination such as a counselor, the psychology center, or an external provider. A case may have multiple referrals over time.
- **InterventionRecord** is a factual record of a human contact or care action. It is not a copy of the student's chat transcript and its staff notes are not exposed to students.
- **Case lifecycle** progresses through `OPEN`, `ACKNOWLEDGED`, `REFERRED`, `IN_PROGRESS`, `RESOLVED`, and `CLOSED`; terminal closure cannot be silently overwritten.
- **Case version** is the JPA-managed optimistic-concurrency token for a `RiskCase`; a staff write may include the version observed by the client and receives a conflict when another write has already advanced it.
- **Case SLA deadline** is the configured response deadline attached to a high-risk `RiskCase`; a case is overdue only while it is in an active lifecycle state and its deadline is before the observation time.
- **Referral SLA deadline** is the `Referral.dueAt` deadline. An omitted due date is filled from the configured referral-response duration; an explicitly supplied due date remains part of the operational record.
- **Risk response policy** maps the assessed `RiskLevel` to the human-follow-up and notification actions allowed by this release. `NONE`, `LOW`, and `MEDIUM` remain assessment-only; `HIGH` opens a case and is eligible for staff notification.
- **Notification eligibility** is a policy decision derived from the report risk level, not from a delivery retry status. A delivery failure cannot downgrade or close the human-follow-up case.
- **Overdue escalation** is a one-time system-generated follow-up signal when an active risk case passes its SLA deadline. It creates staff notification work and an audit event, but does not change the case lifecycle or imply that a human has handled the case.
- **Escalation delivery outcome** describes only whether the system delivered the overdue signal. A failed or retrying escalation must leave the case open for human follow-up.
- **CRISIS** is not a current canonical risk level. Introducing it requires a separate safety and workflow decision rather than silently treating it as `HIGH`.
- **Student support status** exposes only whether the student's own case is open and whether it has an active referral; it does not expose staff notes, evidence, or internal routing rationale.

## Operations aggregation domain

- **SchoolOperationsOverview** is a time-windowed operational read model for school administrators. It describes population-level workload and risk signals; it is not a raw report or case view.
- **Overview window** is an explicit half-open UTC interval `[from, to)`, limited to a bounded lookback so dashboard queries remain predictable and comparable.
- **Operational indicators** are counts of active students, risk assessments by level, cases by lifecycle status, overdue active cases, active/overdue referrals, and interventions that occurred in the window.
- **Aggregation privacy boundary** excludes student identifiers, report text, case identifiers, staff notes, referral reasons, and department-level breakdowns from this first read model.

## Persistence domain

- **Schema migration baseline** is the immutable Flyway version-1 marker for databases that predate migration management. Version 0 is the complete empty-database bootstrap; existing non-empty installations baseline at version 1 and apply later changes. The explicit local/test profiles may still use Hibernate schema creation.
- **Production schema policy** is `ddl-auto=validate` with Flyway enabled for the MySQL profile. Hibernate may create or update schemas only in explicitly non-production profiles.

**完整 RAG 链路评测**:
从用户输入开始，覆盖请求是否进入知识增强流程、知识规划与使用、心理安全判断以及最终回答的整体评测。主结果反映真实系统表现，阶段结果用于解释模型差异。
_Avoid_: 纯检索评测、单模型跑分、只测回答质量

**请求路由决策**:
模型结合当前输入和最近上下文输出 `needsRag`、`riskLevel`、置信度与依据；高风险词和多模态中高风险信号只能提高风险等级，不能被模型降级。该决策不再使用 `CHAT/CONSULT/RISK` 单标签承载多个下游行为。
_Avoid_: 意图三分类、用主题词直接推断用户风险、用是否进入RAG决定是否建心理报告

**知识增强需求**:
`needsRag` 表示当前回答是否需要冻结心理支持知识库。纯心理知识问答可以是 `needsRag=true` 且 `riskLevel=NONE`；普通编程或闲聊可以是 `needsRag=false` 且 `riskLevel=NONE`。
_Avoid_: 心理风险、心理评估、用户诊断

**请求风险等级**:
`NONE/LOW/MEDIUM/HIGH` 表示用户或其明确提到的现实当事人的安全处置强度，不是临床诊断。`NONE` 可进入RAG；任何非 `NONE` 风险必须进入RAG；`HIGH` 必须触发安全回答并进入最小必要报告和预警流程。
_Avoid_: 问题主题敏感度、知识文档风险等级、疾病严重程度

**RAG 合格请求**:
按业务定义应进入知识增强流程的心理咨询或风险请求。普通聊天不计入 RAG 质量总分，但其被错误送入知识增强流程的情况需要单独记录。
_Avoid_: 所有聊天请求、测试问题

**阶段诊断**:
对完整链路中的规划、检索、证据复核、心理安全判断和回答生成分别度量，用于定位最终结果差异的来源。
_Avoid_: 总分、端到端结论

**评测金标准**:
用于裁决评测结果正确与否的权威依据。知识事实以项目知识库为准，检索相关性由人工标注，心理安全与回答质量由具备心理咨询背景的人员复核。
_Avoid_: 模型裁判结果、自动评分结果

**模型裁判**:
用于辅助批量比较回答的语言模型评审机制，其结果必须接受抽样人工复核，且不能独立裁决心理安全指标。
_Avoid_: 金标准、心理安全审核员

**构造型评测集**:
在缺少获授权真实对话时，由知识覆盖场景、人工编写的心理支持场景、边界表达和多轮变体组成，并经过人工复核的评测样本集合。它必须与两个模型的微调训练数据去重。
_Avoid_: 微调数据、训练集验证集、未经复核的模型生成题

**候选生产知识库**:
基于可追溯权威来源构建、准备在评测通过后替换现有内置知识的中文知识集合。两个候选模型必须使用完全相同的内容版本接受比较。
_Avoid_: 评测专用语料、模型生成事实、现有英文知识库

**冻结知识库版本**:
一次可复现评测中固定不变的候选生产知识库快照。该快照的内容、切块规则和索引配置在两个模型之间保持一致。
_Avoid_: 在线变化知识库、每模型独立语料

**通用高校心理支持知识库**:
面向高校学生常见心理支持与求助场景的通用中文知识集合，不包含未经核验的具体学校地址、电话或开放时间。校本信息缺失时使用明确占位符。
_Avoid_: 校本知识库、虚构校园服务信息、医疗诊疗指南

**知识模块**:
围绕一种高校心理支持主题组织的独立知识单元，覆盖适用场景、可执行支持、禁止建议、专业求助条件、来源与更新时间。第一版由十个已确认主题组成。
_Avoid_: 检索切块、评测题、网页摘要

**准入来源**:
允许作为候选生产知识依据的可追溯材料，按官方机构、公立医疗或正规高校机构、同行评议研究的顺序分级。缺少中文材料时可以准确转述权威英文原文，但必须保留原始出处和访问日期。
_Avoid_: 商业软文、自媒体、论坛回答、无出处转载

**阶段评测样本**:
以用户查询和人工标注证据为核心、用于分别衡量规划、检索和证据复核的评测记录。它不代表完整用户对话结果。
_Avoid_: 端到端对话、最终回答盲评

**端到端评测对话**:
从用户表达开始并经过完整 RAG 链路得到最终回答的单轮或多轮场景。它用于比较真实系统结果及其用户可感知延迟。
_Avoid_: 单条检索查询、固定证据生成题

**训练泄漏样本**:
与两个候选模型共同使用的微调数据完全相同或语义高度近似、因而不能用于公平泛化比较的评测样本。所有构造型评测样本在入库前都必须经过泄漏检查。
_Avoid_: 合法知识覆盖题、普通主题相似题

**安全硬门槛**:
候选模型必须全部满足、且不能被质量、速度或资源优势抵消的心理安全条件。危险细节、诊断用药、虚构联系方式、后台标签泄露、高风险支持缺失或未发现的安全链路降级均属于门槛失败。
_Avoid_: 安全加权分、安全平均分、可接受安全错误

**端到端任务成功**:
一个 RAG 合格请求正确进入知识增强流程、命中必要证据、正确复核证据、生成覆盖必需事实且没有关键编造的回答，并在系统时限内完成。端到端任务成功率是通过安全硬门槛后的主排名指标。
_Avoid_: 仅检索命中、仅回答可读、单一综合分

**主评测检索器**:
完整 RAG 链路比较中固定使用的 Chroma 检索环境，采用相同冻结知识库和 Top-K 设置。主评测检索器不可用时评测必须中止，不能静默切换到兜底检索。
_Avoid_: 自动回退检索器、混合检索结果、本地兜底基线

**兜底检索基线**:
使用项目本地轻量文本检索单独运行的回归基线，其结果只反映外部向量检索不可用时的最低能力，不与主评测结果合并。
_Avoid_: 主评测结果、Chroma结果

**评测向量模型**:
通过阿里云百炼 OpenAI 兼容 API 调用的 `text-embedding-v4`，固定输出 1024 维向量。一次评测中，查询和知识文档必须使用同一模型配置生成向量。
_Avoid_: Chroma默认向量模型、本地Qwen聊天模型、未固定维度的向量

**知识片段**:
保持标题语境、完整建议或安全条件，并携带可追溯来源信息的最小检索单元。知识片段不能把一组安全步骤或其适用条件从中间拆开。
_Avoid_: 固定长度字符串、无来源文本、网页片段

**知识文档**:
知识模块中的一份可追溯来源材料，包含来源身份、完整正文和内容版本；同一来源的新内容不会覆盖已经发布的知识版本。
_Avoid_: 检索结果、临时文本、无来源片段

**知识版本**:
由一组知识文档及其切块、向量配置共同组成的不可变知识库快照。只有完成构建和索引校验的版本才能成为 ACTIVE 版本，失败版本不能被检索使用。
_Avoid_: 在线修改的知识库、单个知识片段版本、评测样本集

**证据不足样本**:
候选生产知识库无法充分支持安全、完整回答的评测问题。正确行为是识别知识边界并给出受限的通用支持，而不是把相关但不充分的片段当成确定答案。
_Avoid_: 检索失败样本、无关问题、错误题

**候选金标准**:
已经过权威来源核对和技术一致性检查、但尚未由心理专业人员复核的知识或安全评测标注。它可以支持模型技术比较，但不能证明系统已经完成专业心理安全验收。
_Avoid_: 评测金标准、专业验收结果、生产安全认证

**盲化成对评审**:
隐藏候选模型身份并随机交换回答顺序，由独立模型裁判比较两份回答的忠实度、完整性、可执行性和表达质量。其结果需接受人工抽查，且不能独立裁决安全硬门槛。
_Avoid_: 候选模型自评、单答案绝对打分、安全金标准

**同家族外部裁判**:
通过百炼API调用固定快照 `qwen3.7-max-2026-06-08` 执行的盲化成对评审。它与候选模型不共享本地权重，但仍属于Qwen家族，因此必须报告同家族偏差并使用顺序交换与人工抽查控制风险。
_Avoid_: 独立家族裁判、候选模型自评、心理安全审核员

**基准模型标签**:
为候选模型创建的中性Ollama运行身份，仅包含各自正确聊天模板和统一推理参数，不内置模型身份或额外业务提示。基准模型标签与日常运行标签分离。
_Avoid_: 生产模型标签、原始GGUF文件、带模型专属系统提示的标签

**主性能结果**:
当前评测设备上单并发运行完整 RAG 链路得到的用户体验指标，是两个候选模型延迟比较的主要依据。
_Avoid_: 压力结果、模型微基准、单次冷启动

**压力结果**:
在并发二和并发四条件下得到的吞吐、延迟、超时与资源退化结果，用于识别容量边界，不替代主性能结果。
_Avoid_: 主性能结果、单用户体验

**评测追踪**:
仅在评测模式启用、经过脱敏并写入本地评测目录的完整链路内部记录。它用于关联阶段决策、证据、兜底和耗时，不得通过学生端响应暴露。
_Avoid_: 应用普通日志、学生聊天记录、生产遥测

**运行清单**:
描述一次评测所使用模型权重、代码、知识库、切块、向量服务、检索配置、裁判快照、硬件和随机设置的不可变记录。缺少完整运行清单的结果不能进入正式比较。
_Avoid_: 启动日志、环境说明、结果摘要

**评测报告集**:
由逐样本JSONL、分析CSV、文字结论和可下钻HTML共同组成的一次评测交付物。报告必须分别展示安全门槛、端到端成功、阶段质量、延迟和资源结果，不能只提供单一总分。
_Avoid_: 单一排行榜、聊天截图、仅HTML摘要
