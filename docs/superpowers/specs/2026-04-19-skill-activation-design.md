# Skill Activation Design

## 背景

当前 RepoPilot 已经具备 Skill 元信息扫描与摘要注入能力：

- 启动时扫描项目级与用户级 Skill，构建稳定排序的 `SkillIndex`
- 默认只把 `SkillSummary` 暴露给 system prompt
- 已具备按名称加载 `SKILL.md` 正文与附件的基础 API

但当前对话主链路仍缺失“真正按需激活 Skill”的能力。也就是说：

- 用户在对话里无法显式打开某个 Skill
- 模型虽然能看到 Skill 摘要，但无法通过显式协议请求加载 Skill 正文
- `allowed-tools` 仍停留在结构预留阶段，未进入运行时治理

本设计聚焦补齐“Skill 正文按需进入对话上下文”的最小主链路，不扩展到附件自动递进加载与工具交集治理。

## 目标

本设计要解决的问题是：

1. 支持用户在实际对话里显式触发 Skill 激活
2. 支持模型基于 Skill 摘要，通过显式协议请求激活 Skill
3. 让两种触发共用同一套激活逻辑，而不是维护两套分叉实现
4. 让激活后的 Skill 正文稳定进入后续对话上下文
5. 显式暴露真实错误，不引入隐式匹配或自动猜测

## 非目标

本次不做下面这些内容：

- 不支持普通自然语言中提到 Skill 名称就自动命中
- 不支持一次激活多个 Skill
- 不自动递进加载 Skill 附件
- 不在一期接入 `global policy ∩ skill allowed-tools`
- 不新增复杂的多 Agent / 调度器层

## 方案选择

本次采用方案 A：

- 用户触发走输入解析
- 模型触发走显式 `activate_skill` 工具调用
- 两条入口最终都复用同一个 `SkillActivationService`

不采用“根据模型文本暗示自动加载 Skill”的隐式方案，原因是它会引入不可解释的 heuristics，并破坏错误可见性、可测试性与可治理性。

## 用户触发规则

用户显式触发只支持两种明确语法：

- `/skill-name`
- `$skill-name`

支持下面两种输入形态：

1. 只有激活命令

```text
/debug
```

语义：只激活 `debug`，不继续发起模型推理。

2. 激活命令和任务同一轮输入

```text
/debug 修复这个测试
```

语义：

1. 先激活 `debug`
2. 再把 `修复这个测试` 作为本轮真实用户任务送进 agent

一期不支持一条输入里激活多个 Skill，例如：

```text
/debug /review 修复这个测试
```

这类输入直接报错，不做模糊拆分。

## 模型触发规则

模型不能靠自然语言暗示 Skill 需求，必须显式发起结构化请求。

本次采用内置工具协议：

```text
activate_skill(name="debug")
```

该工具只负责触发 Skill 激活，不负责附件自动展开，也不直接改变工具权限。

模型触发后的执行顺序是：

1. 模型看到 Skill 摘要
2. 模型显式请求 `activate_skill`
3. runtime 执行 `SkillActivationService`
4. Skill 正文进入消息历史
5. 下一轮模型继续基于已激活 Skill 工作

## 核心组件

### SkillActivationService

统一封装 Skill 激活业务逻辑，职责如下：

1. 查询 `SkillIndex`
2. 判断 Skill 是否已激活
3. 加载 `SKILL.md` 正文
4. 生成注入消息
5. 返回结构化激活结果

建议对外接口形式：

```java
SkillActivationResult activate(ActivatedSkillSet activatedSkillSet, String skillName)
```

### ActivatedSkillSet

表示当前 session 已激活的 Skill 状态，至少包含：

- 已激活 Skill 名称集合
- 对应注入消息

它的职责是：

- 判断重复激活
- 维护当前会话内 Skill 激活结果
- 在后续多轮对话中复用已激活 Skill

### ActivateSkillTool

新增内置工具 `activate_skill`，供模型显式请求加载 Skill。

建议 schema：

```json
{
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "要激活的 Skill 名称"
    }
  },
  "required": ["name"]
}
```

该工具内部不直接做复杂逻辑，而是委托 `SkillActivationService`。

## Skill 正文注入策略

激活后的 Skill 正文一期采用“独立 `SYSTEM` 消息”注入，不新增 `ACTIVATED_SKILL` 角色。

注入格式固定为：

```text
# Activated Skill
name: debug
source: project

<SKILL.md 正文>
```

采用 `SYSTEM` 消息的原因：

1. 当前模型适配层已支持 `SYSTEM` 消息
2. `ContextCompactor` 当前会保留所有 `SYSTEM` 消息
3. 现有代码改动最小
4. 能在一期先稳定跑通多轮生效主链路

这里显式区分两类上下文：

- 默认摘要：由 `SystemPromptBuilder` 放进稳定会话指令
- 激活正文：作为后追加的动态 `SYSTEM` 消息进入历史

这样不会把“摘要级提示”和“会话中动态激活的完整正文”混成一块。

## 运行时挂点

### 用户触发挂点

放在 CLI / interactive 输入进入 `AgentLoop` 之前。

处理流程：

1. 解析输入是否为 `/skill-name` 或 `$skill-name`
2. 调用 `SkillActivationService`
3. 把激活后的 `SYSTEM` 消息追加到历史
4. 如果命令后仍有剩余任务文本，则继续执行本轮推理
5. 如果没有剩余任务文本，则直接返回激活确认信息

### 模型触发挂点

通过 `activate_skill` 内置工具进入现有 `tool-calling` 主链路。

处理流程：

1. 模型返回 `ToolCallModelResponse`
2. `AgentLoop` 执行 `activate_skill`
3. 工具调用 `SkillActivationService`
4. 工具返回结构化成功/失败信息
5. 激活成功时，把对应 `SYSTEM` 消息追加到历史
6. 下一轮模型继续推理

这里要求 `AgentLoop` 支持“工具执行除返回 `TOOL` 消息外，还能追加额外消息”的扩展点。

## 错误处理与边界规则

### 1. 用户显式触发但 Skill 不存在

直接返回明确错误，不偷偷忽略，也不把整条输入降级成普通用户消息。

### 2. 模型触发但 Skill 不存在

`activate_skill` 返回 `RECOVERABLE_ERROR`，让下一轮模型看到真实错误后自行调整。

### 3. 重复激活

重复激活视为幂等成功：

- 不重复注入第二份 Skill 正文
- 返回“Skill 已激活”的成功结果

### 4. 只有激活命令，没有后续任务

只激活 Skill，不继续调用模型；直接返回一条确认消息。

### 5. 同一轮多个显式 Skill

一期不支持，直接返回错误。

### 6. 附件处理

本次不自动加载附件。后续如果 Skill 正文里再引用附件，由后续明确能力继续扩展。

## 测试设计

本次至少补以下测试：

### SkillActivationServiceTest

验证：

- 激活存在 Skill
- 激活缺失 Skill
- 重复激活
- 注入消息格式

### ActivateSkillToolTest

验证：

- 模型通过工具激活存在 Skill
- 工具返回缺失 Skill 的 `RECOVERABLE_ERROR`
- 重复激活不重复追加正文

### AgentLoopSkillActivationTest

验证：

- 模型先调用 `activate_skill`，下一轮能看到新增 Skill 正文
- 重复激活不会重复产生 `SYSTEM` 消息

### InteractiveRuntimeRunnerSkillActivationTest

验证：

- `/debug 修复这个测试` 会先激活 Skill，再继续处理剩余任务
- 只有 `/debug` 时只激活、不继续跑任务
- 缺失 Skill 会直接报错

### ContextCompactorSkillActivationTest

验证：

- 激活后的 Skill 正文作为 `SYSTEM` 消息在压缩后仍保留

## 验收标准

满足下面条件即可视为本次完成：

1. 用户显式触发和模型显式触发都能激活 Skill
2. 激活后的 Skill 正文会稳定进入后续对话上下文
3. 重复激活不会重复注入正文
4. 缺失 Skill、空任务文本和非法多 Skill 输入都有明确行为
5. 不引入普通自然语言匹配 Skill 的隐式 heuristics

## 风险与后续演进

一期采用 `SYSTEM` 消息注入会带来一个明确风险：

- 如果激活 Skill 数量持续增加，system 上下文会变长

但这仍然是当前最小可控方案。后续可演进方向包括：

1. 为激活 Skill 单独引入 `ACTIVATED_SKILL` 消息角色
2. 对已激活 Skill 做独立压缩策略
3. 接入 `allowed-tools` 交集治理
4. 支持附件的显式递进加载

## 当前阶段后续优先级

在 Skill 激活主链路已经打通的前提下，当前阶段最优先的后续项不是继续扩展激活语法，而是把 Skill 从“正文注入”推进到“运行时约束能力”：

1. 接入 `global policy ∩ skill allowed-tools` 交集治理。
2. 让 prompt 与 runtime 同时只暴露有效工具子集。
3. 把“Skill 激活前后工具边界变化”纳入离线评测任务集和指标统计。

这样后续简历表述就不再只是“支持 Skill 激活”，而是可以明确说成“设计并实现 Skill 驱动的工具子集约束”。

## 结论

本次设计通过“用户显式触发 + 模型显式工具调用”双入口，复用统一 `SkillActivationService`，把 Skill 激活从静态摘要能力推进到真实对话主链路。它保持了错误可见性、协议显式性和后续治理扩展空间，同时把一期改动范围控制在当前 runtime 结构可以承受的最小范围内。
