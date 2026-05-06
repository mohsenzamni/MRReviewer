package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.dto.AcReview;
import com.mohsenzamni.mrreviewer.dto.Evidence;
import com.mohsenzamni.mrreviewer.dto.ReviewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ReviewHistoryServiceTest {

    private ReviewHistoryService service;

    @BeforeEach
    void setUp() {
        service = new ReviewHistoryService();
    }

    @Test
    void add_andGet_returnsSameEntry() {
        String url = "https://gitlab.com/org/proj/-/issues/1";
        ReviewResponse r = minimalResponse("First cycle summary");

        service.add(url, r);

        assertThat(service.get(url)).containsExactly(r);
    }

    @Test
    void get_returnsEmptyList_whenNoHistory() {
        assertThat(service.get("https://gitlab.com/org/proj/-/issues/99")).isEmpty();
    }

    @Test
    void add_keepsMaxHistoryEntries() {
        String url = "https://gitlab.com/org/proj/-/issues/2";
        for (int i = 0; i < ReviewHistoryService.MAX_HISTORY_PER_ISSUE + 3; i++) {
            service.add(url, minimalResponse("summary"));
        }
        assertThat(service.get(url)).hasSize(ReviewHistoryService.MAX_HISTORY_PER_ISSUE);
    }

    @Test
    void formatHistory_returnsBlank_whenEmpty() {
        assertThat(service.formatHistory("https://gitlab.com/org/proj/-/issues/3")).isBlank();
    }

    @Test
    void formatHistory_includesCycleNumbers() {
        String url = "https://gitlab.com/org/proj/-/issues/4";
        service.add(url, minimalResponse("Summary A"));
        service.add(url, minimalResponse("Summary B"));

        String text = service.formatHistory(url);

        assertThat(text).contains("Cycle 1");
        assertThat(text).contains("Cycle 2");
        assertThat(text).contains("Summary A");
        assertThat(text).contains("Summary B");
    }

    @Test
    void formatHistory_includesAcStatusAndIssues() {
        String url = "https://gitlab.com/org/proj/-/issues/5";
        List<AcReview> acReviews = List.of(
                new AcReview("Login endpoint returns JWT", "covered",
                        List.of(new Evidence("LoginController.java", "45-67")), List.of()),
                new AcReview("Errors use RFC 7807 format", "partial",
                        List.of(), List.of("Only 404 responses formatted; 500 still plain text"))
        );
        ReviewResponse r = new ReviewResponse(
                "Implements login feature.",
                acReviews,
                List.of("Rate limiting not implemented"),
                List.of("Add MockMvc tests")
        );
        service.add(url, r);

        String text = service.formatHistory(url);

        assertThat(text).contains("COVERED");
        assertThat(text).contains("PARTIAL");
        assertThat(text).contains("Login endpoint returns JWT");
        assertThat(text).contains("Errors use RFC 7807 format");
        assertThat(text).contains("Only 404 responses formatted");
    }

    @Test
    void formatHistory_includesRisks() {
        String url = "https://gitlab.com/org/proj/-/issues/6";
        ReviewResponse r = new ReviewResponse(
                "Refactors auth.",
                List.of(),
                List.of("Race condition in concurrent requests", "Missing token expiry check"),
                List.of()
        );
        service.add(url, r);

        String text = service.formatHistory(url);

        assertThat(text).contains("Risks");
        assertThat(text).contains("Race condition in concurrent requests");
        assertThat(text).contains("Missing token expiry check");
    }

    @Test
    void formatHistory_includesSuggestions() {
        String url = "https://gitlab.com/org/proj/-/issues/7";
        ReviewResponse r = new ReviewResponse(
                "Adds OTP flow.",
                List.of(),
                List.of(),
                List.of("Consider adding integration test suite")
        );
        service.add(url, r);

        String text = service.formatHistory(url);

        assertThat(text).contains("Suggestions");
        assertThat(text).contains("Consider adding integration test suite");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReviewResponse minimalResponse(String summary) {
        return new ReviewResponse(summary, List.of(), List.of(), List.of());
    }
}
