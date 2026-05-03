package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Final API response returned by POST /review.
 */
public record ReviewResponse(
        String summary,
        List<String> gaps,
        @JsonProperty("unrelated_changes")
        List<String> unrelatedChanges,
        String verdict,
        double confidence
) {}
