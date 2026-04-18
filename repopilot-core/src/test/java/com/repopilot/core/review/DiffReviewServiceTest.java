package com.repopilot.core.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
