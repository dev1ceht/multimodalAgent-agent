# 校园心理健康监测与干预平台设计文档

日期：2026-07-02
状态：待用户评审
已确认方向：B. 校内试点级平台

## 1. 目标

将当前 `multimodalAgent` 项目从“AI 心理支持助手”升级为“校内试点级校园心理健康监测与干预平台”。

平台需要提供：

- 面向学生的心理支持、心理知识问答和自评入口。
- 基于文本聊天、多模态输入和量表记录的 AI 辅助风险筛查。
- 面向辅导员和心理中心老师的风险复核、跟进、转介、结案工作台。
- 面向学校管理者的风险趋势、处置效率和工作负载看板。
- 面向敏感心理数据的权限、审计、授权同意和数据治理基础能力。

平台必须明确边界：AI 只做筛查、摘要、路由和优先级辅助，不做医学诊断。中风险、高风险和危机风险事件必须进入人工处理流程。

## 2. 当前项目基础

当前项目已经具备一些可复用的基础能力：

- `ChatController` 和 `ChatService` 已支持学生聊天接口与 SSE 流式输出。
- 已支持文本、音频、图片、视频形式的多模态输入。
- 已具备意图分类、心理风险评估、RAG 检索和 Prompt 编排能力。
- `PsychologicalReport` 已能保存 AI 风险分析结果。
- `ReportController` 和 `ReportService` 已提供后台报告查询能力。
- 已有 Excel、邮件、HTTP/MCP 风格的告警工具编排。
- 已包含 H2/MySQL 配置、Redis 短期记忆、Chroma 接入、Docker Compose、Ollama/OpenAI 模型适配。

主要缺口不是 AI 能力，而是校园业务平台能力：

- 缺少学院、专业、班级、年级、辅导员负责范围等组织结构。
- 缺少学生档案，当前用户账号不足以支撑校园管理场景。
- 缺少风险事件闭环，现有报告不能分派、复核、跟进、转介和结案。
- 缺少辅导员、心理中心老师、学校管理员等角色区分。
- 缺少干预记录、转介记录、人工复核记录、授权记录和审计日志。
- 缺少响应时长、待处理数量、误报率、结案率等运营指标。
- 当前认证和部署方式仍偏演示环境。

## 3. 范围

### 3.1 试点版本包含内容

校内试点版需要补充：

- 组织结构和学生档案管理。
- 学生、辅导员、心理中心老师、学校管理员、系统管理员的角色与数据范围控制。
- 从 AI 报告和量表记录生成风险事件。
- 人工复核、跟进、转介、结案的风险处置流程。
- 辅导员工作台和心理中心工作台的接口与页面。
- 校级统计看板。
- 授权同意和审计日志基础能力。
- 面向试点部署的工程加固。

### 3.2 第一版不包含内容

第一版试点不做：

- 医院级诊断或治疗流程。
- 基于 AI 风险结果的自动处分、自动学业处置或行政处置。
- 未经人工复核的家长通知或外部应急联系人通知。
- 跨学校多租户 SaaS 化运营。
- 完整生产合规认证、灾备体系或等保备案自动化。

这些内容可以在试点验证业务价值，并取得学校制度和伦理审批之后再扩展。

## 4. 用户角色

### 4.1 学生

- 使用 AI 聊天和多模态心理支持。
- 填写标准化心理自评量表。
- 查看安全的自助支持资源和校内求助方式。
- 查看授权状态，并在政策允许范围内撤回非必要授权。

### 4.2 辅导员

- 查看自己负责范围内的风险事件。
- 确认和跟进中风险事件。
- 填写跟进记录。
- 将个案转交心理中心。
- 不能浏览无关学生的对话和风险记录。

### 4.3 心理中心老师

- 查看高风险和危机风险事件。
- 对 AI 结论进行人工复核。
- 记录干预方案、转介情况和结案意见。
- 将事件标记为误报、持续观察、已转介或已结案。
- 在个案处理需要时查看敏感对话上下文，但访问必须被审计。

### 4.4 学校管理员

- 查看聚合统计看板。
- 查看风险趋势、处置效率和工作负载。
- 默认不能查看学生原始敏感对话内容。

### 4.5 系统管理员

- 管理账号、角色、系统配置、知识库发布和审计导出。
- 不因系统管理员身份自动获得心理敏感内容访问权限；如需访问，必须单独授权并审计。

## 5. 核心业务流程

### 5.1 风险生成流程

1. 学生发起文本或多模态对话。
2. 现有 AI 流程完成意图识别、RAG 检索和风险评估。
3. 当消息属于 `CONSULT`、`RISK`，或评估结果达到中风险、高风险、危机风险时，系统生成 `PsychologicalReport`。
4. 新增的风险事件服务将符合条件的报告转换为 `risk_events`。
5. 风险事件获得初始风险等级、状态、责任角色、SLA 截止时间和证据摘要。
6. 通知服务根据风险等级触发对应提醒。

### 5.2 人工处置流程

1. 辅导员或心理中心老师打开风险事件。
2. 处理人查看 AI 摘要、证据片段、历史记录和量表记录。
3. 处理人提交人工复核结论。
4. 事件进入分派、升级、转介、持续观察、结案或误报流程。
5. 所有敏感查看和状态流转动作写入审计日志。

### 5.3 状态机

风险事件使用以下状态流转：

```text
PENDING_REVIEW
-> CONFIRMED
-> ASSIGNED
-> IN_PROGRESS
-> REFERRED | OBSERVING | CLOSED

PENDING_REVIEW
-> FALSE_POSITIVE

Any active state
-> ESCALATED
-> IN_PROGRESS | REFERRED | CLOSED
```

### 5.4 风险等级与 SLA

```text
LOW: 默认不生成工单，提供自助支持资源。
MEDIUM: 24 小时内由辅导员确认。
HIGH: 2 小时内由心理中心老师复核。
CRISIS: 立即通知心理中心，并进入学校批准的应急流程。
```

具体 SLA 数值应做成可配置项。

## 6. 领域模型

### 6.1 组织结构表

`departments`：学院或部门。

- `id`
- `name`
- `code`
- `parent_id`
- `enabled`
- `created_at`

`majors`：专业。

- `id`
- `department_id`
- `name`
- `code`
- `enabled`
- `created_at`

`classes`：班级。

- `id`
- `major_id`
- `grade_year`
- `name`
- `code`
- `enabled`
- `created_at`

`student_profiles`：学生档案。

- `id`
- `user_id`
- `student_no`
- `department_id`
- `major_id`
- `class_id`
- `grade_year`
- `gender`
- `phone`
- `emergency_contact_masked`
- `status`
- `created_at`
- `updated_at`

手机号、紧急联系人等敏感字段应根据试点政策进行加密存储或脱敏展示。

`counselor_assignments`：辅导员负责范围。

- `id`
- `counselor_user_id`
- `department_id`
- `major_id`
- `class_id`
- `grade_year`
- `enabled`
- `created_at`

负责范围可以绑定学院、专业、班级或年级。数据权限判断时应匹配最具体的负责范围。

### 6.2 风险与个案表

`risk_events`：风险事件主表。

- `id`
- `public_id`
- `student_user_id`
- `student_profile_id`
- `source_type`
- `source_report_id`
- `source_assessment_id`
- `risk_level`
- `status`
- `ai_emotion`
- `ai_confidence`
- `ai_summary`
- `evidence_excerpt`
- `assigned_to_user_id`
- `assigned_role`
- `sla_deadline_at`
- `confirmed_at`
- `closed_at`
- `created_at`
- `updated_at`

`risk_reviews`：人工复核记录。

- `id`
- `risk_event_id`
- `reviewer_user_id`
- `review_result`
- `human_risk_level`
- `is_false_positive`
- `should_escalate`
- `review_note`
- `created_at`

`intervention_records`：干预和跟进记录。

- `id`
- `risk_event_id`
- `operator_user_id`
- `intervention_type`
- `content`
- `next_action`
- `next_follow_up_at`
- `created_at`

`assessment_records`：标准量表记录。

- `id`
- `student_user_id`
- `scale_type`
- `score`
- `risk_level`
- `answers_json`
- `summary`
- `created_at`

`notification_records`：通知记录。

- `id`
- `risk_event_id`
- `channel`
- `recipient_type`
- `recipient_masked`
- `status`
- `error_message`
- `created_at`

`consent_records`：授权同意记录。

- `id`
- `student_user_id`
- `consent_type`
- `version`
- `status`
- `granted_at`
- `revoked_at`
- `created_at`

`audit_logs`：审计日志。

- `id`
- `actor_user_id`
- `action`
- `resource_type`
- `resource_id`
- `student_user_id`
- `ip_address`
- `user_agent`
- `reason`
- `created_at`

## 7. API 设计

### 7.1 学生接口

- `GET /api/student/profile`
- `PUT /api/student/profile`
- `GET /api/student/consents`
- `POST /api/student/consents`
- `POST /api/student/assessments`
- `GET /api/student/support-resources`

现有聊天接口继续保留：

- `POST /api/chat/stream`
- `POST /api/chat/multimodal/stream`

### 7.2 辅导员接口

- `GET /api/counselor/risk-events`
- `GET /api/counselor/risk-events/{publicId}`
- `POST /api/counselor/risk-events/{publicId}/reviews`
- `POST /api/counselor/risk-events/{publicId}/interventions`
- `POST /api/counselor/risk-events/{publicId}/refer`
- `POST /api/counselor/risk-events/{publicId}/close`

### 7.3 心理中心接口

- `GET /api/psych-center/risk-events`
- `GET /api/psych-center/risk-events/{publicId}`
- `POST /api/psych-center/risk-events/{publicId}/reviews`
- `POST /api/psych-center/risk-events/{publicId}/assign`
- `POST /api/psych-center/risk-events/{publicId}/escalate`
- `POST /api/psych-center/risk-events/{publicId}/interventions`
- `POST /api/psych-center/risk-events/{publicId}/close`

### 7.4 管理端接口

- `GET /api/admin/dashboard/risk-summary`
- `GET /api/admin/dashboard/risk-trend`
- `GET /api/admin/dashboard/organization-distribution`
- `GET /api/admin/dashboard/workload`
- `GET /api/admin/audit-logs`
- `GET /api/admin/organizations`
- `POST /api/admin/organizations`
- `POST /api/admin/counselor-assignments`

现有报告接口可以保留兼容，但新的工作台页面应优先使用 `risk_events`。

## 8. 页面设计

### 8.1 学生端

- AI 聊天和多模态支持。
- 心理自评量表入口。
- 校内支持资源。
- 授权同意和隐私说明页面。

### 8.2 辅导员工作台

- 负责范围内的待处理事件列表。
- 风险事件详情页，展示 AI 摘要、风险历史和有限证据片段。
- 人工复核表单。
- 跟进记录表单。
- 转交心理中心操作。
- 后续跟进日程列表。

### 8.3 心理中心工作台

- 高风险和危机风险事件队列。
- 风险事件详情页，支持受审计的敏感上下文查看。
- 人工复核和风险重分类。
- 干预记录、转介记录和结案记录。
- 误报标记和模型反馈标记。

### 8.4 学校管理看板

- 各风险等级数量。
- 按日或按周的风险趋势。
- 学院、年级、班级分布。
- 待处理和超时工单。
- 平均响应时间。
- 结案率。
- 误报率。

### 8.5 系统管理页面

- 账号与角色管理。
- 组织结构导入和维护。
- 知识库发布。
- 审计日志查询和导出。
- 系统配置。

## 9. 权限与数据范围

权限判断应同时考虑角色和数据范围：

- 学生只能访问自己的记录。
- 辅导员只能访问 `counselor_assignments` 覆盖范围内的学生。
- 心理中心老师可以访问高风险和已转介个案，查看原始敏感上下文时必须审计。
- 学校管理员默认只能查看聚合指标。
- 系统管理员负责配置和账号管理；敏感内容访问仍需单独授权和审计。

敏感接口必须满足：

- 用户已登录。
- 角色权限匹配。
- 数据范围匹配。
- 成功访问前后写入审计日志。

## 10. 隐私与合规设计

平台会处理心理健康相关敏感数据。试点版本至少需要实现：

- 非应急心理监测前提供明确隐私说明，并保存授权同意记录。
- 在学校制度要求时，对敏感个人信息处理取得单独同意。
- 最小化采集，除非确有必要，不采集紧急联系人或原始媒体文件。
- 手机号、紧急联系人等字段脱敏展示。
- 高敏字段加密存储或使用严格访问控制。
- 对敏感查看、导出、复核决策和状态流转写入审计日志。
- 聊天记录、上传媒体、报告、风险事件、审计日志均需要可配置保留期限。
- 学生端不展示 AI 生成的医学诊断标签。
- 高影响干预必须经过人工复核。

应急处置流程必须由学校制度定义。系统可以触发紧急提醒，但正式处置流程需要学校批准。

## 11. 异常处理

### 11.1 AI 和模型失败

- 模型流式输出失败时，保留现有 SSE 错误返回体验。
- 报告已保存但风险事件生成失败时，记录可重试系统错误，并在管理端暴露。
- AI 置信度较低时，默认进入人工复核，而不是自动关闭。

### 11.2 通知失败

- 所有通知尝试写入 `notification_records`。
- 高风险和危机风险通知失败时，按配置重试。
- 心理中心工作台展示通知失败状态。

### 11.3 权限失败

- 角色或数据范围不匹配时返回 403。
- 对敏感资源的拒绝访问尝试也应写入审计日志。

### 11.4 流程冲突

- 风险事件状态流转使用乐观锁或 `updated_at` 校验。
- 不合法状态流转返回明确业务错误。

## 12. 测试方案

### 12.1 单元测试

- 从 `PsychologicalReport` 创建风险事件。
- 风险事件状态流转。
- 辅导员数据范围计算。
- 各角色权限判断。
- SLA 截止时间计算。
- 审计日志创建。

### 12.2 集成测试

- 学生高风险聊天能够创建报告和风险事件。
- 辅导员可以访问负责班级事件，不能访问无关事件。
- 心理中心老师可以复核高风险事件。
- 管理看板只聚合非敏感指标。
- 通知失败能够被持久化并展示。

### 12.3 端到端冒烟测试

- 学生发送高风险消息。
- 系统创建报告和风险事件。
- 心理中心老师复核并确认。
- 处理人添加干预记录。
- 事件结案。
- 看板指标更新。
- 审计日志记录敏感查看和处置动作。

## 13. 部署与运维

试点部署建议：

- 使用 MySQL 或 PostgreSQL 作为主数据库。
- 将 `spring.jpa.hibernate.ddl-auto=update` 替换为 Flyway 或 Liquibase 数据库迁移。
- 保留 Redis 用于短期记忆和必要的工作台缓存。
- 保留 Chroma 用于知识库检索。
- 将演示用 Basic Auth 替换为 JWT、OAuth2 或学校统一身份认证。
- 生产配置中移除默认演示账号。
- 密钥放入环境变量或部署 Secret。
- 将 Docker Compose 拆分为本地开发和试点部署配置。
- 增加结构化日志和 Actuator 健康检查。
- 如持久化上传媒体，需要增加数据库和媒体文件备份策略。

## 14. 实施阶段

### 14.1 第一阶段：平台数据基础

- 新增组织结构实体和学生档案。
- 新增辅导员、心理中心老师等角色常量。
- 新增辅导员负责范围模型。
- 新增授权同意和审计日志基础模型。

### 14.2 第二阶段：风险事件流程

- 新增 `risk_events`、`risk_reviews`、`intervention_records`。
- 从现有报告生成风险事件。
- 新增状态流转服务。
- 新增 SLA 计算。
- 新增通知记录。

### 14.3 第三阶段：工作台接口

- 新增辅导员事件队列和详情接口。
- 新增心理中心事件队列和详情接口。
- 新增复核、干预、转介、升级、结案接口。
- 新增数据范围控制和审计日志。

### 14.4 第四阶段：看板与页面

- 将当前静态 UI 升级为按角色展示的工作空间。
- 新增辅导员队列、心理中心队列和管理看板。
- 保持学生端聊天体验简单、支持性强。

### 14.5 第五阶段：工程加固

- 增加数据库迁移。
- 为试点配置替换演示认证方式。
- 增加流程、权限、看板测试。
- 增加部署配置和运维文档。

## 15. 成功标准

试点平台达到以下标准即可认为第一阶段落地成功：

- 学生交互可以创建中风险或高风险事件。
- 辅导员或心理中心老师可以复核、跟进、转介和结案事件。
- 未授权用户无法查看无关学生事件。
- 敏感查看和流程动作均有审计记录。
- 看板能展示待处理数量、风险分布、响应状态和结案指标。
- 系统文档明确说明 AI 是筛查辅助，不是诊断。
- 平台可以在试点环境中使用真实数据库和配置好的通知渠道运行。

## 16. 展示与论文定位

推荐项目标题：

基于多模态大模型与 Agentic RAG 的校园心理健康风险监测与干预平台

推荐包装口径：

- 这不是单纯聊天机器人，而是 AI 辅助的校园心理健康工作流平台。
- 技术贡献是将多模态输入、动态路由 RAG、风险筛查、人工复核和干预闭环整合起来。
- 工程贡献是把 AI 输出转化为可审计、可授权、可分派、可跟进的校园业务流程。
- 安全贡献是让 AI 保持辅助角色，对敏感行动保留人工复核机制。

## 17. 政策参考

后续实现隐私、授权和数据治理时，应参考：

- 中华人民共和国个人信息保护法: https://flk.npc.gov.cn/detail2.html?ZmY4MDgxODE3YjY0NzJhMzAxN2I2NTZjYzIwNDAwNDQ=
- 中华人民共和国数据安全法: https://flk.npc.gov.cn/detail2.html?ZmY4MDgxODE3OWY1ZTA4MDAxNzlmODg1YzdlNzAzOTI=
