package com.repopilot.core.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.repopilot.core.model.ToolCall;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolCallLoopDetectorTest {

    @Test
    void shouldBuildStableKeyWithCanonicalArgumentOrder() {
        Map<String, String> firstArguments = new LinkedHashMap<>();
        firstArguments.put("path", "README.md");
        firstArguments.put("start", "1");

        Map<String, String> secondArguments = new LinkedHashMap<>();
        secondArguments.put("start", "1");
        secondArguments.put("path", "README.md");

        String firstKey = ToolCallLoopDetector.canonicalKey(new ToolCall("call-1", "read_file", firstArguments));
        String secondKey = ToolCallLoopDetector.canonicalKey(new ToolCall("call-2", "read_file", secondArguments));

        assertEquals(firstKey, secondKey);
        assertEquals("read_file|path=README.md|start=1", firstKey);
    }

    @Test
    void shouldDetectOnlyConsecutiveRepeatedToolCallKeys() {
        ToolCallLoopDetector detector = new ToolCallLoopDetector(3);

        ToolCallLoopDetectionResult firstRead = detector.record(
                new ToolCall("call-1", "read_file", Map.of("path", "README.md"))
        );
        ToolCallLoopDetectionResult secondRead = detector.record(
                new ToolCall("call-2", "read_file", Map.of("path", "README.md"))
        );
        ToolCallLoopDetectionResult grepBreak = detector.record(
                new ToolCall("call-3", "grep_files", Map.of("pattern", "AgentLoop"))
        );
        ToolCallLoopDetectionResult readAfterBreak = detector.record(
                new ToolCall("call-4", "read_file", Map.of("path", "README.md"))
        );

        assertFalse(firstRead.loopDetected());
        assertEquals(1, firstRead.repeatCount());
        assertFalse(secondRead.loopDetected());
        assertEquals(2, secondRead.repeatCount());
        assertFalse(grepBreak.loopDetected());
        assertEquals(1, grepBreak.repeatCount());
        assertFalse(readAfterBreak.loopDetected());
        assertEquals(1, readAfterBreak.repeatCount());
    }

    @Test
    void shouldDetectLoopWhenConsecutiveRepeatCountReachesThreshold() {
        ToolCallLoopDetector detector = new ToolCallLoopDetector(3);

        detector.record(new ToolCall("call-1", "read_file", Map.of("path", "README.md")));
        detector.record(new ToolCall("call-2", "read_file", Map.of("path", "README.md")));
        ToolCallLoopDetectionResult result = detector.record(
                new ToolCall("call-3", "read_file", Map.of("path", "README.md"))
        );

        assertTrue(result.loopDetected());
        assertEquals("read_file", result.toolName());
        assertEquals(3, result.repeatCount());
        assertEquals("path=README.md", result.argumentsSummary());
    }
}
