package com.mohsenzamni.mrreviewer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Per-acceptance-criterion review result.
 *
 * <p>Each entry maps one extracted AC to its coverage status in the diff, along with
 * concrete evidence (file + line references) and any issues found.
 *
 * @param ac       the exact acceptance criterion text extracted from the issue
 * @param status   one of {@code "covered"}, {@code "partial"}, or {@code "missing"}
 * @param evidence file + line references from the diff that address this AC (empty when status is {@code "missing"})
 * @param issues   specific, actionable issues found for this AC (empty when status is {@code "covered"})
 */
public record AcReview(
        String ac,
        String status,
        List<Evidence> evidence,
        List<String> issues
) {}
