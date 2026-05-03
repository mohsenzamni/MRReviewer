package com.mohsenzamni.mrreviewer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Incoming request body for POST /review.
 */
public record ReviewRequest(
        @NotBlank(message = "issueUrl must not be blank")
        @Pattern(
                regexp = "https?://.+/-/issues/\\d+",
                message = "issueUrl must be a valid GitLab issue URL, e.g. https://gitlab.example.com/group/project/-/issues/123"
        )
        String issueUrl
) {}
