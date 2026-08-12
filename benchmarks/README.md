# 当前实现 RAG 评测

本目录评测正在运行的 multimodalAgent 应用，不再要求固定模型、固定 Elasticsearch、固定 Embedding 参数或 `Top-K=4`。评测器通过 `/api/agent/status` 读取并记录当前 provider、模型、生成参数、Embedding 配置及完整检索参数，结果代表当前 `application.yml`、激活 profile 与环境变量共同形成的实际配置。

评测数据仍保留稳定的查询、期望来源和安全标注，以便解释结果：

- `expectedNeedsRag`：该请求是否应该进入当前 RAG 链路。
- `expectedRiskLevel`：当前安全处置等级标注。
- `expectedSources`：来源文档粒度的相关性标注，用于计算 HitRate、MRR 和来源覆盖率。

没有 `expectedSources` 的样本不进入 HitRate/MRR 分母。报告中的 K 来自当前应用的 `retrieval.topK`，不再假定为 4。

## 前置条件

按当前项目配置启动所需依赖。若当前配置使用 Ollama、Elasticsearch 或远程 Embedding，则相应服务必须可用。`DASHSCOPE_API_KEY` 可以来自 `application.yml` 或环境变量，评测脚本不会覆盖或记录密钥。

## 运行

准备数据集和运行清单：

```powershell
python benchmarks\run.py prepare --run-id current-001
```

通过项目脚本启动当前应用：

```powershell
.\scripts\run-benchmark-app.ps1 -RunId current-001 -Label current
```

该脚本只设置 `EVAL_MODE=true`、trace 输出目录和服务端口。模型、生成参数、RAG、Embedding、数据库及 Elasticsearch 配置均由当前应用配置决定。首次执行 `evaluate` 时，非敏感运行配置会写入 `configuration/current.json`；同一标签下配置发生变化时评测会中止，避免混合数据。API Key 不进入快照。

在另一个终端运行 stage 评测：

```powershell
python benchmarks\run.py evaluate `
  --run-id current-001 `
  --label current `
  --suite stage `
  --profile stage-current
```

这里的 `--label` 是结果和 trace 的目录标签，必须与启动脚本的 `-Label` 相同；实际模型由应用配置决定并记录在运行配置快照中。

生成单实现指标汇总：

```powershell
python benchmarks\run.py summarize `
  --run-id current-001 `
  --label current `
  --profile stage-current
```

输出文件位于：

```text
benchmarks/results/current-001/report/stage-current-current-summary.json
```

其中包含当前运行配置、活动知识版本、`ragTopK` 汇总值、`hitRateAtK`、`mrrAtK`、`meanSourceRecallAtK`、路由、安全、生成质量和延迟指标。

如需先做小规模冒烟评测，可给 `evaluate` 添加 `--limit 10 --warmup 1`。完整 stage 套件为 190 条，包含原有 140 条核心场景及新增的跨主题、安全边界、证据不足和路由对照样本；完整 e2e 套件为 80 条，增加了多轮对话和边界请求。新增样本沿用同一套来源标注与训练泄漏检查，原有样本 ID 保持不变。

## 可选回归门禁

当前效果查看不需要回归门禁。如果希望额外应用 `benchmarks/regression-thresholds.json`，可以显式运行：

```powershell
python benchmarks\run.py gate `
  --run-id current-001 `
  --label current `
  --profile stage-current
```

门禁阈值是额外策略，不影响 `evaluate` 和 `summarize` 对当前应用配置的测量。

当前实现评测用于查看此刻效果，不声明可用于正式跨模型比较；运行清单会将 `formalComparisonEligible` 标记为 `false`。
