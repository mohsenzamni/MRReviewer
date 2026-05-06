package com.mohsenzamni.mrreviewer.tool;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Repository file-system tool: read file contents and list directory entries.
 *
 * <h2>Security</h2>
 * All paths are resolved relative to the configured git working directory and then
 * normalised. Any attempt to escape the working directory via {@code ../} sequences is
 * rejected with an {@link IllegalArgumentException}.
 */
@Service
public class RepoTool implements ReviewTool {

    private static final Logger log = LoggerFactory.getLogger(RepoTool.class);

    /** Maximum file lines returned by a single {@link #readFile} call before truncation. */
    static final int MAX_READ_LINES = 300;

    private final AppConfig config;

    public RepoTool(AppConfig config) {
        this.config = config;
    }

    @Override
    public String name() { return "repo"; }

    @Override
    public String description() {
        return "Read source files and list directories in the git working directory.";
    }

    // ── Public result types ───────────────────────────────────────────────────

    /**
     * Result of a {@link #readFile} call.
     *
     * @param relativePath  the resolved relative path that was read
     * @param content       file contents (possibly truncated)
     * @param totalLines    total number of lines in the file before any truncation
     * @param truncated     {@code true} when the content was cut at {@link #MAX_READ_LINES}
     */
    public record ReadFileResult(
            String relativePath,
            String content,
            int totalLines,
            boolean truncated
    ) {}

    /**
     * Result of a {@link #listDirectory} call.
     *
     * @param relativePath  the resolved relative path that was listed
     * @param files         names of regular files directly inside the directory
     * @param directories   names of sub-directories directly inside the directory
     */
    public record DirectoryListing(
            String relativePath,
            List<String> files,
            List<String> directories
    ) {}

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Reads a file from the git working directory.
     *
     * @param relativePath path relative to the git working directory
     * @param startLine    first line to return (1-based, inclusive); {@code null} means start of file
     * @param endLine      last line to return (1-based, inclusive); {@code null} means end of file
     * @return the file content, possibly truncated to {@link #MAX_READ_LINES}
     * @throws IllegalArgumentException if the path escapes the working directory
     * @throws RuntimeException         if the file cannot be read
     */
    public ReadFileResult readFile(String relativePath, Integer startLine, Integer endLine) {
        Path resolved = safeResolve(relativePath);

        List<String> allLines;
        try {
            allLines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Cannot read file '" + relativePath + "': " + ex.getMessage(), ex);
        }

        int totalLines = allLines.size();
        int from = (startLine != null) ? Math.max(0, startLine - 1) : 0;
        int to   = (endLine   != null) ? Math.min(totalLines, endLine) : totalLines;
        from = Math.min(from, totalLines);
        to   = Math.max(from, to);

        List<String> slice = allLines.subList(from, to);
        boolean truncated = slice.size() > MAX_READ_LINES;
        List<String> capped = truncated ? slice.subList(0, MAX_READ_LINES) : slice;

        String content = String.join("\n", capped)
                + (truncated ? "\n[... truncated at " + MAX_READ_LINES + " lines ...]" : "");

        log.debug("readFile '{}' lines {}-{} ({} total, truncated={})",
                relativePath, from + 1, from + capped.size(), totalLines, truncated);
        return new ReadFileResult(relativePath, content, totalLines, truncated);
    }

    /**
     * Lists the immediate children of a directory in the git working directory.
     *
     * @param relativePath path relative to the git working directory (use {@code "."} for root)
     * @return directory listing split into files and sub-directories
     * @throws IllegalArgumentException if the path escapes the working directory
     * @throws RuntimeException         if the directory cannot be read
     */
    public DirectoryListing listDirectory(String relativePath) {
        Path resolved = safeResolve(relativePath);
        if (!Files.isDirectory(resolved)) {
            throw new RuntimeException("Not a directory: " + relativePath);
        }

        List<String> files = new ArrayList<>();
        List<String> directories = new ArrayList<>();

        try (Stream<Path> stream = Files.list(resolved)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    directories.add(name);
                } else {
                    files.add(name);
                }
            });
        } catch (IOException ex) {
            throw new RuntimeException("Cannot list directory '" + relativePath + "': " + ex.getMessage(), ex);
        }

        files.sort(String::compareTo);
        directories.sort(String::compareTo);

        log.debug("listDirectory '{}' → {} files, {} dirs", relativePath, files.size(), directories.size());
        return new DirectoryListing(relativePath, files, directories);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves {@code relativePath} under the working directory and verifies that the result
     * is still inside the working directory (prevents path-traversal attacks).
     *
     * @throws IllegalArgumentException if the resolved path would escape the working directory
     */
    private Path safeResolve(String relativePath) {
        Path workDir = Path.of(config.getGit().getWorkingDir()).toAbsolutePath().normalize();
        Path resolved = workDir.resolve(relativePath).normalize();

        if (!resolved.startsWith(workDir)) {
            throw new IllegalArgumentException(
                    "Path '" + relativePath + "' escapes the git working directory — access denied.");
        }
        return resolved;
    }
}
