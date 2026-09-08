# 使用 Qdrant 与 Neo4j 构建 Facts/Topics 长期记忆

## 状态

已接受。取代 ADR-0003、ADR-0007 和 ADR-0025 中依赖 Elasticsearch 的部分；不可变知识版本、失败显式化、父章节回填和证据门禁继续有效。

## 背景

短期会话窗口无法支持跨会话事实追踪。把所有历史消息直接向量化会产生事实碎片、重复和噪声，也无法表达“因为”“先于”“反驳”等关系。静态知识库原先依赖 Elasticsearch，与长期记忆所需的图扩展和时序补全不是同一种生命周期。

## 决策

静态知识和长期记忆都使用 Qdrant 作为可重建向量投影，删除 Elasticsearch 运行依赖。静态知识仍按不可变 `KnowledgeVersion` 创建独立 collection，只有完整写入且计数一致的版本才可激活。

用户消息保存后，以 `source_message_id` 为幂等键创建异步任务。编译器只抽取用户明确陈述且未来仍有帮助的原子事实，并生成 Topic 聚合。关系词汇封闭为八类：

1. `CAUSES`
2. `TEMPORAL_BEFORE`
3. `TEMPORAL_AFTER`
4. `SUPPORTS`
5. `CONTRADICTS`
6. `ELABORATES`
7. `SIMILAR_TO`
8. `CO_OCCURS`

MySQL 保存任务、Facts、Topics、成员关系和关系边的规范副本，以便重试、审计和重建投影。Qdrant 的 `memory-facts`、`memory-topics` collection 保存向量及 `user_id` 过滤字段。Neo4j 保存 `Fact`、`Topic`、`BELONGS_TO` 和带 `type` 的 `MEMORY_RELATION`。所有检索与图查询必须按 `user_id` 隔离。

长期记忆召回按以下顺序融合：

1. Qdrant 召回 Fact 和 Topic 向量种子；
2. Topic 成员关系聚合相关事实，降低碎片噪声；
3. Neo4j 从 Fact 种子做一到三跳扩展，用于因果链和多跳问答；
4. 从规范事实表补齐同一会话时间轴中的相邻事实；
5. 以受限加权分数合并、去重并截断到预算。

长期记忆作为不可信、可能过时的运行时上下文注入回答提示。高风险响应不注入长期记忆，继续走固定安全处置路径。外部投影不可用时返回 `DEGRADED`，不伪装为成功；对话本身可以继续。

## 后果

- 支持跨轮事实跟踪、多跳问答和因果链推理。
- Topic 层承担语义聚合，Fact 层保持可追溯的原子性。
- Qdrant/Neo4j 都是可由规范表重建的投影，异步任务可重试。
- 运行环境新增 Qdrant、Neo4j 及其持久卷与凭据管理。
- 当前 Qdrant 静态知识检索以稠密向量为主；如以后增加 sparse vector，需以新 ADR 定义融合与评测口径。
