package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Final API response returned by POST /review.
 *
 * <p>The format follows the "MR Semantic Reviewer" design with per-acceptance-criterion traceability:
 * <ul>
 *   <li>{@code summary} — 2-3 sentence executive summary: what the MR does, overall quality, AC coverage.</li>
 *   <li>{@code acceptanceCriteriaReview} — one entry per extracted AC with coverage status, code evidence,
 *       and specific issues.</li>
 *   <li>{@code risks} — cross-cutting concerns not tied to a single AC (security, performance,
 *       backward compatibility, race conditions, etc.).</li>
 *   <li>{@code suggestions} — non-blocking improvement ideas (code quality, test coverage, patterns).</li>
 * </ul>
 */
public record ReviewResponse(
        String summary,
        @JsonProperty("acceptance_criteria_review")
        List<AcReview> acceptanceCriteriaReview,
        List<String> risks,
        List<String> suggestions
) {}
