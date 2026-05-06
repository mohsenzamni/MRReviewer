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
import java.util.stream.Stream;

/**
 * Code search tool: finds lines matching a text query across all source files in the
 * git working directory.
 *
 * <p>The implementation uses a pure-Java file walk so it works on all platforms without
 * depending on external tools like {@code grep}. Only files with common source-code
 * extensions are scanned to avoid binary noise.
 *
 * <p>Results are returned in file-path + line-number order and are capped at
 * {@code limit} to prevent oversized prompts.
 */
@Service
public class SearchTool implements ReviewTool {

    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);

    /** File extensions considered for search (lower-case). */
    private static final List<String> SEARCHABLE_EXTENSIONS = List.of(
            ".java", ".kt", ".groovy",     // JVM
            ".xml", ".yml", ".yaml",       // config
            ".properties",                 // Spring properties
            ".sql",                        // database
            ".md"                          // documentation
    );

    /** Hard cap on the number of matches returned, regardless of caller-supplied limit. */
    static final int MAX_LIMIT = 50;

    private final AppConfig config;

    public SearchTool(AppConfig config) {
        this.config = config;
    }

    @Override
    public String name() { return "search"; }

    @Override
    public String description() {
        return "Search for a text pattern across all source files in the git working directory.";
    }

    // ── Public result types ───────────────────────────────────────────────────

    /**
     * A single line that matched the search query.
     *
     * @param file    path relative to the git working directory
     * @param line    1-based line number within the file
     * @param content the full content of the matching line (trimmed)
     */
    public record SearchMatch(String file, int line, String content) {}

    /**
     * Result of a {@link #searchInRepo} call.
     *
     * @param query        the query that was searched
     * @param matches      ordered list of matching lines (up to {@code limit})
     * @param limitReached {@code true} when more matches exist but were suppressed by the limit
     */
    public record SearchResult(String query, List<SearchMatch> matches, boolean limitReached) {}

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Searches all source files in the working directory for lines containing {@code query}
     * (case-insensitive substring match).
     *
     * @param query the text to search for
     * @param limit maximum number of matches to return (capped at {@link #MAX_LIMIT})
     * @return search result with matched lines
     */
    public SearchResult searchInRepo(String query, int limit) {
        if (query == null || query.isBlank()) {
            return new SearchResult(query, List.of(), false);
        }

        int effectiveLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        // Collect one extra match to detect whether there are more results
        int collectLimit = effectiveLimit + 1;
        String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
        Path workDir = Path.of(config.getGit().getWorkingDir()).toAbsolutePath().normalize();

        List<SearchMatch> matches = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(workDir)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(path) || !isSearchable(path)) {
                    continue;
                }

                List<String> lines;
                try {
                    lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    // Skip files that cannot be decoded or read (e.g. binary files or MalformedInputException)
                    continue;
                }

                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) {
                        String relative = workDir.relativize(path).toString().replace('\\', '/');
                        matches.add(new SearchMatch(relative, i + 1, lines.get(i).strip()));
                        if (matches.size() >= collectLimit) {
                            break;
                        }
                    }
                }
                if (matches.size() >= collectLimit) {
                    break;
                }
            }
        } catch (IOException ex) {
            log.warn("Error walking repository for query '{}': {}", query, ex.getMessage());
        }

        boolean limitReached = matches.size() > effectiveLimit;
        List<SearchMatch> result = limitReached ? matches.subList(0, effectiveLimit) : matches;

        log.debug("searchInRepo '{}' → {} matches (limitReached={})", query, result.size(), limitReached);
        return new SearchResult(query, result, limitReached);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isSearchable(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return SEARCHABLE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
