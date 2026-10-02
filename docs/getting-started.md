# 运行与配置

[项目首页](../README.md) · [文档导航](README.md)

所有命令从仓库根目录执行。先复制 `.env.example` 为 `.env`，然后编辑配置；Spring Boot 与 Docker Compose 都会读取它。进程环境变量可能覆盖文件配置，切换模式时需同时检查终端中的变量。

## 本地开发

最小依赖是 JDK 17 和 Maven。默认 `local` profile 使用 H2 文件数据库、内存认证会话、本地 RAG baseline，业务数据写入 `data/`。

无模型演示设置 `AI_PROVIDER=mock`、`AGENT_MODE=legacy`、`LONG_TERM_MEMORY_ENABLED=false`，然后运行：

```bash
mvn spring-boot:run
```

使用真实模型时，配置 Ollama 或兼容 API，再切回 `AGENT_MODE=saa`。H2、本地检索模式无需 Docker；长期记忆默认关闭。

| 配置 | 本地模板值 / 用途 |
| --- | --- |
| `AI_PROVIDER` | `ollama`；也可用 `openai` 或 `mock` |
| `AGENT_MODE` | `saa`；mock 业务演示使用 `legacy` |
| `RAG_RETRIEVAL_MODE` / `USE_QDRANT` | `LOCAL_BASELINE` / `false` |
| `LONG_TERM_MEMORY_ENABLED` | `false`；开启后需配置向量、图谱和 Embedding 服务 |
| `DEMO_ACCOUNTS_ENABLED` | `true`；创建本地演示账号 |
| `MCP_EXCEL_MODE` / `MCP_EMAIL_MODE` | `local` / `log` |

默认应用地址为 <http://localhost:8080>，宿主机管理端点为 <http://localhost:9090/actuator/health>。学生首次使用需在页面完成同意流程。

### Windows：Docker 数据库 + 宿主机应用

需要 Docker Desktop、Ollama 和已导入的 `OLLAMA_MODEL`。填写 `.env` 中的数据库密码、Neo4j 密码与模型配置后运行：

```powershell
.\scripts\run-dev.ps1
```

脚本检查 Ollama 和模型，启动 MySQL、Redis、Qdrant、Neo4j、Mailpit，再以 `mysql` profile 启动应用。它启用演示账号、Redis 会话、本地 Excel 和日志通知。此脚本针对 Ollama 开发；无模型演示直接使用 Maven。

### Linux / macOS：Ollama + 本地 H2

准备模型与 `.env` 后运行：

```bash
bash scripts/run-dev.sh
```

脚本检查并启动 Ollama，确认模型后运行应用。可通过 `OLLAMA_BIN`、`JAVA_HOME`、`MAVEN_BIN` 指定工具路径。自定义模型不存在时，脚本会尝试导入其 Modelfile，因此必须先准备权重。

## 模型配置

### Ollama

使用已有模型时，运行 `ollama list`，将实际名称填写到 `OLLAMA_MODEL`，设置 `AI_PROVIDER=ollama`。模板默认模型是项目自定义模型，并非可直接拉取的公开模型标签。

使用项目 Qwen3.5 微调模型时，自行准备以下文件：

```text
models/qwen35-9b-psychqa-Q4_K_M.gguf
```

然后导入（如修改模型名，需同步 `.env`）：

```bash
ollama create multimodalAgent-qwen3.5-9b-benchmark:latest -f models/Modelfile.qwen35-benchmark
```

权重、微调数据集和生成结果不随 Git 仓库分发。训练与转换步骤见 [Qwen3.5 指南](qwen35-9b-bf16-lora-finetune-guide.md)。工具调用能力需按 [Agent 运行手册](runbooks/mindcare-agent.md)单独验证。

### 兼容 API

在 `.env` 中设置 `AI_PROVIDER=openai`、`OPENAI_BASE_URL`、`OPENAI_API_KEY`、`OPENAI_MODEL`，然后使用 Maven 启动。模型与端点须兼容当前 Spring AI 调用及工具协议。

## Docker Compose

完整模式使用 MySQL、Redis、Qdrant、Neo4j、Kafka、MinIO 和 Mailpit。默认启用 Qdrant 知识检索及 Kafka / MinIO 导入；填写 `DASHSCOPE_API_KEY`、数据库与对象存储密码、JWT 和审计密钥后启动：

```bash
docker compose up --build -d
docker compose ps
```

Ollama 仍运行在宿主机，容器通过 `host.docker.internal:11434` 访问。应用地址为 <http://localhost:8080>，Mailpit 为 <http://localhost:8025>。监控组件按需启动：

```bash
docker compose --profile observability up -d
```

长期记忆沿用 `LONG_TERM_MEMORY_ENABLED`；需要体验时将其设为 `true`，同时确保 Qdrant、Neo4j 和 Embedding 可用。仅启动本地开发依赖可用：

```bash
docker compose up -d mysql redis qdrant neo4j mailpit
```

Compose 默认不创建演示账号，refresh cookie 默认要求 HTTPS。仅在本地 HTTP 演示时向 `.env` 添加：

```dotenv
COMPOSE_DEMO_ACCOUNTS_ENABLED=true
COMPOSE_REFRESH_COOKIE_SECURE=false
```

部署时关闭演示账号，使用随机 `JWT_SECRET`（至少 32 字节）、独立审计密钥和实际服务密码，并保持安全 Cookie。`.env.example` 中的占位符不是部署密钥。

## Excel 与通知

| 工具 | 模式 | 行为 |
| --- | --- | --- |
| Excel | `local` | 写入 `data/multimodalAgent-reports.xlsx` |
| Excel | `mcp` / `http` | 调用 `MCP_EXCEL_URL` 的 MCP 工具 / `/write` |
| 邮件 | `log` | 只记录日志，不发送邮件 |
| 邮件 | `smtp` | 使用 Spring Mail 与 `MAIL_*` 配置 |
| 邮件 | `mcp` / `http` | 调用 `MCP_EMAIL_URL` 的 MCP 工具 / `/send` |

高风险链路包含报告、Excel 和预警，通知尝试与重试状态独立记录。标准 Agent 只读 MCP 工具的使用见 [Agent 手册](runbooks/mindcare-agent.md)。

## 打包与本地文件

标准 Java 构建：

```bash
mvn package
```

构建产物位于 `target/`；运行时仍需提供 `.env` 或同名环境变量。

`scripts/package-release.sh` 和 `scripts/package-split-release.sh` 用于携带本地训练资料的交付，需要额外准备 `data/lora/psychqa.jsonl`，后者还需要模型权重；不是干净克隆后的必需步骤。

本地密钥、数据库、模型权重、日志、构建与评测输出由 `.gitignore` 排除；Ollama Modelfile 保留在版本管理中。历史设计、评测与验收资料见[文档导航](README.md)。
