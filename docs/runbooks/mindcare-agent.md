# MindCare Agent 本地运行手册

本版本默认保持 `AGENT_MODE=legacy`。SAA ReactAgent 仅用于隔离的本地验证，未纳入生产开启范围。

## 前置条件

- JDK 17、Maven 3.9+；
- 本地 Ollama（模型名通过 `AGENT_MODEL` 指定）；
- 若使用 mysql profile，还需要 Docker Desktop、MySQL、Redis、Qdrant、Neo4j 和 Mailpit；
- 真实模型验证只使用合成账号和本机地址，不使用真实学生数据或真实收件人。

## 启用 SAA 模式

在启动 Spring Boot 的同一 PowerShell 进程中设置配置，然后重启应用：

```powershell
$env:AGENT_MODE = "saa"
$env:AGENT_MODEL = "multimodalAgent-qwen3.5-9b-benchmark:latest"
$env:AI_PROVIDER = "ollama"
$env:OLLAMA_BASE_URL = "http://127.0.0.1:11434"
$env:OLLAMA_MODEL = $env:AGENT_MODEL
$env:AGENT_TOOL_CALLING_VERIFIED = "false"
$env:AGENT_METADATA_RETENTION = "7d"
$env:AGENT_METADATA_CLEANUP_INTERVAL_MS = "3600000"
.\mvnw.cmd --batch-mode --no-transfer-progress spring-boot:run
```

仓库没有 Maven Wrapper 时使用 `mvn` 替换 `.\mvnw.cmd`。本地无 Docker 的快速探针可以使用 H2：

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:LOCAL_DB_URL = "jdbc:h2:mem:agent-local;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
$env:LOCAL_DB_USERNAME = "sa"
$env:LOCAL_DB_PASSWORD = ""
$env:LOCAL_DB_DRIVER = "org.h2.Driver"
$env:AUTH_SESSION_STORE = "memory"
$env:DEMO_ACCOUNTS_ENABLED = "true"
```

## 检查

```powershell
Invoke-RestMethod http://127.0.0.1:9090/actuator/health
Invoke-RestMethod http://127.0.0.1:8080/api/agent/status
python benchmarks\agent\probe.py --base-url http://127.0.0.1:8080 --grant-consent --require-tool-success
```

真实模型探针：

```powershell
python benchmarks\agent\model_probe.py --base-url http://127.0.0.1:8080 --grant-consent --repetitions 3
```

探针输出只保存事件类型、工具事件计数、状态、延迟及可复现模型元数据；模型摘要中的
`toolCallingVerified` 仍须按实际准入结果显式配置，不能由 provider 名称推断。
模型探针的准入字段不可测或低于门槛时会返回非零退出码；结果文件仍会落盘，便于审阅后再决定是否调整模型。

## 回滚

停止并重启应用前切回默认模式：

```powershell
$env:AGENT_MODE = "legacy"
.\mvnw.cmd --batch-mode --no-transfer-progress spring-boot:run
```

不要执行数据库 down migration，不删除 `agent_runs`、风险事件或已提交的投递任务。SAA 路径
失败时可以先回到 legacy；新运行元数据表向后兼容，旧聊天路径继续使用原有事实源。

## 当前限制

- 首版会话互斥是单 JVM 租约；多实例部署前需替换为 Redis/数据库租约；
- `get_support_status` 是标准 MCP 只读工具，服务端仍独立验证学生身份、同意和本人范围；
- 高风险路径不注入长期记忆、不进入自由业务行动循环；风险报告/投递与 SSE 解耦；
- 真实 Qwen3.5-9B Q4_K_M 探针已验证基础工具链，但当前准入结果未达到计划中的多步成功率阈值，
  因此 `AGENT_TOOL_CALLING_VERIFIED` 保持 `false`。
