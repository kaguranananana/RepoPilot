package com.repopilot.cli.interactive;

import com.repopilot.core.agent.AgentRunMode;
import java.util.Objects;

/**
 * 交互式 CLI 当前模式。
 * 它只负责终端命令语义，真实运行时约束由 AgentRunMode 承担。
 */
public enum InteractionMode {
    PLAN(AgentRunMode.PLAN, "PLAN", "只读计划模式，只能搜索和读取文件。"),
    EXECUTE(AgentRunMode.EXECUTE, "EXECUTE", "执行模式，可执行修改，并仍受治理和审批边界约束。");

    private final AgentRunMode agentRunMode;
    private final String label;
    private final String description;

    InteractionMode(AgentRunMode agentRunMode, String label, String description) {
        this.agentRunMode = Objects.requireNonNull(agentRunMode, "agentRunMode must not be null.");
        this.label = Objects.requireNonNull(label, "label must not be null.");
        this.description = Objects.requireNonNull(description, "description must not be null.");
    }

    public AgentRunMode agentRunMode() {
        return agentRunMode;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
