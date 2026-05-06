package com.mohsenzamni.mrreviewer.tool;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RepoToolTest {

    @TempDir
    Path tempDir;

    private RepoTool repoTool;

    @BeforeEach
    void setUp() throws IOException {
        AppConfig config = new AppConfig();
        config.setGit(new AppConfig.Git());
        config.getGit().setWorkingDir(tempDir.toString());
        repoTool = new RepoTool(config);

        // Create test files
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(tempDir.resolve("src/main/Foo.java"),
                "line1\nline2\nline3\nline4\nline5\n");
        Files.writeString(tempDir.resolve("README.md"), "Hello World\n");
    }

    // ── readFile ──────────────────────────────────────────────────────────────

    @Test
    void readFile_returnsFullContent_whenNoLineRange() {
        RepoTool.ReadFileResult result = repoTool.readFile("src/main/Foo.java", null, null);

        assertThat(result.content()).contains("line1");
        assertThat(result.content()).contains("line5");
        assertThat(result.totalLines()).isEqualTo(5);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void readFile_returnsSlice_whenLineRangeGiven() {
        RepoTool.ReadFileResult result = repoTool.readFile("src/main/Foo.java", 2, 4);

        assertThat(result.content()).contains("line2");
        assertThat(result.content()).contains("line3");
        assertThat(result.content()).contains("line4");
        assertThat(result.content()).doesNotContain("line1");
        assertThat(result.content()).doesNotContain("line5");
    }

    @Test
    void readFile_relativePath_isPreserved() {
        RepoTool.ReadFileResult result = repoTool.readFile("README.md", null, null);

        assertThat(result.relativePath()).isEqualTo("README.md");
        assertThat(result.content()).contains("Hello World");
    }

    @Test
    void readFile_throwsForNonExistentFile() {
        assertThatThrownBy(() -> repoTool.readFile("nonexistent.java", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("nonexistent.java");
    }

    // ── listDirectory ─────────────────────────────────────────────────────────

    @Test
    void listDirectory_listsFilesAndSubdirectories() {
        RepoTool.DirectoryListing listing = repoTool.listDirectory(".");

        assertThat(listing.directories()).contains("src");
        assertThat(listing.files()).contains("README.md");
    }

    @Test
    void listDirectory_listsSubdirectory() {
        RepoTool.DirectoryListing listing = repoTool.listDirectory("src/main");

        assertThat(listing.files()).contains("Foo.java");
        assertThat(listing.directories()).isEmpty();
    }

    @Test
    void listDirectory_throwsForNonExistentPath() {
        assertThatThrownBy(() -> repoTool.listDirectory("does/not/exist"))
                .isInstanceOf(RuntimeException.class);
    }

    // ── Path traversal prevention ─────────────────────────────────────────────

    @Test
    void readFile_rejectsPathTraversal() {
        assertThatThrownBy(() -> repoTool.readFile("../../etc/passwd", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the git working directory");
    }

    @Test
    void listDirectory_rejectsPathTraversal() {
        assertThatThrownBy(() -> repoTool.listDirectory("../outside"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes the git working directory");
    }
}
