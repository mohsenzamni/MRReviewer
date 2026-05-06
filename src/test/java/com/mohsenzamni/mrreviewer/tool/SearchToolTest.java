package com.mohsenzamni.mrreviewer.tool;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class SearchToolTest {

    @TempDir
    Path tempDir;

    private SearchTool searchTool;

    @BeforeEach
    void setUp() throws IOException {
        AppConfig config = new AppConfig();
        config.setGit(new AppConfig.Git());
        config.getGit().setWorkingDir(tempDir.toString());
        searchTool = new SearchTool(config);

        // Create Java source files
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/AuthService.java"), """
                public class AuthService {
                    public String authenticate(String username, String password) {
                        // validate password
                        return generateToken(username);
                    }
                    private String generateToken(String username) {
                        return "token-" + username;
                    }
                }
                """);
        Files.writeString(tempDir.resolve("src/LoginController.java"), """
                public class LoginController {
                    // authenticate the user
                    public Response login(String username, String password) {
                        String token = authService.authenticate(username, password);
                        return Response.ok(token);
                    }
                }
                """);
        // Non-Java file should also be searched
        Files.writeString(tempDir.resolve("application.yml"), """
                spring:
                  auth:
                    token-expiry: 3600
                """);
    }

    // ── Basic search ──────────────────────────────────────────────────────────

    @Test
    void searchInRepo_findsMatchesAcrossFiles() {
        SearchTool.SearchResult result = searchTool.searchInRepo("authenticate", 10);

        // Should find occurrences in both Java files
        assertThat(result.matches()).isNotEmpty();
        assertThat(result.matches())
                .extracting(SearchTool.SearchMatch::file)
                .anyMatch(f -> f.contains("AuthService.java"));
        assertThat(result.matches())
                .extracting(SearchTool.SearchMatch::file)
                .anyMatch(f -> f.contains("LoginController.java"));
    }

    @Test
    void searchInRepo_isCaseInsensitive() {
        SearchTool.SearchResult result = searchTool.searchInRepo("AUTHENTICATE", 10);

        assertThat(result.matches()).isNotEmpty();
    }

    @Test
    void searchInRepo_returnsLineNumbers() {
        SearchTool.SearchResult result = searchTool.searchInRepo("generateToken", 10);

        assertThat(result.matches()).isNotEmpty();
        assertThat(result.matches()).allMatch(m -> m.line() > 0);
    }

    @Test
    void searchInRepo_returnsMatchedLineContent() {
        SearchTool.SearchResult result = searchTool.searchInRepo("generateToken", 10);

        assertThat(result.matches())
                .extracting(SearchTool.SearchMatch::content)
                .anyMatch(c -> c.contains("generateToken"));
    }

    @Test
    void searchInRepo_searchesYamlFiles() {
        SearchTool.SearchResult result = searchTool.searchInRepo("token-expiry", 5);

        assertThat(result.matches()).isNotEmpty();
        assertThat(result.matches())
                .extracting(SearchTool.SearchMatch::file)
                .anyMatch(f -> f.contains("application.yml"));
    }

    // ── Limit enforcement ─────────────────────────────────────────────────────

    @Test
    void searchInRepo_respectsLimit() {
        SearchTool.SearchResult result = searchTool.searchInRepo("authenticate", 1);

        assertThat(result.matches()).hasSize(1);
    }

    @Test
    void searchInRepo_capsLimitAtMax() {
        // Even asking for 9999 results, we should not exceed MAX_LIMIT
        SearchTool.SearchResult result = searchTool.searchInRepo("a", SearchTool.MAX_LIMIT + 100);

        assertThat(result.matches()).hasSizeLessThanOrEqualTo(SearchTool.MAX_LIMIT);
    }

    // ── No matches ────────────────────────────────────────────────────────────

    @Test
    void searchInRepo_returnsEmpty_whenNoMatch() {
        SearchTool.SearchResult result = searchTool.searchInRepo("xyzzy_not_found_12345", 10);

        assertThat(result.matches()).isEmpty();
        assertThat(result.limitReached()).isFalse();
    }

    // ── Blank query ───────────────────────────────────────────────────────────

    @Test
    void searchInRepo_returnsEmpty_forBlankQuery() {
        SearchTool.SearchResult result = searchTool.searchInRepo("   ", 10);

        assertThat(result.matches()).isEmpty();
    }
}
