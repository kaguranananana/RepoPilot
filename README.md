# RepoPilot

RepoPilot 是一个基于 Java 的本地 coding agent runtime。它聚焦 coding agent 的核心执行链路：让模型在本地代码仓中搜索文件、读取内容、应用补丁、运行验证命令，并把每一步工具调用纳入权限、审批、trace 和上下文治理。

项目当前定位是 **学习型 coding agent runtime / 面试展示项目 / 可参考的 Java 实现样例**。它不是为了替代 Claude Code、Codex 这类成熟工具，也不是为了成为日常生产可用的通用 coding agent；选择 Java 更多是为了用后端工程的方式拆解 agent runtime 的核心机制，并把学习过程包装成一个可以复盘、可以演示、可以开源参考的项目。

## 项目定位

- **学习优先**：重点是理解并实现 coding agent 的核心链路，而不是追求产品级易用性。
- **面试可讲**：代码结构围绕 agent loop、工具治理、上下文压缩、Skill 激活、trace 控制面等可展开的技术点组织。
- **供参考学习**：开源出来主要是给同样想拆解 coding agent 机制的人参考，尤其是 Java 后端同学。
- **不做过度承诺**：当前更适合验证小型、明确、受控的本地编码任务，不定位为成熟可替代现有工具的 coding agent 产品。

## 快速开始

### 环境要求

- Java 17+
- Maven 3.9+
- Docker，用于启动本地 PostgreSQL
- OpenAI-compatible 或 Anthropic-compatible 模型接口

### 配置环境变量

在仓库根目录创建 `.env.local`：

```dotenv
REPOPILOT_SERVER_BASE_URL=http://127.0.0.1:8080
REPOPILOT_WORKSPACE_ID=coding-agent

REPOPILOT_DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/repopilot
REPOPILOT_DATABASE_USERNAME=repopilot
REPOPILOT_DATABASE_PASSWORD=repopilot

REPOPILOT_MODEL_PROVIDER=openai-compatible
OPENAI_COMPATIBLE_API_KEY=your-api-key
OPENAI_COMPATIBLE_BASE_URL=https://your-openai-compatible-endpoint/v1
OPENAI_COMPATIBLE_MODEL=your-model-id

REPOPILOT_TRACE_LEVEL=summary
REPOPILOT_MAX_STEPS=12
```

如果使用 Anthropic Messages 接口，将模型配置改为：

```dotenv
REPOPILOT_MODEL_PROVIDER=anthropic
ANTHROPIC_API_KEY=your-api-key
ANTHROPIC_BASE_URL=https://your-anthropic-endpoint
ANTHROPIC_MODEL=your-model-id
```

### 运行测试

```bash
mvn test
```

### 启动控制面

```bash
docker compose up -d postgres
mvn -q -pl repopilot-server -am -DskipTests install
cd repopilot-server
set -a; source ../.env.local; set +a
mvn spring-boot:run
```

`repopilot-server` 会连接 PostgreSQL，并通过 Flyway 自动创建 session、trace 和 plan 相关表。

### 启动交互式 CLI

回到仓库根目录，构建 CLI 运行时 classpath：

```bash
cd ..
mvn -q -DskipTests install
mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
```

启动 CLI：

```bash
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication
```

常用交互命令：

```text
/help
/plan
/execute
/reset
/exit
```

## 核心能力

- **Agent Loop**：支持多轮 `model -> tool -> model` 执行闭环。
- **内置工具**：提供 `grep_files`、`read_file`、`apply_patch`、`write_file`、`run_command`、`activate_skill`。
- **工具治理**：在工具执行前做必填参数校验、运行模式校验、Skill 工具约束校验、工作区权限判定、人工审批和 diff review。
- **Plan / Execute 模式**：`PLAN` 阶段只允许只读工具，`EXECUTE` 阶段才允许在治理边界内修改和验证。
- **补丁式修改**：修改已有文件时优先使用 `apply_patch`，把最小变更作为主链路。
- **循环检测**：对连续重复的同一工具调用做确定性检测，达到阈值后中断当前回合并写入 trace。
- **上下文压缩**：通过 `working_memory`、`context_summary`、工具结果 microcompact 和结构化摘要控制长轮次上下文成本。
- **Skill 激活**：支持用户通过 `/skill-name` 或 `$skill-name` 显式激活，也支持模型调用 `activate_skill`；已激活 Skill 的 `allowed-tools` 会参与工具子集交集计算。
- **控制面追踪**：通过 Spring Boot API 持久化 session、trace event 和 plan，支持后续查询与回放。
- **双口径评测**：提供 scripted runtime 评测和真实模型 provider 评测，并提供上下文成本对比报告。

## 项目结构

| 模块 | 说明 |
| --- | --- |
| `repopilot-core` | Agent Loop、工具注册、权限治理、diff review、上下文压缩、Skill 加载与激活 |
| `repopilot-cli` | 交互式 CLI、单次运行命令、模型适配、评测命令、trace 上报 |
| `repopilot-server` | session / trace / plan 控制面，基于 Spring Boot、PostgreSQL 和 Flyway |
| `repopilot-protocol` | 共享 DTO、事件模型和 JSON 序列化约定 |

## 运行评测

先确保已经构建 CLI classpath：

```bash
mvn -q -DskipTests install
mvn -q -pl repopilot-cli -am -DskipTests dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
CP="repopilot-cli/target/classes:$(cat repopilot-cli/target/classpath.txt)"
```

运行确定性 scripted 评测：

```bash
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval
```

默认输出：

- `target/repopilot-eval-report.json`

运行真实模型评测：

```bash
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication eval --runtime-kind REAL_MODEL_PROVIDER
```

默认输出：

- `target/repopilot-real-model-eval-report.json`

运行本地估算口径的上下文成本评测：

```bash
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication context-cost
```

默认输出：

- `target/repopilot-context-cost-estimated-report.json`
- `target/repopilot-context-cost-estimated-report.md`

运行真实 usage 口径的上下文成本评测：

```bash
java -cp "$CP" com.repopilot.cli.RepoPilotCliApplication context-cost --measurement-kind REAL_USAGE
```

默认输出：

- `target/repopilot-context-cost-real-usage-report.json`
- `target/repopilot-context-cost-real-usage-report.md`

## 验证基线

当前仓库保留了三类可以复跑的验证入口：

- `mvn test`：验证多模块 Java 代码、核心 runtime、CLI、server 和协议对象。
- `eval`：验证固定任务集中工具调用、补丁修改、命令验证、Skill 激活和错误暴露。
- `context-cost`：对比完整历史回放和结构化上下文压缩的输入 token 成本。

最近的基线记录见：

- `docs/eval/reports/repopilot-baseline.md`
- `docs/demo/repopilot-demo.md`

## 设计取向

RepoPilot 的运行时倾向直接暴露真实错误，而不是用兜底分支掩盖问题：

- 工具不存在、参数缺失、权限拒绝会返回结构化错误。
- `PLAN` 模式下的写入工具会在审批和执行前被拒绝。
- 补丁失败不会自动切换为整文件覆盖。
- provider 缺失 usage 数据时，真实 usage 口径评测会直接失败。

这些约束让项目更适合做 agent runtime 的工程验证：每条成功路径和失败路径都尽量可解释、可复现、可追踪。

## 参考与致谢

RepoPilot 的很多设计取向参考了市面上成熟 coding agent 产品和开源学习项目。这里列出主要参考来源，方便继续延伸阅读：

- [Claude Code](https://code.claude.com/docs/en/overview)：终端内 coding agent 的产品形态、交互方式和工作流设计。
- [win4r/cc-notebook](https://github.com/win4r/cc-notebook)：Claude Code 相关机制、上下文管理和产品行为分析笔记。
- [OpenAI Codex](https://github.com/openai/codex)：终端 coding agent 的开源实现、工具协议和运行时组织方式。
- [HKUDS/nanobot](https://github.com/HKUDS/nanobot)：轻量级个人 agent 的配置、工具和运行方式参考。
- [JiayuXu0/MiniCode](https://github.com/JiayuXu0/MiniCode)：以“复刻 Claude Code”为目标的学习型实现。
- [LiuMengxuan04/MiniCode](https://github.com/LiuMengxuan04/MiniCode)：轻量终端 coding assistant，实现了 Claude Code-like workflow、tool loop 和 TUI 架构。

RepoPilot 不直接复刻上述项目，也不复用它们的代码；它更像是把这些产品和项目中的关键思想拆开后，用 Java 多模块工程重新组织的一次学习和工程化练习。

## 当前范围

RepoPilot 当前适合验证小型、明确、受控的本地编码任务，例如代码搜索、文件读取、局部补丁修改和命令验证。它不是成熟的通用自动编程产品，也不承诺稳定完成复杂跨模块重构或长期无人值守开发任务。

## 许可证

本项目基于 MIT License 开源，详见 `LICENSE`。
