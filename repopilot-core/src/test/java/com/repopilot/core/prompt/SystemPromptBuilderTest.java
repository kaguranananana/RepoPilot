package com.repopilot.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.tool.ToolDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemPromptBuilderTest {

    @Test
    void shouldSeparateStaticConstitutionDynamicPolicyAndRuntimeContext() {
        SystemPromptBuilder builder = new SystemPromptBuilder();

        SystemPromptBoundary boundary = builder.build(new DynamicPromptContext(
                "你正在继续一个已存在的修复任务。",
                "workspaceId=demo-workspace, repo=RepoPilot",
                List.of("brainstorming: 先做设计确认", "test-driven-development: 先写失败测试"),
                "剩余预算 8 步，请优先调用低成本工具。",
                List.of(
                        new ToolDefinition("read_file", "读取单个文件"),
                        new ToolDefinition("grep_files", "按模式搜索文件")
                ),
                Map.of(
                        "session_id", "session-42",
                        "current_time", "2026-04-16T10:00:00Z"
                )
        ));

        assertTrue(boundary.staticConstitution().contains("# 静态宪法"));
        assertTrue(boundary.dynamicPolicy().contains("# 动态政策"));
        assertTrue(boundary.dynamicPolicy().contains("你正在继续一个已存在的修复任务。"));
        assertTrue(boundary.dynamicPolicy().contains("workspaceId=demo-workspace, repo=RepoPilot"));
        assertTrue(boundary.dynamicPolicy().contains("brainstorming: 先做设计确认"));
        assertTrue(boundary.dynamicPolicy().contains("剩余预算 8 步，请优先调用低成本工具。"));
        assertTrue(boundary.dynamicPolicy().contains("read_file: 读取单个文件"));
        assertTrue(boundary.dynamicPolicy().contains("grep_files: 按模式搜索文件"));

        assertFalse(boundary.staticConstitution().contains("demo-workspace"));
        assertFalse(boundary.systemPrompt().contains("session-42"));
        assertTrue(boundary.runtimeContextBlock().contains("session_id: session-42"));
        assertTrue(boundary.runtimeContextBlock().contains("current_time: 2026-04-16T10:00:00Z"));
    }

    @Test
    void shouldKeepStaticConstitutionStableWhenDynamicContextChanges() {
        SystemPromptBuilder builder = new SystemPromptBuilder();

        SystemPromptBoundary firstBoundary = builder.build(new DynamicPromptContext(
                "任务 A",
                "workspace-A",
                List.of("skill-A"),
                "预算 A",
                List.of(new ToolDefinition("read_file", "读取文件")),
                Map.of("session_id", "session-A")
        ));
        SystemPromptBoundary secondBoundary = builder.build(new DynamicPromptContext(
                "任务 B",
                "workspace-B",
                List.of("skill-B"),
                "预算 B",
                List.of(new ToolDefinition("run_command", "执行命令")),
                Map.of("session_id", "session-B")
        ));

        assertEquals(firstBoundary.staticConstitution(), secondBoundary.staticConstitution());
        assertFalse(firstBoundary.dynamicPolicy().equals(secondBoundary.dynamicPolicy()));
        assertFalse(firstBoundary.runtimeContextBlock().equals(secondBoundary.runtimeContextBlock()));
    }

    @Test
    void shouldRenderPlaceholderWhenDynamicPolicyIsEmpty() {
        SystemPromptBuilder builder = new SystemPromptBuilder();

        SystemPromptBoundary boundary = builder.build(new DynamicPromptContext(
                null,
                null,
                List.of(),
                null,
                List.of(),
                Map.of()
        ));

        assertTrue(boundary.dynamicPolicy().contains("当前没有额外动态政策。"));
        assertTrue(boundary.runtimeContextBlock().isBlank());
    }
}
