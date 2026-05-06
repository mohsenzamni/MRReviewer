package com.mohsenzamni.mrreviewer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mohsenzamni.mrreviewer.client.GitLabClient;
import com.mohsenzamni.mrreviewer.client.LiteLLMClient;
import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.AcReview;
import com.mohsenzamni.mrreviewer.dto.GitDiff;
import com.mohsenzamni.mrreviewer.dto.GitLabIssue;
import com.mohsenzamni.mrreviewer.dto.ReviewRequest;
import com.mohsenzamni.mrreviewer.dto.ReviewResponse;
import com.mohsenzamni.mrreviewer.exception.LLMException;
import com.mohsenzamni.mrreviewer.tool.SearchTool;
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

    @Mock private GitLabClient          gitLabClient;
    @Mock private GitService            gitService;
    @Mock private LiteLLMClient         liteLLMClient;
    @Mock private LocalToolsService     localToolsService;
    @Mock private ProjectConfigService  projectConfigService;
    @Mock private SearchTool            searchTool;

    private ReviewService       reviewService;
    private ReviewHistoryService historyService;

    /** Convenience factory — no custom skills. */
    private static ReviewRequest req(String issueUrl) {
        return new ReviewRequest(issueUrl, null);
    }

    @BeforeEach
    void setUp() {
        AppConfig config  = new AppConfig();
        historyService    = new ReviewHistoryService();
        reviewService     = new ReviewService(gitLabClient, gitService, liteLLMClient,
                historyService, localToolsService, projectConfigService,
                searchTool, new ObjectMapper(), config);

        // Default: static analysis returns nothing — lenient so unit-only tests don't flag it as unused
        lenient().when(localToolsService.runAll()).thenReturn(List.of());
        // Default: search returns no matches — lenient so unit-only tests don't flag it as unused
        lenient().when(searchTool.searchInRepo(anyString(), anyInt()))
                .thenReturn(new SearchTool.SearchResult("", List.of(), false));
    }

    // ── No local changes ──────────────────────────────────────────────────────

    @Test
    void review_returnsEmpty_whenNoDiff() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(1L, "Fix login bug", "Users cannot log in."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of(), "", false));

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/1"));

        assertThat(response.acceptanceCriteriaReview()).isEmpty();
        assertThat(response.risks()).isNotEmpty();
        assertThat(response.summary()).isNotBlank();
        verifyNoInteractions(liteLLMClient);
    }

    // ── No relevant files after filtering ────────────────────────────────────

    @Test
    void review_returnsRisk_whenNoRelevantFiles() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(2L, "Fix payment processor",
                        "Payment module is broken."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("readme.md"), "diff content", false));

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/2"));

        assertThat(response.acceptanceCriteriaReview()).isEmpty();
        assertThat(response.risks()).isNotEmpty();
    }

    // ── Successful review — new per-AC format ────────────────────────────────

    @Test
    void review_returnsAcReview_withFullReportFields() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(3L, "Add login feature", "Implement login endpoint."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(
                        List.of("src/LoginController.java"),
                        "diff --git a/src/LoginController.java ...",
                        false));

        // First call = AC extraction, second call = review
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[\"Login endpoint must return JWT\",\"Errors must be RFC 7807\"]}")
                .thenReturn("""
                        {
                          "summary": "This MR adds the login endpoint.",
                          "acceptance_criteria_review": [
                            {
                              "ac": "Login endpoint must return JWT",
                              "status": "covered",
                              "evidence": [{"file": "LoginController.java", "lines": "45-67"}],
                              "issues": []
                            },
                            {
                              "ac": "Errors must be RFC 7807",
                              "status": "missing",
                              "evidence": [],
                              "issues": ["No error body format applied"]
                            }
                          ],
                          "risks": ["Rate limiting absent on login endpoint"],
                          "suggestions": ["Add MockMvc tests for error paths"]
                        }
                        """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/3"));

        assertThat(response.summary()).contains("login endpoint");
        assertThat(response.acceptanceCriteriaReview()).hasSize(2);

        AcReview covered = response.acceptanceCriteriaReview().get(0);
        assertThat(covered.ac()).isEqualTo("Login endpoint must return JWT");
        assertThat(covered.status()).isEqualTo("covered");
        assertThat(covered.evidence()).hasSize(1);
        assertThat(covered.evidence().get(0).file()).isEqualTo("LoginController.java");
        assertThat(covered.evidence().get(0).lines()).isEqualTo("45-67");
        assertThat(covered.issues()).isEmpty();

        AcReview missing = response.acceptanceCriteriaReview().get(1);
        assertThat(missing.status()).isEqualTo("missing");
        assertThat(missing.issues()).containsExactly("No error body format applied");

        assertThat(response.risks()).containsExactly("Rate limiting absent on login endpoint");
        assertThat(response.suggestions()).containsExactly("Add MockMvc tests for error paths");
    }

    // ── AC status normalisation ───────────────────────────────────────────────

    @Test
    void review_normalisesUnknownAcStatus_toMissing() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(12L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn("""
                        {
                          "summary": "S",
                          "acceptance_criteria_review": [
                            {"ac": "Some AC", "status": "UNKNOWN_STATUS",
                             "evidence": [], "issues": []}
                          ],
                          "risks": [],
                          "suggestions": []
                        }
                        """);

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/12"));

        assertThat(response.acceptanceCriteriaReview()).hasSize(1);
        assertThat(response.acceptanceCriteriaReview().get(0).status()).isEqualTo("missing");
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

    // ── Review history ────────────────────────────────────────────────────────

    @Test
    void history_isStoredAfterSuccessfulReview() {
        String issueUrl = "https://gitlab.com/org/proj/-/issues/10";
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(10L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn(minimalReviewJson());

        reviewService.review(new ReviewRequest(issueUrl, null));

        assertThat(historyService.get(issueUrl)).hasSize(1);
        assertThat(historyService.get(issueUrl).get(0).summary()).isEqualTo("S");
    }

    @Test
    void history_returnsEmpty_whenNoHistory() {
        assertThat(historyService.formatHistory("https://gitlab.com/org/proj/-/issues/999"))
                .isBlank();
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

    // ── Markdown code-fence stripping ─────────────────────────────────────────

    @Test
    void review_parsesResponse_whenLlmWrapsJsonInMarkdownFence() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(20L, "Fix oob issue", "Missing oobTransId."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("OobService.java"),
                        "diff --git a/OobService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn("""
                        ```json
                        {
                          "summary": "Wrapped response",
                          "acceptance_criteria_review": [],
                          "risks": [],
                          "suggestions": []
                        }
                        ```""");

        ReviewResponse response = reviewService.review(req("https://gitlab.com/org/proj/-/issues/20"));

        assertThat(response.summary()).isEqualTo("Wrapped response");
    }

    // ── Invalid LLM JSON ──────────────────────────────────────────────────────

    @Test
    void review_throwsLlmException_onInvalidJson() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(9L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn("not json at all");

        assertThatThrownBy(
                () -> reviewService.review(req("https://gitlab.com/org/proj/-/issues/9")))
                .isInstanceOf(LLMException.class)
                .hasMessageContaining("Failed to parse LLM JSON response");
    }

    // ── Acceptance-criteria extraction ────────────────────────────────────────

    @Test
    void extractAcs_returnsItems_fromLlmResponse() {
        GitLabIssue issue = new GitLabIssue(50L, "Add OTP", "Send OTP via SMS.");
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[\"OTP sent via SMS\",\"OTP expires in 5 minutes\"]}");

        List<String> acs = reviewService.extractAcs(issue);

        assertThat(acs).containsExactly("OTP sent via SMS", "OTP expires in 5 minutes");
    }

    @Test
    void extractAcs_returnsEmptyList_whenLlmFails() {
        GitLabIssue issue = new GitLabIssue(51L, "Add OTP", "Send OTP via SMS.");
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM unreachable"));

        List<String> acs = reviewService.extractAcs(issue);

        assertThat(acs).isEmpty();
    }

    // ── Code context gathering ────────────────────────────────────────────────

    @Test
    void gatherCodeContext_returnsEmpty_whenNoAcs() {
        GitDiff diff = new GitDiff(List.of("Foo.java"), "patch", false);

        String context = reviewService.gatherCodeContext(List.of(), diff);

        assertThat(context).isBlank();
        verifyNoInteractions(searchTool);
    }

    @Test
    void gatherCodeContext_includesSearchResults_forAcTerms() {
        GitDiff diff = new GitDiff(List.of("AuthService.java"), "patch", false);
        when(searchTool.searchInRepo(eq("AuthService"), anyInt()))
                .thenReturn(new SearchTool.SearchResult("AuthService",
                        List.of(new SearchTool.SearchMatch("src/AuthService.java", 10,
                                "public class AuthService {")),
                        false));

        String context = reviewService.gatherCodeContext(
                List.of("Update AuthService authentication flow"), diff);

        assertThat(context).contains("Repository Context");
        assertThat(context).contains("AuthService.java:10");
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
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn(minimalReviewJson());

        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/30"));

        // Second call to chat() is the review call — check its system prompt
        verify(liteLLMClient, times(2)).chat(sysCaptor.capture(), anyString());
        assertThat(sysCaptor.getAllValues().get(1)).contains("Domain skill A, Domain skill B");
    }

    @Test
    void review_usesRequestSkills_overProjectSkills() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(31L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn(minimalReviewJson());

        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(new ReviewRequest(
                "https://gitlab.com/org/proj/-/issues/31", "Request-level skills override"));

        verify(liteLLMClient, times(2)).chat(sysCaptor.capture(), anyString());
        assertThat(sysCaptor.getAllValues().get(1)).contains("Request-level skills override");
        // project config must NOT be consulted when request skills are explicit
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
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn(minimalReviewJson());

        ArgumentCaptor<String> sysCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/32"));

        verify(liteLLMClient, times(2)).chat(sysCaptor.capture(), anyString());
        assertThat(sysCaptor.getAllValues().get(1)).contains(ReviewRequest.DEFAULT_SKILLS);
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
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn(minimalReviewJson());

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/33"));

        verify(liteLLMClient, times(2)).chat(anyString(), userCaptor.capture());
        String reviewUserPrompt = userCaptor.getAllValues().get(1);
        assertThat(reviewUserPrompt).contains("## Static Analysis Results");
        assertThat(reviewUserPrompt).contains("### checkstyle");
        assertThat(reviewUserPrompt).contains("Line too long");
    }

    @Test
    void review_omitsStaticAnalysisSection_whenNoToolResults() {
        when(localToolsService.runAll()).thenReturn(List.of());
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(34L, "Auth refactor", "Refactor auth module."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("AuthService.java"),
                        "diff --git a/AuthService.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[]}")
                .thenReturn(minimalReviewJson());

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/34"));

        verify(liteLLMClient, times(2)).chat(anyString(), userCaptor.capture());
        assertThat(userCaptor.getAllValues().get(1)).doesNotContain("## Static Analysis Results");
    }

    // ── ACs appear in the review user prompt ──────────────────────────────────

    @Test
    void review_includesExtractedAcs_inUserPrompt() {
        when(gitLabClient.fetchIssue(any()))
                .thenReturn(new GitLabIssue(40L, "Add login", "Implement login endpoint."));
        when(gitService.getDiff())
                .thenReturn(new GitDiff(List.of("LoginController.java"),
                        "diff --git a/LoginController.java ...", false));
        when(liteLLMClient.chat(anyString(), anyString()))
                .thenReturn("{\"items\":[\"Login returns JWT\",\"Token expires in 1h\"]}")
                .thenReturn(minimalReviewJson());

        ArgumentCaptor<String> userCaptor = ArgumentCaptor.forClass(String.class);
        reviewService.review(req("https://gitlab.com/org/proj/-/issues/40"));

        verify(liteLLMClient, times(2)).chat(anyString(), userCaptor.capture());
        String reviewUserPrompt = userCaptor.getAllValues().get(1);
        assertThat(reviewUserPrompt).contains("## Acceptance Criteria");
        assertThat(reviewUserPrompt).contains("Login returns JWT");
        assertThat(reviewUserPrompt).contains("Token expires in 1h");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Minimal valid review JSON in the new per-AC format. */
    private static String minimalReviewJson() {
        return """
                {
                  "summary": "S",
                  "acceptance_criteria_review": [],
                  "risks": [],
                  "suggestions": []
                }
                """;
    }
}
