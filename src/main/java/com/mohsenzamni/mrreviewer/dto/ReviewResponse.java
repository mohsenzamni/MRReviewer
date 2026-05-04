package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Final API response returned by POST /review.
 *
 * <p>Each gap is now a {@link Finding} carrying both a description and a severity level
 * (CRITICAL, HIGH, MEDIUM, or LOW). Unrelated changes remain simple strings.
 */
public record ReviewResponse(
        String summary,
        List<Finding> gaps,
        @JsonProperty("unrelated_changes")
        List<String> unrelatedChanges,
        String verdict,
        double confidence
) {}
