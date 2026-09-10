# Agent 能力探针

这里的探针只验证 SAA Agent/MCP 的协议和工具边界，不修改既有 `benchmarks/run.py` 的 RAG
评分语义，也不把 scripted 测试当成真实模型规划能力。

## 运行

先以 `AGENT_MODE=saa` 启动本地应用，再执行：

```powershell
python benchmarks\agent\probe.py --base-url http://127.0.0.1:18080 --grant-consent
```

默认使用本地合成账号 `student / student123`，也可以通过
`AGENT_USERNAME`、`AGENT_PASSWORD` 覆盖。输出只保留 HTTP 状态、MCP 方法、工具名、
`isError` 和受限错误码，不写入 token、原始参数、学生输入或工具原始结果。

`--require-tool-success` 只在已完成本地学生同意后使用；未授权时工具返回
`isError=true` 仍是预期的服务端安全行为。

## 真实模型能力探针

模型探针只在状态端点确认 `executionMode=saa` 且 `realModelEnabled=true` 后运行：

```powershell
python benchmarks\agent\model_probe.py --base-url http://127.0.0.1:18080 --grant-consent --repetitions 3
```

P2 默认读取 `cases.jsonl` 的前 10 类（10 类×3）；P8 使用全部 40 类（40 类×3，含 12 个多步场景），记录每次的公开事件类型、工具事件数量、完成态和延迟；不记录用户原文或回答内容。`toolCallingVerified` 只有在人工/自动审阅该结果并用指定
模型配置显式回填 `AGENT_TOOL_CALLING_VERIFIED=true` 后才应标为 true。
探针覆盖：未认证拒绝、initialize/initialized、tools/list 白名单和 schema、学生本人 tools/call、未知参数拒绝，以及 JSON-RPC `isError` 语义。真实模型准入必须用指定模型单独运行并记录 digest、模板哈希、量化、温度和重复次数；`multi` 只有观察到至少两次完整工具调用才算通过。模型探针会在结果 JSON 中记录 admission；公开 SSE 不暴露原始工具参数时，工具名/参数合法率保持未测量，脚本以非零退出码表示准入未通过。
