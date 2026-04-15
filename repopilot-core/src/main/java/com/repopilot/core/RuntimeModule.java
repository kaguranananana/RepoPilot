package com.repopilot.core;

/**
 * core 模块的占位入口。
 * 阶段 0 先用它把模块边界显式立起来，
 * 后续 AgentLoop、ToolRegistry、PermissionPolicy 都会落在这个模块里。
 */
public final class RuntimeModule {

    private RuntimeModule() {
    }

    public static String moduleName() {
        return "repopilot-core";
    }
}

