package com.repopilot.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void shouldExecuteRegisteredTool() {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        ToolExecutionResult result = toolRegistry.execute("echo", Map.of("text", "hello"));

        assertEquals(ToolExecutionResult.Status.SUCCESS, result.status());
        assertEquals("hello", result.output());
        assertEquals(1, toolRegistry.list().size());
    }

    @Test
    void shouldRejectUnknownToolExecution() {
        ToolRegistry toolRegistry = new ToolRegistry();

        assertThrows(ToolNotFoundException.class, () -> toolRegistry.execute("missing", Map.of()));
    }

    @Test
    void shouldAllowListingToolsWhileSlowToolExecutes() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        toolRegistry.register(
                "slow",
                "慢工具",
                arguments -> {
                    // 先显式告诉测试线程“handler 已经真正开始执行”，
                    // 这样后续发起的 list() 调用才能稳定命中当前的锁粒度问题。
                    handlerStarted.countDown();
                    awaitLatch(releaseHandler);
                    return ToolExecutionResult.success("done");
                }
        );
        toolRegistry.register("echo", "回显输入文本", arguments -> ToolExecutionResult.success(arguments.get("text")));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<ToolExecutionResult> executionFuture =
                    executorService.submit(() -> toolRegistry.execute("slow", Map.of()));

            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));

            Future<List<String>> listFuture = executorService.submit(() ->
                    toolRegistry.list().stream().map(ToolDefinition::name).toList()
            );

            List<String> toolNames = assertDoesNotThrow(() -> listFuture.get(300, TimeUnit.MILLISECONDS));
            assertEquals(List.of("slow", "echo"), toolNames);

            releaseHandler.countDown();
            assertEquals(ToolExecutionResult.Status.SUCCESS, executionFuture.get(1, TimeUnit.SECONDS).status());
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void shouldAllowDefinitionLookupWhileSlowToolExecutes() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        toolRegistry.register(
                "slow",
                "慢工具",
                arguments -> {
                    // 这里同样先占住 handler，
                    // 让并发的 requireDefinition() 调用暴露是否被 execute() 粗粒度串行化。
                    handlerStarted.countDown();
                    awaitLatch(releaseHandler);
                    return ToolExecutionResult.success("done");
                }
        );

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        try {
            Future<ToolExecutionResult> executionFuture =
                    executorService.submit(() -> toolRegistry.execute("slow", Map.of()));

            assertTrue(handlerStarted.await(1, TimeUnit.SECONDS));

            Future<ToolDefinition> definitionFuture =
                    executorService.submit(() -> toolRegistry.requireDefinition("slow"));

            ToolDefinition toolDefinition =
                    assertDoesNotThrow(() -> definitionFuture.get(300, TimeUnit.MILLISECONDS));
            assertEquals("slow", toolDefinition.name());

            releaseHandler.countDown();
            assertEquals(ToolExecutionResult.Status.SUCCESS, executionFuture.get(1, TimeUnit.SECONDS).status());
        } finally {
            executorService.shutdownNow();
        }
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试线程被中断", exception);
        }
    }
}
