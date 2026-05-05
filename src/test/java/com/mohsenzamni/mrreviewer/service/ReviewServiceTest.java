package com.mohsenzamni.mrreviewer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mohsenzamni.mrreviewer.client.GitLabClient;
import com.mohsenzamni.mrreviewer.client.LiteLLMClient;
import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.Finding;
import com.mohsenzamni.mrreviewer.dto.GitDiff;
import com.mohsenzamni.mrreviewer.dto.GitLabIssue;
import com.mohsenzamni.mrreviewer.dto.ReviewRequest;
import com.mohsenzamni.mrreviewer.dto.ReviewResponse;
import com.mohsenzamni.mrreviewer.exception.LLMException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private GitLabClient gitLabClient;
    @Mock private GitService gitService;
    @Mock private LiteLLMClient liteLLMClient;
    @Mock private LocalToolsService localToolsService;
    @Mock private ProjectConfigService projectConfigService;

    private ReviewService reviewService;
    private ReviewHistoryService historyService;

    /** Convenience factory — no custom skills. */
    private static ReviewRequest req(String issueUrl) {
        return new ReviewRequest(issueUrl, null);
    }

    @BeforeEach
    void setUp() {
        AppConfig config = new AppConfig();
        historyService = new ReviewHistoryService();
        reviewService = new ReviewService(gitLabClient, gitService, liteLLMClient,
                historyService, localToolsService, projectConfigService,
                new ObjectMapper(), config);
    }

    // ── No local changes ──────────────────────────────────────────────────────

    @Test
    void review_returnsNotResolved_whenNoDiff() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(1L, "Fix login bug", "Users cannot log in."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of(), "", false));

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/1"));

        assertThat(response.verdict()).isEqualTo("NOT_RESOLVED");
        assertThat(response.confidence()).isEqualTo(1.0);
        verifyNoInteractions(liteLLMClient);
    }

    // ── No relevant files after filtering ────────────────────────────────────

    @Test
    void review_returnsNotResolved_whenNoRelevantFiles() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(2L, "Fix payment processor",
                        "Payment module is broken."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("readme.md"), "diff content", false));

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/2"));

        assertThat(response.verdict()).isEqualTo("NOT_RESOLVED");
        assertThat(response.unrelatedChanges()).contains("readme.md");
        verifyNoInteractions(liteLLMClient);
    }

    // ── Successful LLM review — full report format ────────────────────────────

    @Test
    void review_returnsLlmResponse_withFullReportFields() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(3L, "Add login feature",
                        "Implement login endpoint."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(
                        List.of("src/LoginController.java"),
                        "diff --git a/src/LoginController.java ...",
                        false));
        String llmJson = """
                {
                  "summary": "This MR delivers fixes for Add login feature — 1/2 items are addressed: login endpoint created. No previous automated reviews.",
                  "addressed_items": ["Login endpoint created at POST /login"],
                  "total_items": 2,
                  "gaps": [
                    {
                      "severity": "HIGH",
                      "file_location": "LoginController.java:45",
                      "description": "Missing rate-limiting on login endpoint",
                      "recommendation": "Apply @RateLimiter annotation or add a bucket4j filter."
                    },
                    {
                      "severity": "MEDIUM",
                      "file_location": null,
                      "description": "No unit tests for the new endpoint",
                      "recommendation": "Add MockMvc tests covering success and failure paths."
                    }
                  ],
                  "unrelated_changes": [],
                  "verdict": "PARTIALLY_RESOLVED",
                  "confidence": 0.80
                }
                """;
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(llmJson);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/3"));

        assertThat(response.verdict()).isEqualTo("PARTIALLY_RESOLVED");
        assertThat(response.confidence()).isEqualTo(0.80);
        assertThat(response.addressedItems()).containsExactly("Login endpoint created at POST /login");
        assertThat(response.totalItems()).isEqualTo(2);
        assertThat(response.gaps()).hasSize(2);
        // IDs are auto-assigned: H1, M1
        assertThat(response.gaps()).extracting(Finding::id).containsExactly("H1", "M1");
        assertThat(response.gaps()).extracting(Finding::severity).containsExactly("HIGH", "MEDIUM");
        assertThat(response.gaps().get(0).fileLocation()).isEqualTo("LoginController.java:45");
        assertThat(response.gaps().get(0).recommendation()).contains("RateLimiter");
    }

    // ── Finding IDs are auto-assigned per-severity ────────────────────────────

    @Test
    void review_autoAssignsFindingIds_sortedBySeverity() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(13L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        // LLM returns findings in mixed order
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("""
                {
                  "summary": "S",
                  "addressed_items": [],
                  "total_items": 0,
                  "gaps": [
                    { "severity": "LOW",      "description": "L issue",  "recommendation": "fix L" },
                    { "severity": "CRITICAL",  "description": "C issue",  "recommendation": "fix C" },
                    { "severity": "HIGH",      "description": "H1 issue", "recommendation": "fix H1"},
                    { "severity": "HIGH",      "description": "H2 issue", "recommendation": "fix H2"},
                    { "severity": "MEDIUM",    "description": "M issue",  "recommendation": "fix M" }
                  ],
                  "unrelated_changes": [],
                  "verdict": "NOT_RESOLVED",
                  "confidence": 0.5
                }
                """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/13"));

        assertThat(response.gaps()).extracting(Finding::id)
                .containsExactly("C1", "H1", "H2", "M1", "L1");
    }

    // ── Reviewer skills — default used when none supplied ────────────────────

    @Test
    void reviewRequest_usesDefaultSkills_whenNoneProvided() {
        ReviewRequest r = new ReviewRequest("https://gitlab.com/org/proj/-/issues/1", null);
        assertThat(r.effectiveSkills()).isEqualTo(ReviewRequest.DEFAULT_SKILLS);
    }

    @Test
    void reviewRequest_usesCustomSkills_whenProvided() {
        ReviewRequest r = new ReviewRequest("https://gitlab.com/org/proj/-/issues/1", "Security only");
        assertThat(r.effectiveSkills()).isEqualTo("Security only");
    }

    // ── Review history — stored and formatted ─────────────────────────────────

    @Test
    void history_isStoredAfterSuccessfulReview() {
        String issueUrl = "https://gitlab.com/org/proj/-/issues/10";
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(10L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("""
                {
                  "summary": "First review",
                  "addressed_items": [],
                  "total_items": 0,
                  "gaps": [],
                  "unrelated_changes": [],
                  "verdict": "PARTIALLY_RESOLVED",
                  "confidence": 0.6
                }
                """);

        reviewService.review(new ReviewRequest(issueUrl, null));

        assertThat(historyService.get(issueUrl)).hasSize(1);
        assertThat(historyService.get(issueUrl).get(0).verdict()).isEqualTo("PARTIALLY_RESOLVED");
    }

    @Test
    void history_returnsEmpty_whenNoHistory() {
        assertThat(historyService.formatHistory("https://gitlab.com/org/proj/-/issues/999"))
                .isBlank();
    }

    // ── Severity normalisation ────────────────────────────────────────────────

    @Test
    void review_normalisesSeverity_forUnknownValue() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(12L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("""
                {
                  "summary": "S",
                  "addressed_items": [],
                  "total_items": 0,
                  "gaps": [ { "description": "issue", "severity": "SUPER_CRITICAL", "recommendation": "r" } ],
                  "unrelated_changes": [],
                  "verdict": "NOT_RESOLVED",
                  "confidence": 0.5
                }
                """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/12"));

        assertThat(response.gaps()).hasSize(1);
        assertThat(response.gaps().get(0).severity()).isEqualTo("MEDIUM");
        assertThat(response.gaps().get(0).id()).isEqualTo("M1");
    }

    // ── Keyword extraction ────────────────────────────────────────────────────

    @Test
    void extractKeywords_filtersStopWordsAndShortTokens() {
        GitLabIssue issue = new GitLabIssue(4L, "Fix the login bug", "The user cannot log in.");
        List<String> keywords = reviewService.extractKeywords(issue);

        assertThat(keywords).contains("login", "user", "cannot", "log");
        assertThat(keywords).doesNotContain("the", "in", "a");
    }

    // ── Diff filtering ────────────────────────────────────────────────────────

    @Test
    void filterDiff_keepsRelevantFiles() {
        GitLabIssue issue = new GitLabIssue(5L, "Fix payment service",
                "Payment processing fails.");
        GitDiff full = new GitDiff(
                List.of("PaymentService.java", "UserController.java"),
                "diff --git a/PaymentService.java b/PaymentService.java\n+fix\n"
                + "diff --git a/UserController.java b/UserController.java\n+other\n",
                false);

        GitDiff filtered = reviewService.filterDiff(full, issue);

        assertThat(filtered.changedFiles()).containsExactly("PaymentService.java");
        assertThat(filtered.patch()).contains("PaymentService.java");
        assertThat(filtered.patch()).doesNotContain("UserController.java");
    }

    @Test
    void filterDiff_returnsAll_whenNoKeywords() {
        GitLabIssue issue = new GitLabIssue(6L, "A", "");
        GitDiff full = new GitDiff(List.of("foo.java", "bar.java"), "patch", false);

        GitDiff filtered = reviewService.filterDiff(full, issue);

        assertThat(filtered.changedFiles()).containsExactlyInAnyOrder("foo.java", "bar.java");
    }

    // ── Unknown LLM verdict normalisation ────────────────────────────────────

    @Test
    void review_normalisesUnknownVerdict_toNotResolved() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(7L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("""
                {
                  "summary": "Refactors auth",
                  "addressed_items": [],
                  "total_items": 0,
                  "gaps": [],
                  "unrelated_changes": [],
                  "verdict": "UNKNOWN_VERDICT",
                  "confidence": 0.5
                }
                """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/7"));

        assertThat(response.verdict()).isEqualTo("NOT_RESOLVED");
    }

    // ── Confidence clamping ───────────────────────────────────────────────────

    @Test
    void review_clampsConfidence_toRange() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(8L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("""
                {
                  "summary": "Refactors auth",
                  "addressed_items": [],
                  "total_items": 0,
                  "gaps": [],
                  "unrelated_changes": [],
                  "verdict": "FULLY_RESOLVED",
                  "confidence": 42.0
                }
                """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/8"));

        assertThat(response.confidence()).isEqualTo(1.0);
    }

    // ── Markdown code-fence stripping ─────────────────────────────────────────

    @Test
    void review_parsesResponse_whenLlmWrapsJsonInMarkdownFence() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(20L, "Fix oob issue", "Missing oobTransId."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("OobService.java"),
                        "diff --git a/OobService.java ...", false));
        // LLM wraps its JSON in a markdown code fence despite instructions
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("""
                
                ```json
                {
                  "summary": "Wrapped response",
                  "addressed_items": ["Store oobTransId"],
                  "total_items": 1,
                  "gaps": [],
                  "unrelated_changes": [],
                  "verdict": "FULLY_RESOLVED",
                  "confidence": 0.95
                }
                ```""");

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/20"));

        assertThat(response.verdict()).isEqualTo("FULLY_RESOLVED");
        assertThat(response.confidence()).isEqualTo(0.95);
        assertThat(response.addressedItems()).containsExactly("Store oobTransId");
    }

    // ── Invalid LLM JSON ──────────────────────────────────────────────────────

    @Test
    void review_throwsLlmException_onInvalidJson() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(9L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("not json at all");

        assertThatThrownBy(
                () -> reviewService.review(req("https://gitlab.com/org/proj/-/issues/9")))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("Failed to parse LLM JSON response");
    }

    // ── Project-level skills ──────────────────────────────────────────────────

    @Test
    void review_usesProjectSkills_whenNoRequestSkillsSupplied() {
        when(projectConfigService.loadSkills()).thenReturn("Domain skill A, Domain skill B");
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(30L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(minimalLlmJson());

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/30"));

        verify(liteLLMClient).chat(systemPromptCaptor.capture(), anyString());
        assertThat(systemPromptCaptor.getValue()).contains("Domain skill A, Domain skill B");
    }

    @Test
    void review_usesRequestSkills_overProjectSkills() {
        // project skills should never be consulted when request-level skills are present
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(31L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(minimalLlmJson());

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(new ReviewRequest(
                "https://gitlab.com/org/proj/-/issues/31", "Request-level skills override"));

        verify(liteLLMClient).chat(systemPromptCaptor.capture(), anyString());
        assertThat(systemPromptCaptor.getValue()).contains("Request-level skills override");
        // project config must not be read when request skills are explicit
        verifyNoInteractions(projectConfigService);
    }

    @Test
    void review_usesDefaultSkills_whenNeitherRequestNorProjectSkillsPresent() {
        when(projectConfigService.loadSkills()).thenReturn(null);
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(32L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(minimalLlmJson());

        ArgumentCaptor<String> systemPromptCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/32"));

        verify(liteLLMClient).chat(systemPromptCaptor.capture(), anyString());
        assertThat(systemPromptCaptor.getValue()).contains(ReviewRequest.DEFAULT_SKILLS);
    }

    // ── Static analysis injection ─────────────────────────────────────────────

    @Test
    void review_injectsStaticAnalysis_inUserPrompt_whenToolResultsPresent() {
        when(localToolsService.runAll()).thenReturn(List.of(
                new LocalToolsService.ToolResult("checkstyle",
                        "[ERROR] AuthService.java:42: Line too long (120 > 100).")));
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(33L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(minimalLlmJson());

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/33"));

        verify(liteLLMClient).chat(anyString(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();
        assertThat(userPrompt).contains("## Static Analysis Results");
        assertThat(userPrompt).contains("### checkstyle");
        assertThat(userPrompt).contains("Line too long");
    }

    @Test
    void review_omitsStaticAnalysisSection_whenNoToolResults() {
        when(localToolsService.runAll()).thenReturn(List.of());
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(34L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(minimalLlmJson());

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/34"));

        verify(liteLLMClient).chat(anyString(), userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).doesNotContain("## Static Analysis Results");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Minimal valid LLM JSON response for tests that only care about side-effects. */
    private static String minimalLlmJson() {
        return """
                {
                  "summary": "S",
                  "addressed_items": [],
                  "total_items": 0,
                  "gaps": [],
                  "unrelated_changes": [],
                  "verdict": "NOT_RESOLVED",
                  "confidence": 0.5
                }
                """;
    }
}
