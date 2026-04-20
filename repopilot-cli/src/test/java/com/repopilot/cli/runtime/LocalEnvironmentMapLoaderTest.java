package com.repopilot.cli.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalEnvironmentMapLoaderTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldLoadDotEnvLocalValuesFromWorkspaceRoot() throws Exception {
        Files.writeString(workspaceRoot.resolve(".env.local"), """
                # OpenAI 兼容模型本地配置
                REPOPILOT_MODEL_PROVIDER=openai-compatible
                OPENAI_COMPATIBLE_API_KEY=test-key
                OPENAI_COMPATIBLE_BASE_URL=https://gateway.example.com/v1
                OPENAI_COMPATIBLE_MODEL=kimi-k2.5
                """);

        Map<String, String> environment = LocalEnvironmentMapLoader.load(workspaceRoot, Map.of("JAVA_HOME", "/tmp/java"));

        assertEquals("openai-compatible", environment.get("REPOPILOT_MODEL_PROVIDER"));
        assertEquals("test-key", environment.get("OPENAI_COMPATIBLE_API_KEY"));
        assertEquals("https://gateway.example.com/v1", environment.get("OPENAI_COMPATIBLE_BASE_URL"));
        assertEquals("kimi-k2.5", environment.get("OPENAI_COMPATIBLE_MODEL"));
        assertEquals("/tmp/java", environment.get("JAVA_HOME"));
    }

    @Test
    void shouldLetProcessEnvironmentOverrideDotEnvLocalValues() throws Exception {
        Files.writeString(workspaceRoot.resolve(".env.local"), """
                REPOPILOT_MODEL_PROVIDER=bootstrap
                OPENAI_COMPATIBLE_API_KEY=file-key
                """);

        Map<String, String> environment = LocalEnvironmentMapLoader.load(workspaceRoot, Map.of(
                "REPOPILOT_MODEL_PROVIDER", "openai-compatible",
                "OPENAI_COMPATIBLE_API_KEY", "process-key"
        ));

        assertEquals("openai-compatible", environment.get("REPOPILOT_MODEL_PROVIDER"));
        assertEquals("process-key", environment.get("OPENAI_COMPATIBLE_API_KEY"));
    }

    @Test
    void shouldReturnProcessEnvironmentWhenDotEnvLocalDoesNotExist() {
        Map<String, String> environment = LocalEnvironmentMapLoader.load(workspaceRoot, Map.of(
                "REPOPILOT_MODEL_PROVIDER", "bootstrap"
        ));

        assertEquals(Map.of("REPOPILOT_MODEL_PROVIDER", "bootstrap"), environment);
    }

    @Test
    void shouldRejectMalformedDotEnvLocalLine() throws Exception {
        Files.writeString(workspaceRoot.resolve(".env.local"), """
                OPENAI_COMPATIBLE_API_KEY=test-key
                INVALID_LINE
                """);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> LocalEnvironmentMapLoader.load(workspaceRoot, Map.of())
        );

        assertEquals(".env.local 第 2 行格式非法: INVALID_LINE", exception.getMessage());
    }
}
