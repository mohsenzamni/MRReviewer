package com.mohsenzamni.mrreviewer.dto;

/**
 * Represents a GitLab issue fetched from the API.
 */
public record GitLabIssue(
        long id,
        String title,
        String description
) {}
