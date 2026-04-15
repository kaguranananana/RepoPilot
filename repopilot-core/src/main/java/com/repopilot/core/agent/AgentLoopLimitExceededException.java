package com.repopilot.core.agent;

/**
 * 超过最大步骤数时抛出的保护性异常。
 * 它是防止模型陷入工具循环的第一层硬兜底。
 */
public class AgentLoopLimitExceededException extends RuntimeException {

    public AgentLoopLimitExceededException(int maxSteps) {
        super("Agent loop exceeded max steps: " + maxSteps);
    }
}

