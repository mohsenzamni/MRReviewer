package com.mohsenzamni.mrreviewer.dto;

/**
 * A single finding from the code review, with a human-readable description and a severity level.
 *
 * @param description human-readable explanation of the finding
 * @param severity    one of CRITICAL, HIGH, MEDIUM, LOW
 */
public record Finding(
        String description,
        String severity
) {}
