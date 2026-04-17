# RepoPilot 交互式 CLI 设计说明

**目标**

把当前“必须通过 `run --workspace-id --server-base-url --prompt` 才能发起一次运行”的 CLI，升级成“直接启动就进入交互模式”的终端编码代理入口。

这个交互模式需要满足 4 个要求：

1. 启动后自动进入 REPL，而不是打印骨架提示。
2. 整场会话复用同一个 `session` 和同一份消息历史，行为接近常见 AI Coding Agent。
3. `serverBaseUrl` 和 `workspaceId` 从 `.env.local` 读取，不要求每次手动传参数。
4. 默认在终端打印关键链路摘要，帮助用户理解真实执行过程，但不刷出完整原始 JSON。

---

**用户体验**

默认启动 `RepoPilotCliApplication` 后，根命令直接进入交互模式。

启动流程如下：

1. 读取仓库根目录 `.env.local`
2. 校验 `REPOPILOT_SERVER_BASE_URL` 与 `REPOPILOT_WORKSPACE_ID`
3. 向 server 创建一个新的 `session`
4. 初始化本地 runtime 上下文与系统消息
5. 进入终端输入循环

终端里支持三类输入：

- 普通文本：作为新的 `USER` turn 进入运行时
- `/help`：打印最小命令说明
- `/reset`：清空当前消息历史并重新创建 session
- `/exit`：退出交互模式

每次普通输入都会复用当前会话消息历史，让模型保留上下文。

---

**架构边界**

交互式 CLI 仍然遵循现有双层架构：

- `repopilot-cli + repopilot-core`
  负责本地运行时、模型调用、工具执行与摘要输出
- `repopilot-server`
  负责控制面 session 的创建与后续 trace 承载点

本次改动不会把真实执行迁到 server。server 仍然只作为控制面存在。

---

**核心组件拆分**

为了避免把终端输入循环、消息历史管理和运行时执行全部塞进 `RepoPilotCliCommand`，本次设计拆成 4 个清晰单元：

- `RepoPilotCliCommand`
  负责把根命令切换为交互入口，并启动交互会话。

- `InteractiveCliConfig`
  负责从 `.env.local + 进程环境变量` 中解析交互模式所需的配置，至少包括：
  - `REPOPILOT_SERVER_BASE_URL`
  - `REPOPILOT_WORKSPACE_ID`

- `InteractiveCliSession`
  负责 REPL 生命周期：
  - 创建和重置 session
  - 维护完整 `ConversationMessage` 历史
  - 读取用户输入
  - 分发 `/help`、`/reset`、`/exit`
  - 调用单轮运行器

- `InteractiveRuntimeRunner`
  负责把“已有消息历史 + 本轮新输入”送进现有 runtime 组件，拿回更新后的消息历史与最终回答。

- `ConsoleTraceObserver`
  负责把关键运行事件打印为确定性的终端摘要。

---

**消息历史模型**

交互模式不会每次都重新拼接一个“长 prompt 字符串”交给 `run` 子命令，而是维护真实的 `ConversationMessage` 历史。

生命周期如下：

1. 创建 session 后，先生成固定的 system / runtime context 消息前缀
2. 用户输入一条 prompt，追加一条新的 `USER` 消息
3. `AgentLoop` 继续在这份历史上执行：
   - 模型返回 `tool_calls`
   - 工具执行
   - 结果回注为 `TOOL`
   - 模型继续推理
4. 当模型返回最终回答时，把 `ASSISTANT` 结果留在历史中
5. 下一次用户输入会继续复用这份历史

`/reset` 是唯一显式清空历史的入口。

---

**链路摘要输出**

默认终端只打印摘要，不打印完整 request / response JSON。

摘要范围固定为：

- session 创建成功
- 当前用户输入
- 每个 step 的模型结果类型：
  - `tool_calls(n)`
  - `final`
- 每个工具的开始与结束摘要
- 最终回答
- 当前轮失败信息

摘要规则必须是确定性的，不能直接把完整工具输出全文刷到屏幕上。

建议输出形态：

- `[session] created session-xxx workspace=repo`
- `[user] 读取 README.md`
- `[step 1] model -> tool_calls(1)`
- `[tool] read_file path=README.md`
- `[tool:success] read_file 42 行`
- `[step 2] model -> final`
- `[assistant] ...`

---

**错误处理**

交互模式遵循显式失败原则：

- 缺少交互模式必需配置时，启动直接失败，不做降级
- 创建 session 失败时，交互模式直接退出
- 单轮运行失败时，只打印 `[error] ...`，不退出整个 REPL
- `/reset` 失败时，保留旧会话与旧历史，不偷偷切换到半初始化状态

---

**测试策略**

本次改动优先补 4 组测试：

1. `RepoPilotCliCommandTest`
   验证根命令启动交互入口，而不是打印骨架文本。

2. `InteractiveCliSessionTest`
   验证启动、普通输入、`/help`、`/reset`、`/exit` 的行为。

3. `InteractiveRuntimeRunnerTest`
   验证系统消息初始化、多轮历史复用、单轮执行后历史更新。

4. `ConsoleTraceObserverTest`
   验证 step/tool/final 的摘要输出格式。

---

**本次明确不做**

为了保持主链路聚焦，本次交互模式不包含：

- 流式 token 输出
- 多行编辑器
- 原始 HTTP JSON debug 输出
- 全屏 TUI
- trace 上报到 server
- server 侧持久化消息历史

这些能力都可以在交互模式主链路稳定后继续叠加。
