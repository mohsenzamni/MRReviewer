package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalToolsServiceTest {

    @TempDir
    Path tempDir;

    private AppConfig config;
    private LocalToolsService service;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        config.getGit().setWorkingDir(tempDir.toAbsolutePath().toString());
        config.getLocalTools().setEnabled(true);
        config.getLocalTools().setTimeoutSeconds(30);
        service = new LocalToolsService(config);
    }

    // ── Master switch ─────────────────────────────────────────────────────────

    @Test
    void runAll_returnsEmpty_whenGloballyDisabled() {
        config.getLocalTools().setEnabled(false);
        config.getLocalTools().setTools(List.of(enabledEchoTool("echo-tool", "hello")));

        assertThat(service.runAll()).isEmpty();
    }

    // ── Per-tool enabled flag ─────────────────────────────────────────────────

    @Test
    void runAll_skipsDisabledTool() {
        AppConfig.LocalTools.Tool tool = echoTool("skipped", "should not appear");
        tool.setEnabled(false);
        config.getLocalTools().setTools(List.of(tool));

        assertThat(service.runAll()).isEmpty();
    }

    @Test
    void runAll_skipsToolWithNoCommand() {
        AppConfig.LocalTools.Tool tool = new AppConfig.LocalTools.Tool();
        tool.setName("empty-cmd");
        tool.setEnabled(true);
        tool.setCommand(List.of());
        config.getLocalTools().setTools(List.of(tool));

        assertThat(service.runAll()).isEmpty();
    }

    // ── Successful tool run ───────────────────────────────────────────────────

    @Test
    void runAll_capturesOutput_forSuccessfulTool() {
        config.getLocalTools().setTools(List.of(enabledEchoTool("my-tool", "hello from tool")));

        List<LocalToolsService.ToolResult> results = service.runAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("my-tool");
        assertThat(results.get(0).output()).contains("hello from tool");
    }

    @Test
    void runAll_omitsResult_whenToolProducesNoOutput() {
        // "true" command exits 0 but produces no output
        AppConfig.LocalTools.Tool tool = new AppConfig.LocalTools.Tool();
        tool.setName("silent");
        tool.setEnabled(true);
        tool.setCommand(List.of("sh", "-c", "true"));
        config.getLocalTools().setTools(List.of(tool));

        assertThat(service.runAll()).isEmpty();
    }

    // ── Non-zero exit — still captures output ─────────────────────────────────

    @Test
    void runAll_capturesOutput_evenWhenToolExitsNonZero() {
        // Simulates a linter that finds violations (exits 1 but produces output)
        AppConfig.LocalTools.Tool tool = new AppConfig.LocalTools.Tool();
        tool.setName("linter");
        tool.setEnabled(true);
        tool.setCommand(List.of("sh", "-c", "echo '[ERROR] violation found'; exit 1"));
        config.getLocalTools().setTools(List.of(tool));

        List<LocalToolsService.ToolResult> results = service.runAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).output()).contains("violation found");
    }

    // ── Non-fatal failure handling ────────────────────────────────────────────

    @Test
    void runAll_continuesAfterToolStartFailure_andReturnsRemainingResults() {
        AppConfig.LocalTools.Tool failingTool = new AppConfig.LocalTools.Tool();
        failingTool.setName("failing");
        failingTool.setEnabled(true);
        failingTool.setCommand(List.of("this-command-does-not-exist-anywhere"));

        config.getLocalTools().setTools(List.of(
                failingTool,
                enabledEchoTool("success-tool", "still runs")));

        List<LocalToolsService.ToolResult> results = service.runAll();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("success-tool");
        assertThat(results.get(0).output()).contains("still runs");
    }

    // ── Multiple tools ────────────────────────────────────────────────────────

    @Test
    void runAll_runsMultipleEnabledTools_inOrder() {
        config.getLocalTools().setTools(List.of(
                enabledEchoTool("tool-a", "output-a"),
                enabledEchoTool("tool-b", "output-b")));

        List<LocalToolsService.ToolResult> results = service.runAll();

        assertThat(results).hasSize(2);
        assertThat(results.get(0).name()).isEqualTo("tool-a");
        assertThat(results.get(1).name()).isEqualTo("tool-b");
    }

    // ── Output truncation ─────────────────────────────────────────────────────

    @Test
    void runAll_truncatesOutputAtMaxOutputLines() {
        config.getLocalTools().setMaxOutputLines(3);
        // Generate 10 lines of output
        AppConfig.LocalTools.Tool tool = new AppConfig.LocalTools.Tool();
        tool.setName("verbose");
        tool.setEnabled(true);
        tool.setCommand(List.of("sh", "-c",
                "for i in 1 2 3 4 5 6 7 8 9 10; do echo \"line $i\"; done"));
        config.getLocalTools().setTools(List.of(tool));

        List<LocalToolsService.ToolResult> results = service.runAll();

        assertThat(results).hasSize(1);
        String output = results.get(0).output();
        assertThat(output).contains("line 1").contains("line 3");
        assertThat(output).doesNotContain("line 4");
        assertThat(output).contains("truncated");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AppConfig.LocalTools.Tool enabledEchoTool(String name, String text) {
        AppConfig.LocalTools.Tool tool = echoTool(name, text);
        tool.setEnabled(true);
        return tool;
    }

    private static AppConfig.LocalTools.Tool echoTool(String name, String text) {
        AppConfig.LocalTools.Tool tool = new AppConfig.LocalTools.Tool();
        tool.setName(name);
        tool.setCommand(List.of("echo", text));
        return tool;
    }
}
