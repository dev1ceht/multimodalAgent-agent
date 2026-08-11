# 使用百炼text-embedding-v4生成评测向量

候选生产知识库和 RAG 主评测统一通过阿里云百炼 OpenAI 兼容 API 调用
`text-embedding-v4`，固定 1024 维，并将生成向量显式写入 Elasticsearch。
我们接受外部 API 依赖、费用和重新索引成本，以获得固定的 Qwen3 系列中文向量能力，
并避免向量生成配置不透明导致的不可复现结果。
