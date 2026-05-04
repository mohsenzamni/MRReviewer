package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.dto.Finding;
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
        ReviewResponse r = response("FULLY_RESOLVED", 1.0);

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
            service.add(url, response("NOT_RESOLVED", 0.1));
        }
        assertThat(service.get(url)).hasSize(ReviewHistoryService.MAX_HISTORY_PER_ISSUE);
    }

    @Test
    void formatHistory_returnsBlank_whenEmpty() {
        assertThat(service.formatHistory("https://gitlab.com/org/proj/-/issues/3")).isBlank();
    }

    @Test
    void formatHistory_includesCycleNumber() {
        String url = "https://gitlab.com/org/proj/-/issues/4";
        service.add(url, response("PARTIALLY_RESOLVED", 0.6));
        service.add(url, response("FULLY_RESOLVED", 0.9));

        String text = service.formatHistory(url);

        assertThat(text).contains("Cycle 1");
        assertThat(text).contains("Cycle 2");
        assertThat(text).contains("PARTIALLY_RESOLVED");
        assertThat(text).contains("FULLY_RESOLVED");
    }

    @Test
    void formatHistory_includesFindings() {
        String url = "https://gitlab.com/org/proj/-/issues/5";
        ReviewResponse r = new ReviewResponse(
                "summary",
                List.of(new Finding("Missing auth check", "CRITICAL")),
                List.of(),
                "NOT_RESOLVED",
                0.5
        );
        service.add(url, r);

        String text = service.formatHistory(url);

        assertThat(text).contains("Missing auth check");
        assertThat(text).contains("CRITICAL");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReviewResponse response(String verdict, double confidence) {
        return new ReviewResponse("summary", List.of(), List.of(), verdict, confidence);
    }
}
