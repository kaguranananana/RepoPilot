package com.repopilot.core.tool.builtin;

import com.repopilot.core.skill.ActivatedSkillSet;
import com.repopilot.core.skill.SkillActivationResult;
import com.repopilot.core.skill.SkillActivationService;
import com.repopilot.core.skill.SkillLoader;
import com.repopilot.core.tool.ToolExecutionContext;
import com.repopilot.core.tool.ToolExecutionResult;
import com.repopilot.core.tool.ToolHandler;
import java.util.Map;
import java.util.Objects;

/**
 * 按名称激活单个 Skill 的内置工具。
 * 当前版本只覆盖最小主链路：
 * 1. 校验 name 参数
 * 2. 从当前历史重建已激活 Skill 集合
 * 3. 调用统一激活服务生成追加的 SYSTEM 消息
 */
public final class ActivateSkillTool implements ToolHandler {

    private final SkillActivationService activationService;

    public ActivateSkillTool(SkillLoader skillLoader) {
        this(new SkillActivationService(skillLoader));
    }

    ActivateSkillTool(SkillActivationService activationService) {
        this.activationService = Objects.requireNonNull(activationService, "activationService must not be null.");
    }

    @Override
    public ToolExecutionResult execute(Map<String, String> arguments) {
        // 旧的无上下文执行入口仍显式转到统一实现，
        // 这样 register/execute 的历史调用点不会绕开真正的激活逻辑。
        return execute(ToolExecutionContext.empty(), arguments);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, String> arguments) {
        String skillName = requireNonBlankArgument(arguments, "name");
        if (skillName == null) {
            return ToolExecutionResult.recoverableError("缺少必填参数: name");
        }

        try {
            // 这里先从当前消息历史恢复已激活集合，
            // 再把本次目标 Skill 交给统一激活服务，
            // 保证模型触发与用户触发最终复用同一条主链路。
            SkillActivationResult result = activationService.activate(
                    ActivatedSkillSet.fromMessages(context.messages()),
                    skillName
            );
            return ToolExecutionResult.success(result.output(), result.appendedMessages());
        } catch (IllegalArgumentException exception) {
            // 模型路径下缺失 Skill 属于可恢复错误，
            // 需要把真实错误文本回注给模型，而不是把整轮直接打断。
            return ToolExecutionResult.recoverableError(exception.getMessage());
        }
    }

    private String requireNonBlankArgument(Map<String, String> arguments, String key) {
        if (arguments == null) {
            return null;
        }

        String value = arguments.get(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
