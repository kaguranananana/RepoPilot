package com.repopilot.core.tool;

/**
 * 工具执行结果。
 * success 先只表达成功或失败，
 * 后续如果需要审批等待、后台任务句柄等能力，再向这个结果对象扩展。
 */
public record ToolExecutionResult(
        boolean success,
        String output
) {
}

