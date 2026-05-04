# Persistent Memory Design

## 背景

当前 RepoPilot 已经具备两类与“记忆”相关的能力：

- 会话内短期状态：`WorkingMemory`、`WorkingMemorySnapshot`、`ContextCompactor`
- 动态上下文注入：`SystemPromptBuilder`、`SkillLoader`、`SkillActivationService`

这意味着 RepoPilot 已经能解决“单次会话里不要忘事”和“按需把 Skill 正文放进上下文”这两个问题，但仍然缺一层跨会话、可审计、可复用的长期知识承载。

当前缺口主要体现在：

- 新会话会从零开始，用户长期偏好和仓库级稳定约定不会自动延续
- 已经确认过的工作流约束、项目入口、常用命令等信息需要反复重新解释
- 项目虽然已经有 `trace / plan / working_memory / context_summary`，但它们都不适合作为跨会话长期知识

本设计聚焦补齐“最小持久记忆系统”的一期主链路，让 RepoPilot 既有真实可运行的长期记忆能力，也能在面试中清楚讲出记忆分层、上下文治理和错误边界。

## 目标

本设计要解决的问题是：

1. 为 RepoPilot 增加一层跨会话持久记忆，但不破坏现有 short-term memory 与 trace 边界
2. 支持用户显式创建、查看、列出、删除长期记忆
3. 支持 runtime 在新任务开始前自动召回少量相关记忆
4. 让 recalled memory 只作为线索进入 prompt，而不是长期累加进历史
5. 保持 `fail-closed` 与真实错误暴露，不引入静默 fallback 或隐式 heuristics
6. 让整套能力既能现场 demo，也能沉淀成结构清晰、容易复述的架构亮点

## 非目标

一期不做下面这些内容：

- 不做自动提取记忆
- 不做团队共享记忆
- 不做向量库、embedding 检索或外部知识库接入
- 不让模型通过普通工具直接写入持久记忆
- 不把 `trace`、`plan`、`working_memory` 或 `context_summary` 直接复用为持久记忆
- 不把持久记忆同步到 `repopilot-server`
- 不做索引损坏后的自动修复、自动扫描补全或模糊兜底

## 方案选择

本次评估过三种范围：

1. 纯手动记忆
2. 手动记忆 + 自动召回
3. 手动记忆 + 自动召回 + 自动提取

本次采用方案 2：

- 用户显式管理记忆
- runtime 自动召回少量相关记忆
- 自动提取留到二期

不采用方案 3 的原因是：

- 自动提取会把问题从“记忆存取”升级成“记忆判断”，显著增加解释和验收难度
- 自动提取需要新增 stop hook、后台 selector、冲突规则和去重策略，超出一期面试展示主线
- 方案 2 已经能形成完整 demo，同时保留清晰的二期演进路径

## 核心原则

### 1. 记忆分层

RepoPilot 中与“记忆”相关的状态分为三层：

- `working_memory / context_summary`：会话内短期状态
- `trace / plan`：执行历史和过程证据
- `persistent memory`：跨会话长期有效、值得重复使用的稳定事实

一期新增的持久记忆不能和前两层混用。

### 2. 记忆只是线索，不是真相源

recalled memory 只能帮助模型少走弯路，不能直接当作当前仓库事实。凡是涉及：

- 文件路径
- 源码结构
- 命令可执行性
- 当前配置状态
- 运行结果

模型都必须继续通过 `read_file`、`grep_files`、`run_command` 等真实工具验证。

### 3. 文件化优先

持久记忆一期采用工作区内文件化存储，而不是数据库主存储。这样做的原因是：

- 更贴近本地 runtime 的真实读写语义
- 可审计、可 diff、可版本化
- 易于通过现有 CLI 直接展示和验证
- 避免把控制面 `server` 过早扩展成知识平台

### 4. 少而准

自动召回的原则固定为：

- 少而准优于多而杂
- 只召回少量真正相关的记忆
- 不能把所有持久记忆一次性塞进 prompt
- recalled memory 必须受同一份上下文预算约束

## 数据模型与目录结构

### 目录结构

一期固定在工作区下使用下面的目录：

```text
.repopilot/
  memory/
    MEMORY.md
    user/
      user-prefer-chinese-comments.md
    project/
      project-plan-execute-boundary.md
    feedback/
      feedback-dont-hide-real-errors.md
    reference/
      reference-local-postgres-port.md
```

说明：

- `MEMORY.md` 是索引，不承载长正文
- 单条记忆独立成文件，方便局部读取、删除和审计
- 记忆按类型分目录，避免单目录无限增长

### 记忆类型

一期只支持四类：

- `user`：用户长期偏好，例如输出语言、代码注释习惯
- `project`：仓库级长期约定，例如工作流边界、工程规则、入口命令
- `feedback`：用户明确给过的协作纠偏，例如“不要掩盖真实错误”
- `reference`：稳定参考指针，例如本地端口、文档位置、固定入口

### 单条记忆文件格式

每条记忆文件必须使用固定 front matter：

```yaml
---
id: project-plan-execute-boundary
type: project
title: Plan 与 Execute 必须分阶段
summary: 该仓库要求先只读取证，再进入修改与验证。
created_at: 2026-05-04T10:00:00Z
updated_at: 2026-05-04T10:00:00Z
tags:
  - workflow
  - runtime
---
```

正文只允许存放长期有效、跨会话值得复用的事实，不允许写入：

- 某次任务的临时步骤
- 某次调试过程
- 某次 trace 结果
- 会自然过期、且应以代码为准的瞬时状态

### `MEMORY.md` 索引职责

`MEMORY.md` 只做轻量入口索引，至少包含：

- id
- type
- title
- summary
- updated_at

它的职责是：

- 给自动召回提供统一候选入口
- 给用户提供人类可读的总览
- 避免 runtime 每轮都全量读取所有记忆正文

## 用户显式管理路径

### 设计原则

显式记忆管理不经过模型推理，而是直接由 CLI 解析并调用持久化组件执行。原因是：

- 写记忆属于高信任动作
- 一期要保证行为确定、可预测、可演示
- 避免把“记忆创建”变成模型自由发挥的副产品

### CLI 命令

一期建议在交互式 CLI 中增加：

- `/remember`
- `/memories`
- `/memory <id>`
- `/forget <id>`

命令语义如下：

#### 1. `/remember`

进入受控录入流程，收集：

- `type`
- `title`
- `summary`
- `body`
- 可选 `tags`

完成后：

1. 生成稳定 `id`
2. 写入单条记忆文件
3. 重写 `MEMORY.md`
4. 向用户返回明确成功信息

#### 2. `/memories`

读取并展示 `MEMORY.md` 的索引内容，用于快速浏览现有长期记忆。

#### 3. `/memory <id>`

按 id 读取单条正文，帮助用户检查记忆内容是否准确。

#### 4. `/forget <id>`

删除对应记忆文件并重写索引；删除失败必须直接暴露真实错误。

### 运行时挂点

用户显式管理路径放在 `InteractiveCliSession` 中，位置与当前 `/plan`、`/execute`、Skill 显式命令同级，不进入 `AgentLoop`。

推荐接入点：

- `InteractiveCliSession`
- `UserMemoryCommandParser`

不建议做成普通模型工具的原因是：

- 一期不需要“模型自己决定是否写记忆”
- 显式命令更容易做面试演示
- 可避免把工作区外写入或隐式记忆提取带入主链路

## 自动召回路径

### 设计原则

自动召回是一期的“智能部分”，但它只负责读，不负责写。其目标是：

- 根据当前任务挑选少量相关记忆
- 以临时上下文块的形式注入本轮 prompt
- 不在多轮历史中永久累加 recalled memory

### 触发时机

自动召回发生在每轮普通任务开始前：

1. 先剥离旧 prompt boundary
2. 读取 `MEMORY.md`
3. 选择相关记忆
4. 把 recalled memory 注入本轮动态边界
5. 再追加新的 `USER` 消息并进入 `AgentLoop`

交互式路径挂在：

- `DefaultInteractiveRuntimeRunner.rebuildPromptBoundary(...)`

单次运行路径挂在：

- `CliRuntimeBootstrap.DefaultCliRuntimeBootstrap.run(...)`

这样可以避免交互式和单次运行两条入口行为分叉。

### 候选选择方式

一期不做向量检索，也不做自由 heuristics。建议采用“小模型结构化选择器”：

1. 读取 `MEMORY.md` 索引
2. 把“当前用户输入 + 当前运行模式 + memory index 摘要”发送给无工具 selector
3. selector 只返回少量 memory id，最多 3 条
4. runtime 再按 id 读取正文并构建 recalled block

之所以采用 selector，而不是全量注入或简单关键词匹配，是因为：

- 全量注入会污染上下文预算
- 简单 heuristics 难以解释，且容易误召回
- RepoPilot 已经有 `ModelStructuredContextSummaryGenerator` 这类“受限模型只输出结构化结果”的先例

### recalled memory 注入格式

一期建议把所有召回结果聚合成一条临时 `SYSTEM` 消息：

```text
# Recalled Memories
- id: project-plan-execute-boundary
  type: project
  updated_at: 2026-05-04T10:00:00Z
  summary: 该仓库要求先只读取证，再进入修改与验证。
  content:
  ...
```

不建议一条记忆一个 `SYSTEM` 消息，原因是：

- 更难做边界剥离
- 更容易在历史中累积
- 不利于控制 prompt 体积

### 为什么 recalled memory 必须是临时边界消息

当前 `ContextCompactor` 会保留所有 `SYSTEM` 消息。如果 recalled memory 直接追加进会话历史，会有两个问题：

1. 每轮都会持续累积 recalled memory
2. 持久记忆会反过来变成新的上下文膨胀源

因此一期必须把 recalled memory 视为“本轮动态边界的一部分”，并在下一轮重建边界时显式移除旧 recalled block，再按当前任务重新计算。

### recalled memory 与短期记忆的边界

一期不让 recalled memory 自动写回：

- `working_memory`
- `context_summary`
- `trace`

因为 recalled memory 只是输入线索，而不是本轮已经验证过的执行事实。

## 组件拆分

### `repopilot-core`

建议新增 `memory` 子包，并至少包含：

#### `MemoryType`

固定四类枚举：

- `USER`
- `PROJECT`
- `FEEDBACK`
- `REFERENCE`

#### `MemoryRecord`

表示单条完整记忆，字段包括：

- `id`
- `type`
- `title`
- `summary`
- `body`
- `createdAt`
- `updatedAt`
- `tags`

#### `MemoryIndexEntry`

表示索引中的轻量条目，仅保留自动召回需要的字段。

#### `PersistentMemoryStore`

抽象持久记忆存储接口，定义：

- `save`
- `get`
- `list`
- `delete`
- `loadIndex`

#### `FilePersistentMemoryStore`

工作区文件版实现。职责：

1. 管理 `.repopilot/memory/` 目录
2. 读写单条记忆文件
3. 重写 `MEMORY.md`
4. 做路径边界和文件格式校验

#### `MemoryIndexRenderer`

专门负责索引渲染和解析，避免 `FilePersistentMemoryStore` 同时承担 IO 与 Markdown 格式逻辑。

#### `MemoryRecallService`

自动召回编排器。输入：

- 当前 prompt
- 当前 run mode
- memory index

输出：

- 当前轮次应注入的 recalled memory 集合

#### `MemoryRecallSelector`

召回选择器接口。

#### `ModelMemoryRecallSelector`

默认实现。行为约束：

- 只输出结构化 JSON
- 不允许工具调用
- 最多返回 3 个 id
- 结果中不能出现索引不存在的 id

#### `RecalledMemoryPromptRenderer`

负责把召回结果渲染成临时 `SYSTEM` 消息。

### `repopilot-cli`

建议新增或修改：

#### `UserMemoryCommandParser`

解析 `/remember`、`/memories`、`/memory <id>`、`/forget <id>`。

#### `InteractiveCliSession`

新增显式记忆命令处理分支，直接调用 `PersistentMemoryStore`，不经过模型。

#### `DefaultInteractiveRuntimeRunner`

在 `rebuildPromptBoundary(...)` 前后接入自动召回逻辑，并负责剥离旧 recalled block。

#### `CliRuntimeBootstrap`

单次运行入口复用同一套 recall 逻辑，避免行为分叉。

## Prompt 与运行时约束

### System Prompt 约束

建议在 `SystemPromptBuilder` 中增加稳定规则：

- recalled memory 是历史线索，不是真相源
- 涉及文件、代码、命令和当前状态时，必须重新用工具验证
- 未验证前，不得把 recalled memory 当作当前仓库事实输出

### 权限边界

一期不把记忆管理注册为普通模型工具，因此：

- `WorkspacePermissionPolicy` 不需要接入新的 memory tool 权限分支
- 显式记忆管理由 CLI 直接调用本地组件完成

这样可以保持：

- agent runtime 工具治理边界不被首版记忆功能扰动
- 一期能力更容易解释和测试

## 错误处理与边界规则

### 1. 索引或记忆文件格式非法

直接失败，不静默跳过，也不自动修复。

### 2. 记忆 id 重复

直接失败，拒绝写入或拒绝加载。

### 3. selector 输出非法

包括：

- 非法 JSON
- 返回未知 id
- 返回超过上限的 id 数量

都应直接暴露错误，不偷偷降级成“那就不召回”。

### 4. `/forget` 删除失败

必须明确报错，不能伪装成删除成功。

### 5. 路径越界

所有记忆文件路径必须限定在 `.repopilot/memory/` 目录下；绝对路径、路径遍历或符号链接越界都应失败。

### 6. recalled memory 过期风险

一期不做自动过期清理，但每条 recalled memory 都应暴露 `updated_at`，帮助模型识别这是历史知识而非即时状态。

## 测试设计

一期至少补下面几类测试。

### `FilePersistentMemoryStoreTest`

验证：

- 保存记忆
- 读取记忆
- 列出索引
- 删除记忆
- 重写索引
- 路径越界拒绝
- front matter 缺字段报错

### `MemoryIndexRendererTest`

验证：

- `MEMORY.md` 渲染稳定
- `MEMORY.md` 解析稳定
- 重复 id 报错
- 非法格式报错

### `ModelMemoryRecallSelectorTest`

验证：

- 只接受结构化 JSON
- 非法 JSON 报错
- 未知 id 报错
- 超过 3 条报错
- 工具调用响应报错

### `MemoryRecallServiceTest`

验证：

- 空索引返回空召回
- 非空索引可返回有限条目
- 返回条目与索引一致

### 运行时集成测试

建议新增：

- `DefaultInteractiveRuntimeRunnerMemoryRecallTest`
- `InteractiveCliSessionMemoryCommandTest`
- `CliRuntimeBootstrapMemoryRecallTest`

重点验证：

- recalled block 每轮先移除再重建
- recalled memory 不会无限累加
- 显式命令不经过模型也能工作
- 单次运行与交互式运行行为一致

## 验收标准

一期完成后，至少要满足下面 6 条：

1. 用户可以通过 CLI 显式创建四类持久记忆。
2. 持久记忆以独立 Markdown 文件保存在 `.repopilot/memory/` 下，并维护稳定 `MEMORY.md` 索引。
3. 用户可以列出、查看、删除指定记忆。
4. 普通任务开始前，runtime 能自动召回最多 3 条相关记忆，并以临时 `SYSTEM` 块注入。
5. recalled memory 不会在多轮历史中无限累积；每轮都会先移除旧 recalled block，再按当前任务重算。
6. 至少有一条可复跑 demo，证明“写入记忆 -> 新任务自动召回 -> 模型据此少走弯路”。

## 面试展示建议

推荐用下面的最小脚本演示：

1. 展示 `.repopilot/memory/` 目录和 `MEMORY.md`
2. 使用 `/remember` 写入一条 `project` 记忆
3. 发起一个相关任务
4. 展示当前请求中出现 `# Recalled Memories`
5. 说明模型仍需继续 `read_file / grep_files` 验证
6. 使用 `/forget <id>` 删除记忆并再次验证不再召回

演示时重点强调四点：

1. 记忆分层：短期会话状态与长期记忆分离
2. 可审计：长期记忆是工作区文件，不是黑盒数据库
3. 按需注入：默认只看索引，相关时才召回正文
4. 真实验证：记忆不是事实源，使用前必须重新取证

## 二期预留

后续可以在不破坏一期边界的前提下继续扩展：

- stop hook / post-run 自动提取记忆
- remember / forget 的受限工具化
- `repopilot-server` 侧同步与审计
- team memory
- 过期检测和冲突治理

但这些都不进入一期验收范围。

## 结论

一期的最优解不是“把 Claude Code 全套记忆系统复刻过来”，而是沿用 RepoPilot 已有的设计语言，补上一层最小但完整的持久记忆系统：

- 显式写入
- 文件化存储
- 索引与正文分离
- 自动召回但不自动提取
- recalled memory 作为临时动态边界消息注入
- 继续坚持 `fail-closed` 和真实工具验证

这样可以用最小新增复杂度，同时拿到真实可运行功能、清晰的架构故事和稳定的面试展示路径。
