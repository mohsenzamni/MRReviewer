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
    void formatHistory_includesFindingIdLocationAndDescription() {
        String url = "https://gitlab.com/org/proj/-/issues/5";
        ReviewResponse r = new ReviewResponse(
                "summary",
                List.of("Addressed item A"),
                3,
                List.of(new Finding("C1", "CRITICAL", "CardHandler.java:677-687",
                        "Partial mutations silently persisted",
                        "Mark transaction for rollback on early return.")),
                List.of(),
                "NOT_RESOLVED",
                0.5
        );
        service.add(url, r);

        String text = service.formatHistory(url);

        assertThat(text).contains("C1");
        assertThat(text).contains("CRITICAL");
        assertThat(text).contains("CardHandler.java:677-687");
        assertThat(text).contains("Partial mutations silently persisted");
    }

    @Test
    void formatHistory_includesAddressedItems() {
        String url = "https://gitlab.com/org/proj/-/issues/6";
        ReviewResponse r = new ReviewResponse(
                "summary",
                List.of("SMS issuer-capability validation", "Atomic delete of multiple devices"),
                5,
                List.of(),
                List.of(),
                "PARTIALLY_RESOLVED",
                0.7
        );
        service.add(url, r);

        String text = service.formatHistory(url);

        assertThat(text).contains("Addressed items");
        assertThat(text).contains("SMS issuer-capability validation");
        assertThat(text).contains("2/5");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReviewResponse response(String verdict, double confidence) {
        return new ReviewResponse("summary", List.of(), 0, List.of(), List.of(), verdict, confidence);
    }
}
