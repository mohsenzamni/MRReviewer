package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single finding from the code review.
 *
 * @param id             auto-assigned short ID, e.g. C1, H2, M1, L3
 * @param severity       one of CRITICAL, HIGH, MEDIUM, LOW
 * @param fileLocation   "FileName.java:123-456" pinpointing the problematic code (may be null)
 * @param description    detailed explanation of the problem, including code context where relevant
 * @param recommendation actionable advice on how to fix the problem
 */
public record Finding(
        String id,
        String severity,
        @JsonProperty("file_location")
        String fileLocation,
        String description,
        String recommendation
) {}
