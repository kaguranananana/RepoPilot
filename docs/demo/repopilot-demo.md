# RepoPilot Demo Guide

本文档整理当前可以直接对外展示的 3 类证据：

1. scripted baseline
2. real-model eval baseline
3. 真实交互式 CLI 会话记录

目标不是把所有结果包装成“已经产品完成”，而是把已经验证过的能力和已经暴露出的风险都讲清楚。

---

## Demo 1：Scripted Baseline

### 命令

```bash
mvn -q -DskipTests install
mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval
```

### 期望输出

```text
eval report: target/repopilot-eval-report.json
scenario_count=10 task_success_rate=1.0000 tool_call_valid_rate=1.0000
```

### 当前结果

- `runtimeKind=SCRIPTED_RUNTIME`
- `scenarioCount=10`
- `taskSuccessRate=1.0`
- `toolCallValidRate=1.0`
- `avgSteps=2.2`
- `avgDurationMillis=5.5`

### 适合怎么讲

- 证明最小 runtime 主链路已经可重复执行
- 证明工具注册、权限治理、补丁修改、命令验证、上下文压缩和 Skill 激活都进入了固定任务集
- 不要把这部分讲成“真实模型产品能力”

## Demo 2：Real-Model Eval Baseline

### 命令

```bash
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval --runtime-kind REAL_MODEL_PROVIDER
```

### 期望输出

```text
eval report: target/repopilot-real-model-eval-report.json
scenario_count=5 task_success_rate=1.0000 tool_call_valid_rate=1.0000
```

### 当前结果

- `runtimeKind=REAL_MODEL_PROVIDER`
- `scenarioCount=5`
- `taskSuccessRate=1.0`
- `toolCallValidRate=1.0`
- `avgSteps=3.2`
- `avgDurationMillis=14246.2`

### 覆盖场景

- `code-search`
- `file-read`
- `patch-edit`
- `command-validation`
- `search-read-patch-command`

### 适合怎么讲

- 证明 OpenAI-compatible 适配层、真实模型 provider、工具协议和 eval runtime 在当前环境里是通的
- 证明最小产品门槛里的“搜索 / 阅读 / 补丁修改 / 命令验证 / 最终回答”已经在真实模型固定任务集里跑通
- 仍然不能替代真实交互式 REPL 的产品体验证明

## Demo 3：真实交互式 CLI Smoke Path

### 前置

先启动 server：

```bash
cd repopilot-server
mvn spring-boot:run
```

回到仓库根目录后，准备 CLI classpath：

```bash
cd ..
mvn -q -DskipTests install
mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
```

### 会话 A：更窄的真实交互 smoke path，已通过

#### 输入文件

文件：`target/manual-e2e/real-model-demo-min.txt`

初始内容：

```text
status=draft
```

#### CLI 输入

```text
直接 read_file 读取 target/manual-e2e/real-model-demo-min.txt；再用 apply_patch 把其中的 status=draft 改成 status=ready；再运行命令 grep -n 'status=ready' target/manual-e2e/real-model-demo-min.txt 验证；最后用两句中文汇报结果。
```

#### 实际观测

- session：`session-f602b806-79ec-432a-8494-ab8a13b8fa76`
- 工具序列：
  1. `read_file`
  2. `apply_patch`
  3. `run_command`
- trace 证据：
  - 第 1 步 `read_file -> SUCCESS`
  - 第 2 步 `apply_patch -> SUCCESS`
  - 第 3 步 `run_command -> SUCCESS`
  - 第 4 步 `FINAL`
- 最终文件内容：

```text
status=ready
```

#### 适合怎么讲

- 证明真实控制面 + 真实交互式 CLI + 真实模型 + 审批 + trace 的整条链路已可运行
- 这是一条“窄 smoke path”，能证明最小真实交互闭环，不代表复杂提示词下已经稳定

### 会话 B：更宽的真实交互提示，暴露了真实缺陷

#### 输入文件

文件：`target/manual-e2e/real-model-demo.txt`

初始内容：

```text
name=RepoPilot
marker=repopilot-demo-20260420
status=draft
```

#### CLI 输入

```text
先用 grep_files 搜索 repopilot-demo-20260420，限定在 target/manual-e2e；找到命中文件后读取该文件；然后用 apply_patch 把 target/manual-e2e/real-model-demo.txt 里的 status=draft 改成 status=ready；再运行命令 grep -n 'status=ready' target/manual-e2e/real-model-demo.txt 验证；最后用两句中文汇报结果。
```

#### 实际观测

- session：`session-7dcbb46c-3c16-4810-a955-9fd11e96228e`
- 工具序列：
  1. `grep_files`
  2. `read_file`
  3. `apply_patch`
  4. `run_command`
- 表面结果：
  - 模型最终汇报“任务已完成”
  - `grep -n 'status=ready'` 返回 `exitCode=0`
- 真实问题：
  - `apply_patch` 的 hunk 同时删除了无关行 `name=RepoPilot`
  - 最终文件内容变成：

```text
marker=repopilot-demo-20260420
status=ready
```

#### 说明

这条记录很重要，因为它证明 RepoPilot 当前并不是“只会成功”的演示壳子，而是真实暴露了当前产品化缺口：

- 验证命令只证明目标状态存在，不能证明改动足够精确
- 缺少更强的计划阶段与验证约束
- 这已经不再阻塞“最小真实模型编码任务闭环”这一门槛，但仍然是后续 `Plan / Execute`、更强回归验证、循环检测和 Skill 工具约束要继续补的直接理由

## 对外演示建议

如果要在面试或简历沟通中展示，建议按下面顺序讲：

1. 先讲 scripted baseline，说明 runtime 主链路和工具治理已经固定可跑。
2. 再讲 real-model eval，说明真实模型 provider 已经接通，而且固定任务集里已经有一条完整编码任务闭环。
3. 最后讲真实交互式 CLI：
   - 先给出会话 A，证明最小真实交互闭环已经存在。
   - 再补一句会话 B 暴露过宽补丁问题，说明你清楚当前还没产品完成，并且知道缺口在哪里。

这种讲法比单纯报“都通过了”更可信，也更像真正做过 Agent Runtime 的人。
