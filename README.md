# RepoPilot

RepoPilot 是一个面向本地代码仓研发任务的 Java coding agent runtime。它采用“本地执行 Runtime + 服务端 Control Plane”的双层架构，重点不是做聊天壳子，而是把 `model -> tool -> model`、工具治理、补丁式修改、命令验证、上下文压缩、Skill 激活和 session / trace 这些关键机制真正做出来。

当前最准确的项目状态是：

- `Java coding agent runtime`
- `本地 coding agent 雏形`
- `最小 runtime 闭环已具备，产品化收尾仍在继续`

当前不应直接表述为：

- `产品级 coding agent 已完成`
- `通用 coding agent 产品`
- `稳定支持复杂多模块重构的开发代理`

## 当前能力

RepoPilot 当前已经落地的能力包括：

- 多轮 `model -> tool -> model` Agent Loop
- 真实内置工具：`grep_files`、`read_file`、`apply_patch`、`write_file`、`run_command`、`activate_skill`
- 受治理的工具执行流水线：参数校验、权限判定、审批、diff review、fail-closed
- 补丁式最小修改主链路，而不是只依赖整文件覆盖
- 结构化 `working_memory` 与 `context_summary`
- Skill 摘要注入与显式激活
- 交互式 CLI
- 最小 session / trace 控制面
- scripted 与 real-model 两套评测入口

## 当前能力边界

RepoPilot 当前只承诺“小型、明确、受控”的本地编码任务：

- 适合单文件或少量文件的局部修改
- 适合明确报错定位、小功能补齐、命令验证
- 不承诺稳定完成跨模块重构、长时间调试、模糊需求拆解或产品级全自动交付

另外，需要明确区分三类证据：

- `bootstrap` 假模型：只能证明 prompt 和 runtime 接线正确
- `SCRIPTED_RUNTIME` 评测：证明 runtime 在固定场景下可重复运行
- `REAL_MODEL_PROVIDER` 评测和真实交互会话：证明真实模型链路可用，但仍不能等同于“复杂任务已稳定产品化”

## 已验证证据

截至 `2026-04-20`，当前仓库已经有下面这些实际验证结果：

- `mvn test` 全量通过
- scripted 评测通过：`scenarioCount=10`、`taskSuccessRate=1.0`、`toolCallValidRate=1.0`、`avgSteps=2.2`
- real-model 评测通过：`scenarioCount=5`、`taskSuccessRate=1.0`、`toolCallValidRate=1.0`、`avgSteps=3.2`
- real-model 固定任务集现在已经包含一条严格验收的端到端编码场景：`grep_files -> read_file -> apply_patch -> run_command -> final`
- 真实交互式 CLI smoke path 已跑通一条 `read_file -> apply_patch -> run_command -> final`
- 一条更宽的真实交互提示仍暴露过补丁精度风险：模型在完成状态修改的同时删掉了无关行，说明“最小门槛已过”不等于“复杂提示下已经稳定产品化”

详细记录见：

- `docs/eval/reports/repopilot-baseline.md`
- `docs/demo/repopilot-demo.md`

## 架构

- `repopilot-core`
  Agent Runtime。包含消息模型、Agent Loop、Tool Registry、权限策略、diff review、上下文压缩、Skill 加载与激活。
- `repopilot-cli`
  本地执行入口。包含交互式 CLI、单次运行入口、评测入口、审批交互和 trace 上报。
- `repopilot-server`
  最小控制面。当前只做 session / trace 的创建、查询和回放。
- `repopilot-protocol`
  共享协议。包含 DTO、事件模型和 JSON 序列化约定。

## 环境要求

- Java 17+
- Maven 3.9+
- 可访问的 OpenAI-compatible 接口

仓库根目录需要 `.env.local`，至少包含：

```dotenv
REPOPILOT_SERVER_BASE_URL=http://127.0.0.1:8080
REPOPILOT_WORKSPACE_ID=coding-agent
REPOPILOT_MODEL_PROVIDER=openai-compatible
OPENAI_COMPATIBLE_API_KEY=your-api-key
OPENAI_COMPATIBLE_BASE_URL=https://your-openai-compatible-endpoint/v1
OPENAI_COMPATIBLE_MODEL=your-model-id
# 可选
REPOPILOT_TRACE_LEVEL=summary
REPOPILOT_MAX_STEPS=12
```

## 快速开始

### 1. 运行测试

```bash
mvn test
```

### 2. 启动控制面

当前最稳定的启动方式是进入 `repopilot-server` 模块运行：

```bash
cd repopilot-server
mvn spring-boot:run
```

### 3. 构建 CLI 运行时 classpath

当前仓库还没有收口成 fat jar 或统一 launcher，CLI 入口使用显式 classpath 运行：

```bash
cd ..
mvn -q -DskipTests install
mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
```

### 4. 启动交互式 CLI

```bash
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication
```

交互命令：

- `/help`
- `/reset`
- `/exit`

### 5. 运行 scripted 评测

```bash
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval
```

输出报告：

- `target/repopilot-eval-report.json`

### 6. 运行 real-model 评测

```bash
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval --runtime-kind REAL_MODEL_PROVIDER
```

输出报告：

- `target/repopilot-real-model-eval-report.json`

## Demo 指南

推荐先看 `docs/demo/repopilot-demo.md`，其中已经整理了三类可对外展示的证据：

1. scripted baseline
2. real-model eval baseline（已包含最小端到端编码任务）
3. 真实交互式 CLI smoke path 与一条失败暴露记录

## 最小产品门槛状态

- [x] 真实模型端到端编码任务跑通并形成稳定产品证据
- [x] 固定任务集评测可重复执行
- [ ] `Plan / Execute` 只读阶段落地
- [ ] 确定性循环检测进入运行时
- [ ] Skill `allowed-tools` 进入 prompt + runtime 双重约束
- [x] README / demo / baseline / 简历口径完全一致并收口

当前仍未过线的关键项不是继续加工具，而是：

1. `Plan / Execute`
2. 确定性循环检测
3. Skill `allowed-tools` 运行时治理
4. 更复杂开放式提示下的补丁精度与回归验证约束

## 面试表述建议

当前更稳妥的项目表述是：

> 基于 Java 实现了一个本地 coding agent runtime，支持搜索 / 读取 / 补丁修改 / 命令验证闭环，具备工具治理、上下文压缩、Skill 激活、session / trace 控制面，以及 scripted / real-model 双口径评测。

如果面试官继续追问，可以明确补一句：

> 当前已经完成真实模型下的最小编码任务闭环和可重复评测，但还在补 `Plan / Execute`、循环检测和 Skill 工具约束这几个产品化收尾项。
