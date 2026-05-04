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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private GitLabClient gitLabClient;

    @Mock
    private GitService gitService;

    @Mock
    private LiteLLMClient liteLLMClient;

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
                historyService, new ObjectMapper(), config);
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

    // ── Successful LLM review — severity in gaps ──────────────────────────────

    @Test
    void review_returnsLlmResponse_withSeverityFindings() {
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
                  "summary": "Adds login endpoint",
                  "gaps": [
                    { "description": "Missing rate-limiting on login endpoint", "severity": "HIGH" },
                    { "description": "No unit tests for the new endpoint", "severity": "MEDIUM" }
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
        assertThat(response.summary()).isEqualTo("Adds login endpoint");
        assertThat(response.gaps()).hasSize(2);
        assertThat(response.gaps()).extracting(Finding::severity)
                .containsExactly("HIGH", "MEDIUM");
        assertThat(response.gaps()).extracting(Finding::description)
                .contains("Missing rate-limiting on login endpoint");
    }

    // ── Reviewer skills — default used when none supplied ────────────────────

    @Test
    void reviewRequest_usesDefaultSkills_whenNoneProvided() {
        ReviewRequest req = new ReviewRequest("https://gitlab.com/org/proj/-/issues/1", null);
        assertThat(req.effectiveSkills()).isEqualTo(ReviewRequest.DEFAULT_SKILLS);
    }

    @Test
    void reviewRequest_usesCustomSkills_whenProvided() {
        ReviewRequest req = new ReviewRequest("https://gitlab.com/org/proj/-/issues/1", "Security only");
        assertThat(req.effectiveSkills()).isEqualTo("Security only");
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
    void history_formatHistory_includesVerdictAndFindings() {
        String issueUrl = "https://gitlab.com/org/proj/-/issues/11";
        ReviewResponse past = new ReviewResponse(
                "Added login endpoint",
                List.of(new Finding("Missing tests", "MEDIUM")),
                List.of(),
                "PARTIALLY_RESOLVED",
                0.7
        );
        historyService.add(issueUrl, past);

        String formatted = historyService.formatHistory(issueUrl);

        assertThat(formatted).contains("PARTIALLY_RESOLVED");
        assertThat(formatted).contains("Missing tests");
        assertThat(formatted).contains("MEDIUM");
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
                  "gaps": [ { "description": "issue", "severity": "SUPER_CRITICAL" } ],
                  "unrelated_changes": [],
                  "verdict": "NOT_RESOLVED",
                  "confidence": 0.5
                }
                """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/12"));

        assertThat(response.gaps()).hasSize(1);
        assertThat(response.gaps().get(0).severity()).isEqualTo("MEDIUM");
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
        // Issue with only stop-words / very short tokens
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
                .thenReturn(new GitDiff(
                        List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...",
                        false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(
                """
                {
                  "summary": "Refactors auth",
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
                .thenReturn(new GitDiff(
                        List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...",
                        false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn(
                """
                {
                  "summary": "Refactors auth",
                  "gaps": [],
                  "unrelated_changes": [],
                  "verdict": "FULLY_RESOLVED",
                  "confidence": 42.0
                }
                """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/8"));

        assertThat(response.confidence()).isEqualTo(1.0);
    }

    // ── Invalid LLM JSON ──────────────────────────────────────────────────────

    @Test
    void review_throwsLlmException_onInvalidJson() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(9L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(
                        List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...",
                        false));
        when(liteLLMClient.chat(anyString(), anyString())).thenReturn("not json at all");

        assertThatThrownBy(
                () -> reviewService.review(req("https://gitlab.com/org/proj/-/issues/9")))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("Failed to parse LLM JSON response");
    }
}
