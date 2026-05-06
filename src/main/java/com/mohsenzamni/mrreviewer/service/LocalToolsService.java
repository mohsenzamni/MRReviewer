package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Runs locally configured static-analysis tools in the git working directory and
 * returns their combined output so it can be injected into the LLM review prompt.
 *
 * <p>Design goals:
 * <ul>
 *   <li>All tools are executed as discrete argument lists via {@link ProcessBuilder} — never
 *       concatenated into a shell string, preventing command injection.</li>
 *   <li>Tool failures (non-zero exit, start failure, timeout) are <em>non-fatal</em>: a warning
 *       is logged and the tool's result is omitted, so one broken tool does not block the review.</li>
 *   <li>Non-zero exit is treated as "the tool ran and found issues" rather than an error, because
 *       tools like Checkstyle exit with code 1 when violations are found — which is exactly when
 *       their output is most valuable.</li>
 * </ul>
 */
@Service
public class LocalToolsService {

    private static final Logger log = LoggerFactory.getLogger(LocalToolsService.class);

    /**
     * The name and captured output of a single tool run.
     *
     * @param name   the tool name as configured in {@code app.local-tools.tools[*].name}
     * @param output combined stdout + stderr of the tool, possibly truncated
     */
    public record ToolResult(String name, String output) {}

    private final AppConfig config;

    public LocalToolsService(AppConfig config) {
        this.config = config;
    }

    /**
     * Runs all enabled tools in the git working directory and returns their results.
     * Returns an empty list when the feature is globally disabled or no tools are configured.
     *
     * @return ordered list of results for every tool that ran and produced non-empty output
     */
    public List<ToolResult> runAll() {
        AppConfig.LocalTools localToolsConfig = config.getLocalTools();
        if (!localToolsConfig.isEnabled()) {
            log.debug("Local tools are disabled — skipping");
            return List.of();
        }

        File workDir = new File(config.getGit().getWorkingDir()).getAbsoluteFile();
        List<ToolResult> results = new ArrayList<>();

        for (AppConfig.LocalTools.Tool tool : localToolsConfig.getTools()) {
            if (!tool.isEnabled()) {
                log.debug("Skipping disabled tool '{}'", tool.getName());
                continue;
            }
            if (tool.getCommand().isEmpty()) {
                log.warn("Tool '{}' has no command configured — skipping", tool.getName());
                continue;
            }

            log.info("Running local tool '{}': {}", tool.getName(), tool.getCommand());
            try {
                String output = runTool(workDir, tool);
                if (!output.isBlank()) {
                    results.add(new ToolResult(tool.getName(), output));
                    log.debug("Tool '{}' produced {} characters of output", tool.getName(), output.length());
                } else {
                    log.debug("Tool '{}' produced no output", tool.getName());
                }
            } catch (LocalToolException ex) {
                log.warn("Local tool '{}' could not be executed — skipping: {}", tool.getName(), ex.getMessage());
            }
        }

        return results;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Executes a single tool and returns its combined stdout + stderr, truncated to the
     * configured {@code max-output-lines} limit.
     *
     * @throws LocalToolException if the process cannot be started or times out
     */
    private String runTool(File workDir, AppConfig.LocalTools.Tool tool) {
        ProcessBuilder pb = new ProcessBuilder(tool.getCommand());
        pb.directory(workDir);
        pb.redirectErrorStream(true);  // merge stderr into stdout so all output is captured

        Process process;
        try {
            process = pb.start();
        } catch (IOException ex) {
            throw new LocalToolException("Failed to start tool process: " + ex.getMessage(), ex);
        }

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException ex) {
            process.destroyForcibly();
            throw new LocalToolException("Failed to read tool output: " + ex.getMessage(), ex);
        }

        int timeoutSeconds = config.getLocalTools().getTimeoutSeconds();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new LocalToolException("Tool process was interrupted", ex);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new LocalToolException(
                    "Tool process timed out after " + timeoutSeconds + "s");
        }

        // Non-zero exit is intentionally not treated as a hard failure — static-analysis
        // tools like Checkstyle exit 1 when they find violations, which is exactly the
        // output we want to forward to the LLM.
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.debug("Tool '{}' exited with code {} — including output", tool.getName(), exitCode);
        }

        return truncate(output, config.getLocalTools().getMaxOutputLines());
    }

    /**
     * Truncates {@code text} to at most {@code maxLines} lines, appending a note when truncated.
     */
    private static String truncate(String text, int maxLines) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> lines = text.lines().collect(Collectors.toList());
        if (lines.size() <= maxLines) {
            return text;
        }
        return lines.subList(0, maxLines).stream().collect(Collectors.joining("\n"))
                + "\n[... output truncated at " + maxLines + " lines ...]";
    }

    /** Internal exception used to signal that a tool could not be run (not a violation finding). */
    private static class LocalToolException extends RuntimeException {
        LocalToolException(String message, Throwable cause) { super(message, cause); }
        LocalToolException(String message) { super(message); }
    }
}
