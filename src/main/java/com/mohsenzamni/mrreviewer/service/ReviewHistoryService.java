package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.dto.Finding;
import com.mohsenzamni.mrreviewer.dto.ReviewResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stores the review history for each issue (keyed by issue URL) in memory.
 *
 * <p>The history is used to give the LLM context about previous review cycles so that
 * it can track progress and avoid repeating the same feedback on already-fixed findings.
 *
 * <p>At most {@link #MAX_HISTORY_PER_ISSUE} past reviews are kept per issue URL.
 */
@Service
public class ReviewHistoryService {

    /** Maximum number of past reviews retained per issue. */
    static final int MAX_HISTORY_PER_ISSUE = 10;

    /** issue URL → ordered list of past review summaries (oldest first). */
    private final Map<String, List<ReviewResponse>> history = new LinkedHashMap<>();

    /**
     * Appends a completed review to the history for the given issue URL.
     *
     * @param issueUrl the canonical issue URL used as the history key
     * @param response the completed review to store
     */
    public synchronized void add(String issueUrl, ReviewResponse response) {
        List<ReviewResponse> list = history.computeIfAbsent(issueUrl, k -> new ArrayList<>());
        list.add(response);
        if (list.size() > MAX_HISTORY_PER_ISSUE) {
            list.remove(0);
        }
    }

    /**
     * Returns an unmodifiable view of all past reviews for {@code issueUrl}.
     * Returns an empty list when no history exists yet.
     */
    public synchronized List<ReviewResponse> get(String issueUrl) {
        return Collections.unmodifiableList(
                history.getOrDefault(issueUrl, Collections.emptyList()));
    }

    /**
     * Formats past reviews as a compact Markdown block suitable for inclusion in an LLM prompt.
     * Returns an empty string when there is no history.
     */
    public String formatHistory(String issueUrl) {
        List<ReviewResponse> past = get(issueUrl);
        if (past.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Previous Review Cycles\n\n");
        for (int i = 0; i < past.size(); i++) {
            ReviewResponse r = past.get(i);
            sb.append("### Cycle ").append(i + 1).append(" — verdict: ").append(r.verdict())
              .append(" (confidence: ").append(String.format("%.2f", r.confidence())).append(")\n");
            sb.append("**Summary:** ").append(r.summary()).append("\n");
            if (!r.gaps().isEmpty()) {
                sb.append("**Gaps/Issues found:**\n");
                r.gaps().forEach(f ->
                    sb.append("- [").append(f.severity()).append("] ").append(f.description()).append("\n")
                );
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
