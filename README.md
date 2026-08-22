# multimodalAgent Agent

multimodalAgent 是一个校园心理健康智能体

- 动态路由 RAG：先识别 `CHAT / CONSULT / RISK`，闲聊不查知识库，咨询与风险消息才进入检索增强。
- SSE 流式输出：`/api/chat/stream` 返回 `text/event-stream`，适合前端做打字机效果。
- 后台心理状态识别：记录情绪标签、情绪分数、风险等级和置信度，但学生端不展示评估结果。
- 数据闭环：咨询/风险消息写入数据库，高风险先写 Excel，再触发邮件或 HTTP MCP 预警。
- Spring AI 模型接入：默认通过 `ollama` 调用项目模型，也可切到 `openai`；`mock` 只作为无模型离线演示。
- 混合知识检索：生产配置使用 Elasticsearch KNN + BM25 双路召回，经 RRF 融合和后置重排；本地开发 profile 默认使用本地 baseline。

默认 Qwen3.5-9B 的 LoRA 微调、合并、GGUF 转换和 Ollama 接入流程见：
[docs/qwen35-9b-bf16-lora-finetune-guide.md](docs/qwen35-9b-bf16-lora-finetune-guide.md)。
Qwen2.5-7B 仍作为对照模型保留，流程见：[docs/qwen25-7b-lora-finetune-guide.md](docs/qwen25-7b-lora-finetune-guide.md)。

## 目录

```text
src/main/java/com/multimodalAgent/agent
├── config                 # 配置、安全、AI/MCP Bean
├── controller             # Chat / Knowledge / Report API
├── domain                 # JPA 实体与枚举
├── dto                    # 请求与响应对象
├── repository             # Spring Data JPA
├── security               # 当前用户与认证查询
└── service
    ├── ai                 # Spring AI 模型适配器、mock 客户端与 Prompt
    ├── knowledge          # 切块、Elasticsearch 混合检索与版本索引发布
    └── mcp                # Excel 与邮件/HTTP 预警工具
```

## 快速启动

运行项目需要 JDK 17、Maven、Docker Desktop 和 Ollama。默认 Web 端口为 `8080`，
管理端点端口为 `9090`，运行模型由 `.env` 中的 `OLLAMA_MODEL` 配置。

### 环境变量配置

项目使用根目录 `.env` 管理本地配置；`.env.example` 只包含可提交的模板和占位符。
首次运行前复制模板，并填写需要的 API Key、JWT 密钥、数据库密码、服务 URL 和模型名：

```powershell
Copy-Item .env.example .env
```

`.env` 已加入 Git 忽略列表，不要将真实密钥提交到仓库。Spring Boot 本地启动和 Docker Compose
都会读取该文件；生产环境请通过部署平台的环境变量或 Secret 注入同名变量。

### 开发环境快速启动：使用 Docker 数据库（推荐）

只要 Docker Desktop、Ollama 已启动且 `.env` 中配置的模型已经导入，执行一条命令：

```powershell
cd D:\project\multimodalAgent
.\scripts\run-dev.ps1
```

如果 PowerShell 执行策略阻止脚本，可改用：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-dev.ps1
```

脚本会自动启动并等待 Docker 中的 MySQL、Redis、Elasticsearch 和 Mailpit，然后使用 `mysql`
profile 启动宿主机上的 Spring Boot。业务数据持久化到 Docker MySQL，会话写入 Docker Redis，
并自动启用演示账号、本地 Excel 和日志邮件模式，无需手工设置环境变量。

如果 `.env` 中配置了 `DASHSCOPE_API_KEY`，可将 `USE_ELASTICSEARCH` 和 `RAG_RETRIEVAL_MODE`
切换为 Elasticsearch 混合检索；否则使用本地 RAG baseline，聊天功能仍可正常使用。
启动后访问 `http://localhost:8080`。

```text
admin / admin123
schooladmin / schooladmin123
student / student123
```

如果只需要完全不依赖 Docker 的轻量模式，仍可直接执行 `mvn spring-boot:run`；该方式使用 H2、
内存会话和本地 RAG baseline。

### Windows：手动启动 Docker 依赖

先启动 Ollama，并确认模型已经存在：

```powershell
& "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" list
```

如果列表中没有 `.env` 中 `OLLAMA_MODEL` 指定的模型，执行：

```powershell
& "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" create `
  $env:OLLAMA_MODEL `
  -f .\models\Modelfile.qwen35-benchmark
```

启动 MySQL、Redis、Elasticsearch 和 Mailpit。Compose 文件会从 `.env` 读取 JWT、MySQL 等配置，
即使本次只启动依赖服务也需要先准备 `.env`：

```powershell
cd D:\project\multimodalAgent
Copy-Item .env.example .env
docker compose up -d mysql redis elasticsearch mailpit
docker compose ps
```

在另一个 PowerShell 窗口启动 Spring Boot：

```powershell
cd D:\project\multimodalAgent
# 修改 .env 中的本地开发变量后直接启动：
mvn spring-boot:run
```

如果暂时没有 Embedding API Key，可改用本地 baseline；MySQL、Redis 和 Ollama 仍照常使用：

```powershell
$env:USE_ELASTICSEARCH = "false"
$env:RAG_RETRIEVAL_MODE = "LOCAL_BASELINE"
mvn spring-boot:run
```

### Linux / macOS：本地 H2 快速启动

脚本会检查并启动 Ollama、确认模型存在，然后使用 H2 和演示账号启动应用：

```bash
cd multimodalAgent
./scripts/run-dev.sh
```

如果 Ollama、JDK 或 Maven 不在 `PATH` 中，可通过 `OLLAMA_BIN`、`JAVA_HOME`、`MAVEN_BIN` 指定路径。

### 完整 Docker 启动

应用和所有依赖都运行在 Docker 时，不需要再运行 Maven。Windows PowerShell 示例：

```powershell
cd D:\project\multimodalAgent
Copy-Item .env.example .env
# 在 .env 中填写 JWT_SECRET、MYSQL_PASSWORD、MYSQL_ROOT_PASSWORD；
# 如果启用 Elasticsearch KNN，再填写 DASHSCOPE_API_KEY。
docker compose up --build -d
docker compose ps
```

Ollama 运行在宿主机时，应用容器会通过 `host.docker.internal:11434` 访问它。

启动完成后访问：

```text
应用：http://localhost:8080
健康检查：http://localhost:9090/actuator/health（宿主机启动应用时）
Mailpit：http://localhost:8025
```

页面左上角会显示当前模型模式；如果本机没有启动 Ollama，聊天接口会提示模型连接失败。
生产默认不会创建演示账号；`scripts/run-dev.sh` 或上面的 Windows 开发配置会显式开启：

```text
admin / admin123
schooladmin / schooladmin123
student / student123
```

认证使用 15 分钟的 Bearer access JWT 和 14 天的轮换 refresh token。浏览器只在内存中保存
access token，refresh token 由 `/api/auth` 路径下的 HttpOnly、SameSite=Strict Cookie 承载；
401 时前端会合并当前页面的并发刷新，并通过 Web Locks 串行化同源标签页后自动重试一次。
生产或 Docker 部署必须提供至少 32 字节的
`JWT_SECRET`，并保持 `REFRESH_COOKIE_SECURE=true`；只有本地纯 HTTP 开发环境可以显式关闭。

## 调用示例

```bash
STUDENT_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"student","password":"student123"}' | jq -r .accessToken)

ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r .accessToken)

curl -N -H "Authorization: Bearer $STUDENT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"我最近很焦虑，晚上总是睡不着"}' \
  http://localhost:8080/api/chat/stream
```

高风险示例会触发报告、Excel 写入和预警：

```bash
curl -N -H "Authorization: Bearer $STUDENT_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"message":"我不想活了，感觉撑不下去了"}' \
  http://localhost:8080/api/chat/stream
```

管理员查看后台报告：

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/admin/reports
```

查看当前是否接入真实大模型：

```bash
curl -H "Authorization: Bearer $STUDENT_TOKEN" http://localhost:8080/api/agent/status
```

管理员追加知识库：

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"source":"sleep-guide","content":"失眠时可先固定起床时间，减少睡前屏幕刺激，必要时联系校心理中心。"}' \
  http://localhost:8080/api/admin/knowledge
```

查看知识版本和索引任务状态：

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/admin/knowledge/status
```

生产检索使用 Elasticsearch BM25 与 HNSW KNN 双路召回，通过 RRF 合并异构排名，再按归一化 RRF 分数与查询词覆盖率进行确定性重排。默认 KNN `k=50`、`num_candidates=200`，RRF `rank_window_size=50`、`rank_constant=60`，最终返回 Top-K=4。可通过 `RAG_KNN_K`、`RAG_KNN_NUM_CANDIDATES`、`RAG_RRF_RANK_WINDOW_SIZE`、`RAG_RRF_RANK_CONSTANT` 及重排权重调整。

评测追踪中的 `ragEvidence` 会为最终证据记录 `E1`、`E2` 等稳定编号，以及知识版本 key、向量 ID 和来源切块位置；这些字段只写入内部评测记录，不返回给学生端。

## 接入 Ollama / LoRA 模型

默认模型配置就是本地 Ollama Qwen3.5-9B 路线，模型名由 `.env` 中的 `OLLAMA_MODEL` 控制。

本地模型由这个 GGUF 权重创建：

```text
models/qwen35-9b-psychqa-Q4_K_M.gguf
```

对应的模型定义文件为 `models/Modelfile.qwen35-benchmark`。Windows 首次导入或重新导入模型时执行：

```powershell
# 先在 .env 中设置 OLLAMA_BENCHMARK_MODEL
& "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" create `
  $env:OLLAMA_BENCHMARK_MODEL `
  -f .\models\Modelfile.qwen35-benchmark
```

Linux/macOS 可执行：

```bash
cd multimodalAgent
./scripts/create-finetuned-model.sh
```

之后直接启动项目：

```bash
cd multimodalAgent
./scripts/run-dev.sh
```

macOS 脚本也会尝试 `/Applications/Ollama.app/Contents/Resources/ollama`；其他系统请把 Ollama 加入 `PATH`，
或通过 `OLLAMA_BIN` 指定可执行文件。

没有本地模型、只想离线演示完整业务流程时，才使用 mock：

```bash
cd multimodalAgent
# 在 .env 中设置 AI_PROVIDER=mock 和 DEMO_ACCOUNTS_ENABLED=true
mvn spring-boot:run
```

也可以不用脚本，手动指定本地模型启动：

```bash
cd multimodalAgent
# 在 .env 中设置 AI_PROVIDER、OLLAMA_BASE_URL 和 OLLAMA_MODEL
mvn spring-boot:run
```

## 打包给别人运行

模型文件较大，建议单独压缩发送：

```text
models/qwen35-9b-psychqa-Q4_K_M.gguf
```

生成不含模型权重的应用发布包：

```bash
cd multimodalAgent
./scripts/package-release.sh
```

脚本会在 `dist/` 下生成 `multimodalAgent-app-时间戳.tar.gz`。发布包包含源码、Dockerfile、docker-compose、脚本、文档、`models/Modelfile.qwen35-benchmark` 和 `data/lora/psychqa.jsonl` 数据集；会排除模型权重、模型 zip、运行数据库、Excel 输出、日志、PDF 文档、`target/`、`.m2/`、`.tools/`、IDE 配置等本机产物。

收到项目的人需要把模型 zip 解压到：

```text
multimodalAgent/models/qwen35-9b-psychqa-Q4_K_M.gguf
```

然后执行：

```bash
cd multimodalAgent
./scripts/create-finetuned-model.sh
./scripts/run-dev.sh
```

如果用 Docker 部署数据库、Redis、Elasticsearch、Mailpit，请先复制并填写 `.env`：

```bash
# 编辑 .env 中的 JWT_SECRET、MySQL 密码和模型配置
docker compose up -d mysql redis elasticsearch mailpit
./scripts/create-finetuned-model.sh
./scripts/run-dev.sh
```

如果不是 macOS，或 Ollama/JDK/Maven 不在默认路径，需要先安装 Ollama、JDK 17、Maven，并按实际路径设置 `OLLAMA_BIN`、`JAVA_HOME`、`MAVEN_BIN`。

## 接入 OpenAI

```bash
cd multimodalAgent
# 在 .env 中设置 AI_PROVIDER=openai、OPENAI_API_KEY 和 OPENAI_MODEL
mvn spring-boot:run
```

## 使用 MySQL、Elasticsearch、SMTP

启动依赖：

```bash
# 在 .env 中设置 COMPOSE_SPRING_PROFILES_ACTIVE=mysql、数据库密码，
# 以及需要启用的 Elasticsearch / SMTP 配置
docker compose up -d mysql redis elasticsearch mailpit
```

使用 MySQL profile：

```bash
# 在 .env 中设置 AI、JWT、Elasticsearch、DashScope 和 SMTP 变量
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

Mailpit 管理页面：`http://localhost:8025`

## MCP 工具模式

Excel 工具：

- `MCP_EXCEL_MODE=mcp`：应用配置默认值，通过 MCP 协议调用 `MCP_EXCEL_URL`
- `MCP_EXCEL_MODE=local`：直接写入 `./data/multimodalAgent-reports.xlsx`；本地开发和 Compose 默认使用此模式
- `MCP_EXCEL_MODE=http`：调用 `MCP_EXCEL_URL/write`

邮件工具：

- `MCP_EMAIL_MODE=mcp`：应用配置默认值，通过 MCP 协议调用 `MCP_EMAIL_URL`
- `MCP_EMAIL_MODE=log`：只记录日志；本地开发和 Compose 默认使用此模式
- `MCP_EMAIL_MODE=smtp`：使用 Spring Mail 发送
- `MCP_EMAIL_MODE=http`：调用 `MCP_EMAIL_URL/send`

高风险链路按文档实现为：写入报告 -> 写入 Excel -> Excel 成功后发送预警 -> 更新状态。
