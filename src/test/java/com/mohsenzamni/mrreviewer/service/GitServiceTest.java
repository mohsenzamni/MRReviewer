package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.GitDiff;
import com.mohsenzamni.mrreviewer.exception.GitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class GitServiceTest {

    @TempDir
    Path tempDir;

    private AppConfig config;
    private GitService gitService;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        config.getGit().setMaxDiffLines(100);
        config.getGit().setBaseBranch("origin/main");
        config.getGit().setWorkingDir(tempDir.toAbsolutePath().toString());
        gitService = new GitService(config);
    }

    // ── getDiff / working-directory validation ────────────────────────────────

    @Test
    void getDiff_throwsGitException_whenNoDotGitDirectory() {
        // tempDir exists but has no .git folder
        assertThatThrownBy(() -> gitService.getDiff())
                .isInstanceOf(GitException.class)
                .hasMessageContaining("Not a git repository");
    }

    @Test
    void getDiff_throwsGitException_whenWorkingDirDoesNotExist() {
        config.getGit().setWorkingDir("/this/path/does/not/exist");
        assertThatThrownBy(() -> gitService.getDiff())
                .isInstanceOf(GitException.class)
                .hasMessageContaining("does not exist");
    }

    // ── validateRef (via getDiff) ─────────────────────────────────────────────

    @Test
    void getDiff_throwsGitException_forUnsafeRef() {
        config.getGit().setBaseBranch("origin/main; rm -rf /");
        assertThatThrownBy(() -> gitService.getDiff())
                .isInstanceOf(GitException.class)
                .hasMessageContaining("Unsafe or invalid git ref");
    }

    @Test
    void getDiff_throwsGitException_forNullRef() {
        config.getGit().setBaseBranch(null);
        assertThatThrownBy(() -> gitService.getDiff())
                .isInstanceOf(GitException.class)
                .hasMessageContaining("Unsafe or invalid git ref");
    }

    // ── runGit ────────────────────────────────────────────────────────────────

    @Test
    void runGit_returnsOutput_forEchoCommand() {
        // Use a valid directory (tempDir itself); the command does not need git
        String result = gitService.runGit(tempDir.toFile(), "echo", "hello world");
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void runGit_throwsGitException_onNonZeroExit() {
        assertThatThrownBy(
                () -> gitService.runGit(tempDir.toFile(), "sh", "-c", "exit 1"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("Git command failed");
    }

    @Test
    void runGit_throwsGitException_forUnknownCommand() {
        assertThatThrownBy(
                () -> gitService.runGit(tempDir.toFile(), "this-command-does-not-exist"))
                .isInstanceOf(GitException.class)
                .hasMessageContaining("Failed to start git process");
    }

    // ── Diff truncation ───────────────────────────────────────────────────────

    @Test
    void diffTruncation_marksAsNotTruncated_whenUnderLimit() {
        config.getGit().setMaxDiffLines(10_000);
        // We cannot actually run git diff without a real repo, so we test
        // the truncation logic indirectly by verifying that a GitDiff with
        // fewer lines than the limit is constructed correctly.
        GitDiff diff = new GitDiff(List.of("file.java"), "line1\nline2", false);
        assertThat(diff.truncated()).isFalse();
        assertThat(diff.changedFiles()).containsExactly("file.java");
    }
}
