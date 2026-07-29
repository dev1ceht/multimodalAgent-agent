# 完整RAG链路评测

本目录比较两个本地微调模型在真实Spring Boot RAG链路中的差异。心理安全知识和高风险标签目前是候选金标准，不能代替心理专业人员验收。

## 前置条件

1. 启动固定版本 Chroma 1.5.9：`docker compose up -d chroma`
2. 设置百炼密钥：`$env:DASHSCOPE_API_KEY = "..."`
3. 启动Ollama。
4. 创建两个中性基准标签：

```powershell
.\scripts\create-benchmark-models.ps1
```

## 运行

准备冻结数据和运行清单：

```powershell
python benchmarks\run.py prepare --run-id baseline-001
```

启动Qwen2.5评测应用：

```powershell
.\scripts\run-benchmark-app.ps1 -Model qwen25 -RunId baseline-001
```

在另一终端运行正确性评测：

```powershell
python benchmarks\run.py evaluate --run-id baseline-001 --model qwen25 --suite all
```

停止应用，使用相同方法启动并评测 `qwen35`。随后运行同一代表性子集的并发2和并发4压力档位，例如：

```powershell
python benchmarks\run.py evaluate --run-id baseline-001 --model qwen25 --suite e2e --concurrency 2 --limit 12 --profile e2e-c2
python benchmarks\run.py evaluate --run-id baseline-001 --model qwen25 --suite e2e --concurrency 4 --limit 12 --profile e2e-c4
```

两个模型完成后生成四类报告；加 `--judge` 会调用固定的 `qwen3.7-max-2026-06-08` 进行A/B顺序交换盲评：

```powershell
python benchmarks\run.py compare --run-id baseline-001 --profile e2e-c1 --judge
```

结果保存在 `benchmarks/results/<run-id>/`。真实API Key不会写入运行清单或报告。

每个模型使用独立的 Chroma 集合
`multimodalAgent_eval_<run-id>_<model>`，集合中的中文知识库、切分参数和
`text-embedding-v4` 配置保持一致，避免跨模型残留数据污染。评测还会输出：

- `metrics/<model>/<profile>.json`：吞吐量、GPU/显存快照与 Ollama CPU/GPU 装载信息。
- `report/<profile>-human-review.csv`：确定性抽取 20% 样本的盲化人工复核表。
- `report/<profile>-human-review-key.jsonl`：单独保存 A/B 身份映射，复核前不要交给评审者。
