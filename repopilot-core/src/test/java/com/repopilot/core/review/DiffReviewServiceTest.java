package com.repopilot.core.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffReviewServiceTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void shouldSummarizeCreateOperationBeforeWrite() {
        DiffReviewService diffReviewService = new DiffReviewService(workspaceRoot);

        DiffReviewService.DiffReviewSummary summary = diffReviewService.review(
                "write_file",
                Map.of(
                        "path", "docs/notes.txt",
                        "content", "第一行\n第二行\n"
                )
        );

        assertEquals(DiffReviewService.ChangeType.CREATE, summary.changeType());
        assertEquals("docs/notes.txt", summary.displayPath());
        assertEquals(0, summary.beforeLineCount());
        assertEquals(2, summary.afterLineCount());
        assertTrue(summary.summary().contains("CREATE"));
    }

    @Test
    void shouldSummarizeModifyOperationWithLineCounts() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/notes.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "第一行\n第二行\n");

        DiffReviewService diffReviewService = new DiffReviewService(workspaceRoot);

        DiffReviewService.DiffReviewSummary summary = diffReviewService.review(
                "write_file",
                Map.of(
                        "path", "docs/notes.txt",
                        "content", "第一行\n第二行-已更新\n第三行\n"
                )
        );

        assertEquals(DiffReviewService.ChangeType.MODIFY, summary.changeType());
        assertEquals("docs/notes.txt", summary.displayPath());
        assertEquals(2, summary.beforeLineCount());
        assertEquals(3, summary.afterLineCount());
        assertTrue(summary.summary().contains("MODIFY"));
    }

    @Test
    void shouldSummarizeApplyPatchOperationWithoutWritingFile() throws Exception {
        Path targetFile = workspaceRoot.resolve("docs/notes.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "第一行\n第二行\n");

        DiffReviewService diffReviewService = new DiffReviewService(workspaceRoot);

        DiffReviewService.DiffReviewSummary summary = diffReviewService.review(
                "apply_patch",
                Map.of(
                        "path", "docs/notes.txt",
                        "patch", """
                                @@
                                 第一行
                                -第二行
                                +第二行-已更新
                                """
                )
        );

        assertEquals(DiffReviewService.ChangeType.MODIFY, summary.changeType());
        assertEquals("docs/notes.txt", summary.displayPath());
        assertEquals(2, summary.beforeLineCount());
        assertEquals(2, summary.afterLineCount());
        assertTrue(summary.summary().contains("DIFF_REVIEW"));
        assertTrue(summary.summary().contains("addedLineCount: 1"));
        assertTrue(summary.summary().contains("removedLineCount: 1"));
        assertEquals("第一行\n第二行\n", Files.readString(targetFile));
    }

    @Test
    void shouldExposeAttemptedOriginalLinesWhenApplyPatchPreviewContextMismatch() throws Exception {
        Path targetFile = workspaceRoot.resolve("target/real-e2e/Demo-manual.txt");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "name=RepoPilot\nstatus=draft\n");

        DiffReviewService diffReviewService = new DiffReviewService(workspaceRoot);

        DiffReviewService.DiffReviewFailure exception = assertThrows(
                DiffReviewService.DiffReviewFailure.class,
                () -> diffReviewService.review(
                        "apply_patch",
                        Map.of(
                                "path", "target/real-e2e/Demo-manual.txt",
                                "patch", """
                                        @@
                                         status=draft
                                        -status=draft
                                        +status=ready
                                        """
                        )
                )
        );

        assertTrue(exception.getMessage().contains("CONTEXT_MISMATCH"));
        assertTrue(exception.getMessage().contains("attemptedOriginalLines:"));
        assertTrue(exception.getMessage().contains("1| status=draft"));
        assertTrue(exception.getMessage().contains("2| status=draft"));
    }
}
