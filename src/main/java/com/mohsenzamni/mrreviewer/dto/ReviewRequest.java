package com.mohsenzamni.mrreviewer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Incoming request body for POST /review.
 *
 * <p>{@code reviewerSkills} is optional; when absent the default skill set is used.
 */
public record ReviewRequest(
        @NotBlank(message = "issueUrl must not be blank")
        @Pattern(
                regexp = "https?://.+/-/issues/\\d+",
                message = "issueUrl must be a valid GitLab issue URL, e.g. https://gitlab.example.com/group/project/-/issues/123"
        )
        String issueUrl,

        String reviewerSkills
) {
    /** Default reviewer skill set used when none is provided. */
    public static final String DEFAULT_SKILLS =
            "Security vulnerabilities, Performance, Code quality and readability, " +
            "Test coverage, SOLID principles, Design patterns, Error handling, " +
            "API contract correctness, Naming conventions";

    /** Returns the configured skills, or the default skill set if none was supplied. */
    public String effectiveSkills() {
        return (reviewerSkills != null && !reviewerSkills.isBlank())
                ? reviewerSkills
                : DEFAULT_SKILLS;
    }
}
