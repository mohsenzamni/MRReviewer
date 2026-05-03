package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.GitDiff;
import com.mohsenzamni.mrreviewer.exception.GitException;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Executes local git commands safely via {@link ProcessBuilder}.
 *
 * <p>Security notes:
 * <ul>
 *   <li>Commands are always passed as discrete argument lists — never concatenated into a shell string.</li>
 *   <li>The working directory is validated before use.</li>
 *   <li>A timeout is enforced to prevent hanging processes.</li>
 * </ul>
 */
@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    /** Maximum time (seconds) a git sub-process is allowed to run. */
    private static final int PROCESS_TIMEOUT_SECONDS = 60;

    /**
     * Allowlist pattern for the git base-branch argument.
     * Accepts {@code origin/main}, {@code origin/master}, {@code refs/remotes/origin/main}, etc.
     * Rejects anything that could be used for shell injection.
     */
    private static final Pattern SAFE_REF_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_./@-]{1,200}$");

    private final AppConfig config;

    public GitService(AppConfig config) {
        this.config = config;
    }

    /**
     * Runs {@code git diff <baseBranch>...HEAD} in the configured working directory.
     *
     * @return diff result, possibly truncated
     * @throws GitException if git is unavailable, the repo is missing, or the command fails
     */
    public GitDiff getDiff() {
        String baseBranch = config.getGit().getBaseBranch();
        validateRef(baseBranch);

        File workDir = resolveWorkingDirectory();

        // 1. Collect changed file names (--name-only)
        List<String> changedFiles = listChangedFiles(baseBranch, workDir);

        if (changedFiles.isEmpty()) {
            log.info("No changes detected between {} and HEAD", baseBranch);
            return new GitDiff(List.of(), "", false);
        }

        // 2. Collect the unified diff patch
        return buildDiff(baseBranch, workDir, changedFiles);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<String> listChangedFiles(String baseBranch, File workDir) {
        String output = runGit(workDir, "git", "diff", "--name-only",
                baseBranch + "...HEAD");
        return output.lines()
                .map(String::trim)
                .filter(l -> !l.isBlank())
                .collect(Collectors.toList());
    }

    private GitDiff buildDiff(String baseBranch, File workDir, List<String> changedFiles) {
        String rawPatch = runGit(workDir, "git", "diff", baseBranch + "...HEAD");

        int maxLines = config.getGit().getMaxDiffLines();
        List<String> lines = rawPatch.lines().collect(Collectors.toList());

        boolean truncated = lines.size() > maxLines;
        String patch = truncated
                ? lines.subList(0, maxLines).stream().collect(Collectors.joining("\n"))
                  + "\n\n[... diff truncated at " + maxLines + " lines ...]"
                : rawPatch;

        return new GitDiff(changedFiles, patch, truncated);
    }

    /**
     * Runs a git command and returns its stdout as a string.
     *
     * @param workDir   working directory
     * @param command   git command + arguments (each as a separate element — never shell-expanded)
     * @return stdout of the process
     * @throws GitException on non-zero exit, timeout, or I/O error
     */
    String runGit(File workDir, String... command) {
        log.debug("Executing: {}", List.of(command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException ex) {
            throw new GitException("Failed to start git process: " + ex.getMessage(), ex);
        }

        String stdout;
        String stderr;
        try (
                BufferedReader outReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))
        ) {
            stdout = outReader.lines().collect(Collectors.joining("\n"));
            stderr = errReader.lines().collect(Collectors.joining("\n"));
        } catch (IOException ex) {
            process.destroyForcibly();
            throw new GitException("Failed to read git output: " + ex.getMessage(), ex);
        }

        boolean finished;
        try {
            finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GitException("Git process was interrupted", ex);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new GitException("Git process timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new GitException(
                    "Git command failed (exit " + exitCode + "): "
                    + (stderr.isBlank() ? "(no stderr)" : stderr));
        }

        return stdout;
    }

    private File resolveWorkingDirectory() {
        String dir = config.getGit().getWorkingDir();
        File workDir = new File(dir).getAbsoluteFile();
        if (!workDir.exists() || !workDir.isDirectory()) {
            throw new GitException("Git working directory does not exist: " + workDir);
        }
        // Verify it is actually a git repo
        File gitDir = new File(workDir, ".git");
        if (!gitDir.exists()) {
            throw new GitException("Not a git repository: " + workDir);
        }
        return workDir;
    }

    /**
     * Validates that {@code ref} matches the safe-ref allowlist, preventing command injection.
     */
    private void validateRef(String ref) {
        if (ref == null || !SAFE_REF_PATTERN.matcher(ref).matches()) {
            throw new GitException("Unsafe or invalid git ref: " + ref);
        }
    }
}
