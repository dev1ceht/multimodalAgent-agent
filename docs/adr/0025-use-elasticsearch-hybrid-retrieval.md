# 使用 Elasticsearch KNN、BM25、RRF 与后置重排

## 状态

Accepted

## 决策

生产知识检索默认使用 Elasticsearch。每个不可变知识版本对应一个独立索引，Chunk
正文写入 `text` 字段用于 BM25，Embedding 写入启用 HNSW 的 `dense_vector` 字段用于
近似 KNN。两个召回列表由 Elasticsearch RRF retriever 融合，候选结果再通过项目现有
`EvidenceReranker` seam 完成确定性后置重排。

知识版本只有在索引创建、全部 Chunk 写入、刷新和文档数量校验完成后才允许切换为
ACTIVE；同时更新 `mindcare-knowledge-active` alias。应用检索仍以数据库中的 ACTIVE
版本为准，alias 用于运维检查和外部只读检索。

`EvidenceRetriever` 保持为上层唯一检索 interface。Agent 查询规划、证据审核、风险
路由、上下文组装和 SSE 输出不感知 Elasticsearch 查询结构。生产模式检索失败时返回
FAILED，不静默回退到本地 baseline。

Chroma 模式暂时保留，仅用于复现历史评测结果；新生产配置默认
`ELASTICSEARCH_REQUIRED`。

## 影响

- BM25 与向量相似度无需直接比较分数，RRF 使用排名完成融合。
- RRF 原始分数按双路召回理论最大值归一化到 `[0,1]`，继续满足统一证据质量策略。
- 修改 Embedding 模型或维度后必须创建新知识版本并重建索引。
- 中文 BM25 目前使用 Elasticsearch `standard` analyzer；如引入专用中文分词器，必须
  通过新索引版本发布并重新运行离线评测。
