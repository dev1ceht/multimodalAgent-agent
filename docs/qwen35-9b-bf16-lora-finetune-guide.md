# Qwen3.5-9B BF16 LoRA 微调与项目接入指南

本文记录在单张 NVIDIA A10G 24GB 云服务器上，使用 `ms-swift` 对 `Qwen/Qwen3.5-9B` 进行 BF16 LoRA 微调，并完成 Adapter 验收、LoRA 合并、GGUF 转换、Q4_K_M 量化、Ollama 导入和 `multimodalAgent` 项目接入的完整流程。

本文对应当前已验证的核心配置：

```text
GPU                      NVIDIA A10G 24GB
CPU                      14 核
内存                     40GB
Python                   3.12
PyTorch                  2.9.1+cu129
CUDA                     12.9
基础模型                 Qwen/Qwen3.5-9B
训练方式                 BF16 LoRA（不是 QLoRA）
max_length               512
micro batch size         1
gradient accumulation    16
LoRA rank / alpha        8 / 32
```

> 训练阶段可以在 60GB 系统盘上勉强完成，但“基础模型缓存 + 合并模型 + BF16 GGUF + Q4 GGUF”会占用更多临时空间。完整部署流程建议准备至少 100GB 磁盘，150GB 更稳妥。

## 1. 与旧项目 Guide 的区别

旧文档 `docs/qwen25-7b-lora-finetune-guide.md` 使用：

```text
Qwen2.5-7B-Instruct + BF16 LoRA
```

本文使用：

```text
Qwen3.5-9B + BF16 LoRA
```

本文命令中没有以下量化参数，因此不是 QLoRA：

```text
--quant_method bnb
--quant_bits 4
--bnb_4bit_quant_type nf4
--bnb_4bit_use_double_quant true
```

`--torch_dtype bfloat16` 表示基础模型以 BF16 权重加载和计算。

## 2. 登录服务器

```bash
ssh -p 43569 root@14de396cb27349a0a89c5390e6ba92a3.region1.waas.aigate.cc
```

不要在聊天、日志或截图中发送服务器密码。

## 3. 检查硬件和磁盘

```bash
nvidia-smi
free -h
df -h /root
lsblk
```

训练开始前，`/root` 最好至少有 30GB 可用空间。合并和转换前需要重新检查磁盘。

## 4. 环境准备

如果 `/root/qwen35-fast` 已经安装并通过验证，可直接进入第 5 节。

### 4.1 创建虚拟环境

复用云镜像自带的 PyTorch，避免重新下载数 GB 的 Torch/CUDA 包：

```bash
python3 -m venv --system-site-packages /root/qwen35-fast
source /root/qwen35-fast/bin/activate

python -m pip install -U pip setuptools wheel
```

确认当前 Python：

```bash
which python
```

应输出：

```text
/root/qwen35-fast/bin/python
```

### 4.2 一次性安装主要依赖

```bash
export PIP_PREFER_BINARY=1

python -m pip install --upgrade-strategy only-if-needed \
  "ms-swift==4.4.1" \
  "transformers>=5.9,<6" \
  "datasets==4.8.4" \
  "pandas==2.3.3" \
  "qwen_vl_utils>=0.0.14" \
  "peft>=0.17" \
  accelerate safetensors bitsandbytes liger-kernel ninja packaging psutil
```

Qwen3.5 在 ms-swift 的模型注册信息中包含 `decord` 依赖，即使当前数据是纯文本，也可能在加载阶段检查该包：

```bash
python -m pip install --no-deps "decord==0.6.0"
```

如果 `pip check` 输出：

```text
decord 0.6.0 is not supported on this platform
```

以实际导入结果为准：

```bash
python -c "import decord; print(decord.__version__)"
```

### 4.3 安装 Qwen3.5 计算内核

```bash
export MAX_JOBS=4
export NVCC_THREADS=2

python -m pip install -U "flash-linear-attention>=0.4.2" --no-build-isolation

python -m pip install -v \
  "causal-conv1d==1.6.2.post1" \
  --no-build-isolation
```

当前环境为 PyTorch 2.9、CUDA 12.x、Python 3.12、CXX11 ABI TRUE，可直接安装官方 FlashAttention wheel，避免源码编译：

```bash
python -c "import torch; print(torch.__version__, torch.version.cuda, torch._C._GLIBCXX_USE_CXX11_ABI)"

python -m pip install --no-deps \
  "https://github.com/Dao-AILab/flash-attention/releases/download/v2.8.3/flash_attn-2.8.3%2Bcu12torch2.9cxx11abiTRUE-cp312-cp312-linux_x86_64.whl"
```

### 4.4 验证核心环境

```bash
python - <<'PY'
import torch
import bitsandbytes
import flash_attn
import causal_conv1d
import fla
import swift
import decord

print("torch:", torch.__version__)
print("CUDA:", torch.version.cuda)
print("GPU:", torch.cuda.get_device_name(0))
print("flash-attn:", flash_attn.__version__)
print("causal-conv1d:", causal_conv1d.__version__)
print("ms-swift:", swift.__version__)
print("core environment OK")
PY
```

## 5. 上传和验证训练数据

当前项目训练文件：

```text
data/lora/psychqa.jsonl
```

本地 Windows PowerShell 上传：

```powershell
scp -P 43569 "D:\project\multimodalAgent\data\lora\psychqa.jsonl" root@14de396cb27349a0a89c5390e6ba92a3.region1.waas.aigate.cc:/root/psychqa.jsonl
```

服务器验证：

```bash
python - <<'PY'
import json

path = "/root/psychqa.jsonl"
count = 0

with open(path, encoding="utf-8") as f:
    for line_no, line in enumerate(f, 1):
        obj = json.loads(line)
        missing = {"instruction", "input", "output"} - obj.keys()
        assert not missing, f"第 {line_no} 行缺少字段: {missing}"
        count += 1

print("数据验证成功，样本数:", count)
PY
```

预期：

```text
数据验证成功，样本数: 2400
```

当前数据集结构为：

```text
唯一 instruction：分析用户文本情绪，只能输出四类标签
输出类别：正常、焦虑、低落、高风险
每类样本数：600
```

## 6. 使用 tmux 防止 SSH 断线

如果系统没有 tmux：

```bash
apt-get update
apt-get install -y tmux
```

创建冒烟测试会话：

```bash
tmux new -s qwen35-bf16-smoke
```

常用操作：

```text
Ctrl+B，然后按 D                退出但保持进程运行
tmux attach -t qwen35-bf16-smoke  重新进入
tmux ls                           查看会话
```

## 7. BF16 LoRA 冒烟测试

冒烟测试应使用和正式训练相同的 `max_length=512`，只把训练步数缩短到 5。

```bash
source /root/qwen35-fast/bin/activate

unset PYTORCH_CUDA_ALLOC_CONF
export PYTORCH_ALLOC_CONF=expandable_segments:True
export CUDA_VISIBLE_DEVICES=0
export MODELSCOPE_CACHE=/root/model-cache

swift sft \
  --model Qwen/Qwen3.5-9B \
  --dataset /root/psychqa.jsonl \
  --tuner_type lora \
  --torch_dtype bfloat16 \
  --freeze_vit true \
  --freeze_aligner true \
  --add_non_thinking_prefix true \
  --loss_scale ignore_empty_think \
  --lora_rank 8 \
  --lora_alpha 32 \
  --target_modules all-linear \
  --per_device_train_batch_size 1 \
  --gradient_accumulation_steps 2 \
  --gradient_checkpointing true \
  --learning_rate 1e-4 \
  --max_length 512 \
  --split_dataset_ratio 0 \
  --max_steps 5 \
  --save_steps 5 \
  --logging_steps 1 \
  --dataset_num_proc 4 \
  --dataloader_num_workers 2 \
  --attn_impl flash_attention_2 \
  --report_to none \
  --output_dir /root/output/qwen35-9b-bf16-lora-smoke \
  --system "你是校园心理关怀智能体。分析任务必须严格按照要求输出指定标签；对话任务保持温和、稳定、非评判，高风险内容优先保护学生安全。" \
  2>&1 | tee /root/qwen35-bf16-smoke.log
```

成功标志：

```text
global_step/max_steps: 5/5
```

显存监控：

```bash
watch -n 1 nvidia-smi
```

如果 `max_length=512` 出现 OOM，可先改为 256。降低上下文只减少激活显存，不会减少约 18～19GB 的 BF16 基础权重。

## 8. 正式 BF16 LoRA 训练

新建正式训练会话：

```bash
tmux new -s qwen35-bf16
```

执行：

```bash
source /root/qwen35-fast/bin/activate

unset PYTORCH_CUDA_ALLOC_CONF
export PYTORCH_ALLOC_CONF=expandable_segments:True
export CUDA_VISIBLE_DEVICES=0
export MODELSCOPE_CACHE=/root/model-cache

swift sft \
  --model Qwen/Qwen3.5-9B \
  --dataset /root/psychqa.jsonl \
  --tuner_type lora \
  --torch_dtype bfloat16 \
  --freeze_vit true \
  --freeze_aligner true \
  --add_non_thinking_prefix true \
  --loss_scale ignore_empty_think \
  --lora_rank 8 \
  --lora_alpha 32 \
  --target_modules all-linear \
  --num_train_epochs 3 \
  --per_device_train_batch_size 1 \
  --per_device_eval_batch_size 1 \
  --gradient_accumulation_steps 16 \
  --gradient_checkpointing true \
  --learning_rate 1e-4 \
  --lr_scheduler_type cosine \
  --warmup_ratio 0.05 \
  --max_length 512 \
  --split_dataset_ratio 0.1 \
  --data_seed 42 \
  --eval_strategy steps \
  --eval_steps 100 \
  --save_steps 50 \
  --save_total_limit 2 \
  --logging_steps 5 \
  --dataset_num_proc 4 \
  --dataloader_num_workers 2 \
  --attn_impl flash_attention_2 \
  --report_to none \
  --output_dir /root/output/qwen35-9b-psychqa-bf16-lora \
  --system "你是校园心理关怀智能体。分析任务必须严格按照要求输出指定标签；对话任务保持温和、稳定、非评判，高风险内容优先保护学生安全。" \
  2>&1 | tee /root/qwen35-bf16-train.log
```

2400 条数据按 9:1 划分后：

```text
训练集约 2160 条
验证集约 240 条
每轮优化步骤约 2160 / 16 = 135
3 轮总步骤约 405
```

查看进度：

```bash
tail -f /root/qwen35-bf16-train.log
```

连续观察 GPU：

```bash
timeout 30 nvidia-smi dmon -s pucm -d 1
```

`per_device_train_batch_size=1` 且样本较短时，GPU 利用率低于 50% 不一定异常。`gradient_accumulation_steps=16` 是串行累积，不会一次向 GPU 输入 16 条样本。应优先观察总训练时间和 `train_speed(s/it)`。

如果希望提高吞吐，可另做冒烟测试：

```text
--per_device_train_batch_size 2
--per_device_eval_batch_size 2
--gradient_accumulation_steps 8
```

有效 batch 仍为 16，但必须重新确认 `max_length=512` 时不发生 OOM。

## 9. 训练完成检查

成功标志：

```text
global_step/max_steps: 405/405
```

检查异常：

```bash
grep -iE "nan|oom|traceback|error" /root/qwen35-bf16-train.log
```

查找 Adapter：

```bash
find /root/output/qwen35-9b-psychqa-bf16-lora \
  -name adapter_model.safetensors
```

自动选择编号最大的 checkpoint：

```bash
ADAPTER=$(find /root/output/qwen35-9b-psychqa-bf16-lora \
  -type f -name adapter_model.safetensors \
  -printf '%h\n' | sort -V | tail -1)

echo "$ADAPTER"
ls -lh "$ADAPTER"
```

推理、合并和备份时要使用完整 checkpoint 目录，不是只使用 `adapter_model.safetensors`。

## 10. Adapter 分类能力验收

```bash
source /root/qwen35-fast/bin/activate

export CUDA_VISIBLE_DEVICES=0
export PYTORCH_ALLOC_CONF=expandable_segments:True
export MODELSCOPE_CACHE=/root/model-cache

swift infer \
  --adapters "$ADAPTER" \
  --infer_backend transformers \
  --stream true \
  --enable_thinking false \
  --temperature 0 \
  --max_new_tokens 32
```

测试提示：

```text
分析用户文本情绪，只能输出：正常、焦虑、低落、高风险。用户文本：最近总是睡不好，一想到考试就心慌。
```

预期输出：

```text
焦虑
```

至少覆盖四个类别以及若干边界样本。

批量验证训练时的验证集：

```bash
swift infer \
  --adapters "$ADAPTER" \
  --infer_backend transformers \
  --enable_thinking false \
  --temperature 0 \
  --max_new_tokens 32 \
  --load_data_args true \
  --max_batch_size 1 \
  --metric acc \
  --result_path /root/qwen35-validation-results.jsonl
```

## 11. 项目 JSON 兼容性验收（必须执行）

当前训练数据只让模型返回四个中文标签，但项目的 `PromptTemplates.psychologyPrompt` 要求严格 JSON：

```json
{"emotion":"NORMAL|ANXIETY|DEPRESSED|HIGH_RISK","emotionScore":0.0,"risk":"LOW|MEDIUM|HIGH","confidence":0.0,"summary":"short reason"}
```

项目的 `PsychologicalAssessmentService` 会按 JSON 解析；解析失败时会退回关键词启发式判断。因此部署前必须用项目真实提示词验证：

```bash
swift infer \
  --adapters "$ADAPTER" \
  --infer_backend transformers \
  --stream true \
  --enable_thinking false \
  --temperature 0 \
  --max_new_tokens 128 \
  --system '你负责分析校园心理健康消息。只返回严格 JSON，不要包含 Markdown 或解释文字：{"emotion":"NORMAL|ANXIETY|DEPRESSED|HIGH_RISK","emotionScore":0.0,"risk":"LOW|MEDIUM|HIGH","confidence":0.0,"summary":"short reason"}'
```

输入：

```text
最近上下文：无。当前输入：最近总是睡不好，一想到考试就心慌。
```

期望输出类似：

```json
{"emotion":"ANXIETY","emotionScore":2.0,"risk":"LOW","confidence":0.92,"summary":"因考试压力出现失眠和心慌。"}
```

如果模型只输出“焦虑”，说明 Adapter 的标签任务训练成功，但与项目 JSON 接口不匹配。此时不要把部署视为完成，应选择以下方案之一：

1. 修改项目解析器，使其同时兼容四个中文标签和 JSON。
2. 将训练数据输出改成项目要求的 JSON，再重新训练。
3. 为聊天和心理分类配置两个独立模型或 Adapter。

项目聊天功能与心理评估当前共用同一个 `AiClient`，所以还必须测试普通聊天，确认模型不会对所有用户消息都只返回分类标签。

## 12. 合并 BF16 LoRA

先检查空间：

```bash
df -h /root
du -sh /root/model-cache /root/output
```

仅合并模型通常还需要约 20GB 空间。完整转换链路建议另外准备约 45GB 临时空间。

验证通过且空间足够后：

```bash
source /root/qwen35-fast/bin/activate

swift export \
  --adapters "$ADAPTER" \
  --merge_lora true \
  --output_dir /root/output/qwen35-9b-psychqa-bf16-merged
```

验证：

```bash
du -sh /root/output/qwen35-9b-psychqa-bf16-merged
ls -lh /root/output/qwen35-9b-psychqa-bf16-merged
```

不要在验证 Q4 模型并完成下载前删除基础模型缓存、Adapter 或合并模型。

## 13. 单独准备 llama.cpp 环境

不要在 `qwen35-fast` 环境中安装 llama.cpp 的 Python 依赖，避免其 requirements 修改 `transformers` 版本。

```bash
deactivate

apt-get update
apt-get install -y git cmake build-essential

cd /root
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git

python3 -m venv /root/llamacpp-env
source /root/llamacpp-env/bin/activate

python -m pip install -U pip
python -m pip install -r /root/llama.cpp/requirements.txt
python -m pip install -U "transformers>=5.9,<6"

cd /root/llama.cpp
cmake -B build
cmake --build build --config Release -j 8
```

## 14. 转换为 BF16 GGUF

当前项目向主模型发送文本结果，视觉、音频和视频由项目内其他模块处理，因此此处只导出文本模型，不导出 `mmproj`。

```bash
cd /root/llama.cpp
source /root/llamacpp-env/bin/activate

python convert_hf_to_gguf.py \
  /root/output/qwen35-9b-psychqa-bf16-merged \
  --outfile /root/output/qwen35-9b-psychqa-BF16.gguf \
  --outtype bf16 \
  --no-mtp
```

`--no-mtp` 排除当前项目不使用的 Multi-Token Prediction 头。不要沿用旧 Guide 的 `--outtype f16`，本文保留 BF16 精度作为量化源。

确认：

```bash
ls -lh /root/output/qwen35-9b-psychqa-BF16.gguf
```

## 15. 量化为 Q4_K_M

```bash
/root/llama.cpp/build/bin/llama-quantize \
  /root/output/qwen35-9b-psychqa-BF16.gguf \
  /root/output/qwen35-9b-psychqa-Q4_K_M.gguf \
  Q4_K_M
```

确认：

```bash
ls -lh /root/output/qwen35-9b-psychqa-Q4_K_M.gguf
```

## 16. 云端测试 GGUF

```bash
/root/llama.cpp/build/bin/llama-cli \
  -m /root/output/qwen35-9b-psychqa-Q4_K_M.gguf \
  -ngl 99 \
  -c 4096 \
  -n 128 \
  --temp 0 \
  -p "分析用户文本情绪，只能输出正常、焦虑、低落、高风险。用户文本：最近一想到考试就心慌失眠。"
```

必须确认量化后仍能正常加载、生成，并保持分类效果，再下载到本地。

## 17. 下载 GGUF 到本地项目

本地 Windows PowerShell：

```powershell
$dir = "D:\project\multimodalAgent\models\multimodalAgent-qwen3.5-9b-ft"
New-Item -ItemType Directory -Force $dir

scp -P 43569 root@14de396cb27349a0a89c5390e6ba92a3.region1.waas.aigate.cc:/root/output/qwen35-9b-psychqa-Q4_K_M.gguf "$dir\multimodalAgent-qwen3.5-9b-ft-Q4_K_M.gguf"
```

## 18. 创建 Ollama Modelfile

创建文件：

```text
models/multimodalAgent-qwen3.5-9b-ft/Modelfile
```

内容：

```text
FROM ./multimodalAgent-qwen3.5-9b-ft-Q4_K_M.gguf

SYSTEM """
你是校园心理关怀智能体。保持温和、稳定、非评判。
当用户表达普通问题时，正常回答，不强行进行心理测评。
当用户表达情绪困扰时，先共情，再给出具体、可执行的小步骤。
遇到明确自伤、伤人或自杀风险时，不提供危险细节，优先鼓励用户联系身边可信任的人、学校心理中心或当地紧急救助。
不要向普通聊天用户输出后台风险等级、心理报告、评分或诊断结论。
"""

PARAMETER num_ctx 4096
PARAMETER temperature 0.35
```

创建 Ollama 模型：

```powershell
ollama create multimodalAgent-qwen3.5-9b-ft:latest `
  -f "D:\project\multimodalAgent\models\multimodalAgent-qwen3.5-9b-ft\Modelfile"
```

查看：

```powershell
ollama list
```

测试：

```powershell
ollama run multimodalAgent-qwen3.5-9b-ft:latest
```

## 19. 项目接入

当前以下文件仍默认使用旧模型名：

```text
src/main/resources/application.yml
scripts/run-dev.sh
scripts/create-finetuned-model.sh
docker-compose.yml
scripts/package-split-release.sh
```

在永久修改项目前，可以先通过环境变量接入新模型。

Windows PowerShell：

```powershell
$env:AI_PROVIDER = "ollama"
$env:OLLAMA_MODEL = "multimodalAgent-qwen3.5-9b-ft:latest"
$env:OLLAMA_BASE_URL = "http://localhost:11434"
$env:AI_TEMPERATURE = "0.35"
```

然后按项目原有方式启动服务。

必须验证：

1. 普通聊天仍能自然回答，不会只输出情绪标签。
2. 心理评估接口能得到可解析的 JSON，或者项目已兼容四类标签。
3. 高风险硬规则仍优先于模型输出。
4. Ollama 请求实际使用 `multimodalAgent-qwen3.5-9b-ft:latest`。

## 20. 常见现象

### 20.1 `PYTORCH_CUDA_ALLOC_CONF` deprecated

旧变量：

```bash
export PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
```

PyTorch 2.9 应改为：

```bash
unset PYTORCH_CUDA_ALLOC_CONF
export PYTORCH_ALLOC_CONF=expandable_segments:True
```

### 20.2 冒烟测试最后学习率为 0

使用 `max_steps=5` 和余弦调度器时，最后一步学习率衰减到 0 属于正常现象。正式训练约 405 步，学习率会先 warmup，再逐步衰减。

### 20.3 GPU 利用率低于 50%

短文本、batch size 1 和 Python 内核调度会造成 GPU 间歇空闲。只要训练速度稳定、没有长时间 GPU 0%、没有内核回退或数据加载卡顿，就不属于失败。

### 20.4 BF16 LoRA 和 QLoRA 显存差异

已观察到：

```text
4-bit QLoRA 约 9GB
BF16 LoRA（max_length=256）约 18.14GiB
```

差异主要来自基础权重：4-bit 约 5～6GB，BF16 约 18～19GB。

### 20.5 `Building wheel ...` 长时间无输出

另开 SSH 检查：

```bash
ps -eo pid,etime,%cpu,%mem,cmd --sort=-%cpu | \
grep -E 'pip|bdist|ninja|nvcc|g\+\+|c\+\+' | grep -v grep
```

有 `ninja/nvcc/g++` 表示正在编译；只有 Python 且 CPU 长期为 0，通常是下载或子进程卡住。

## 21. 官方参考

- [ms-swift Qwen3.5 Best Practices](https://swift.readthedocs.io/en/latest/BestPractices/Qwen3_5-Best-Practice.html)
- [ms-swift LoRA 推理与合并](https://swift.readthedocs.io/en/latest/Instruction/Pre-training-and-Fine-tuning.html)
- [FlashAttention 官方仓库](https://github.com/Dao-AILab/flash-attention)
- [llama.cpp Hugging Face → GGUF 转换器](https://github.com/ggml-org/llama.cpp/blob/master/convert_hf_to_gguf.py)
- [llama.cpp 量化说明](https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md)
- [Ollama Qwen3.5](https://ollama.com/library/qwen3.5)

