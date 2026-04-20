package com.repopilot.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchApplyServiceTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldApplySingleExactHunkToExistingFile() throws Exception {
        Path targetFile = workspaceRoot.resolve("src/Demo.java");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, """
                class Demo {
                    String greeting() {
                        return "hello";
                    }
                }
                """);
        PatchApplyService service = new PatchApplyService(workspaceRoot);

        PatchApplyResult result = service.apply(new PatchApplyRequest(
                "src/Demo.java",
                """
                        @@
                             String greeting() {
                        -        return "hello";
                        +        return "hello from patch";
                             }
                        """
        ));

        assertEquals(PatchApplyResult.ChangeType.MODIFY, result.changeType());
        assertEquals("src/Demo.java", result.displayPath());
        assertEquals(5, result.beforeLineCount());
        assertEquals(5, result.afterLineCount());
        assertEquals(1, result.addedLineCount());
        assertEquals(1, result.removedLineCount());
        assertEquals("""
                class Demo {
                    String greeting() {
                        return "hello from patch";
                    }
                }
                """, Files.readString(targetFile));
        assertTrue(result.summary().contains("PATCH_APPLY"));
    }

    @Test
    void shouldExposeContextMismatchAndKeepOriginalFile() throws Exception {
        Path targetFile = workspaceRoot.resolve("src/Demo.java");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "class Demo {\n}\n");
        PatchApplyService service = new PatchApplyService(workspaceRoot);

        PatchApplyService.PatchApplyException exception = assertThrows(
                PatchApplyService.PatchApplyException.class,
                () -> service.apply(new PatchApplyRequest(
                        "src/Demo.java",
                        """
                                @@
                                -missing
                                +replacement
                                """
                ))
        );

        assertEquals(PatchApplyService.ErrorType.CONTEXT_MISMATCH, exception.errorType());
        assertTrue(exception.getMessage().contains("上下文不匹配"));
        assertEquals("class Demo {\n}\n", Files.readString(targetFile));
    }

    @Test
    void shouldExposeMissingTargetFile() {
        PatchApplyService service = new PatchApplyService(workspaceRoot);

        PatchApplyService.PatchApplyException exception = assertThrows(
                PatchApplyService.PatchApplyException.class,
                () -> service.apply(new PatchApplyRequest(
                        "src/Missing.java",
                        """
                                @@
                                -old
                                +new
                                """
                ))
        );

        assertEquals(PatchApplyService.ErrorType.TARGET_FILE_NOT_FOUND, exception.errorType());
        assertTrue(exception.getMessage().contains("目标文件不存在"));
    }

    @Test
    void shouldExposeInvalidPatchFormat() throws Exception {
        Path targetFile = workspaceRoot.resolve("src/Demo.java");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "class Demo {\n}\n");
        PatchApplyService service = new PatchApplyService(workspaceRoot);

        PatchApplyService.PatchApplyException exception = assertThrows(
                PatchApplyService.PatchApplyException.class,
                () -> service.apply(new PatchApplyRequest(
                        "src/Demo.java",
                        """
                                -old
                                +new
                                """
                ))
        );

        assertEquals(PatchApplyService.ErrorType.INVALID_PATCH_FORMAT, exception.errorType());
        assertTrue(exception.getMessage().contains("补丁格式非法"));
    }
}
