# RAG 主评测固定 Elasticsearch 并禁止静默回退

完整 RAG 链路的主评测固定使用 Elasticsearch KNN + BM25 + RRF 和 Top-K=4；
Elasticsearch 不可用时中止本轮评测，本地轻量检索只作为独立兜底基线报告。
这能避免静默回退让两个模型实际使用不同检索器，从而污染模型差异结论。
