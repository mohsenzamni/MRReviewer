package com.mohsenzamni.mrreviewer.dto;

import java.util.List;

/**
 * Holds the result of running {@code git diff origin/main...HEAD}.
 *
 * @param changedFiles list of relative file paths that changed
 * @param patch        the raw unified diff text (possibly truncated)
 * @param truncated    true when the diff was cut short due to size limits
 */
public record GitDiff(
        List<String> changedFiles,
        String patch,
        boolean truncated
) {}
