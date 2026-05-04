package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Final API response returned by POST /review.
 *
 * <p>Mirrors the professional reviewer report format:
 * <ul>
 *   <li>{@code summary} — executive summary in the form
 *       "This MR delivers fixes for issue X — Y/Z items are addressed: …"</li>
 *   <li>{@code addressedItems} — list of issue acceptance criteria satisfied by the diff</li>
 *   <li>{@code totalItems} — total number of acceptance criteria identified in the issue</li>
 *   <li>{@code gaps} — findings grouped/sorted by severity, each with a file:line location
 *       and an actionable recommendation</li>
 *   <li>{@code unrelatedChanges} — changes in the diff that do not relate to the issue</li>
 * </ul>
 */
public record ReviewResponse(
        String summary,
        @JsonProperty("addressed_items")
        List<String> addressedItems,
        @JsonProperty("total_items")
        int totalItems,
        List<Finding> gaps,
        @JsonProperty("unrelated_changes")
        List<String> unrelatedChanges,
        String verdict,
        double confidence
) {}
