package com.mohsenzamni.mrreviewer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mohsenzamni.mrreviewer.client.GitLabClient;
import com.mohsenzamni.mrreviewer.client.LiteLLMClient;
import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.Finding;
import com.mohsenzamni.mrreviewer.dto.GitDiff;
import com.mohsenzamni.mrreviewer.dto.GitLabIssue;
import com.mohsenzamni.mrreviewer.dto.ReviewRequest;
import com.mohsenzamni.mrreviewer.dto.ReviewResponse;
import com.mohsenzamni.mrreviewer.exception.LLMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the full pre-push review pipeline:
 *
 * <ol>
 *   <li>Parse the GitLab issue URL and fetch issue metadata.</li>
 *   <li>Retrieve the local git diff.</li>
 *   <li>Filter the diff to files relevant to the issue.</li>
 *   <li>Build a structured prompt (including reviewer skills and past review history) and call the LLM.</li>
 *   <li>Parse the JSON response, persist to history, and return a {@link ReviewResponse}.</li>
 * </ol>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    // ── Prompt constants ──────────────────────────────────────────────────────

    /**
     * System prompt template.  {@code %s} is replaced with the caller-supplied reviewer skills.
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are an expert software engineer performing a pre-push code review.
            
            Your reviewer focus areas / skill set:
            %s
            
            You will receive:
            - A GitLab issue (title + description).
            - Optionally, a history of previous review cycles for this issue.
            - A git diff of the current local changes.
            
            Your tasks:
            1. Summarize the issue in one or two sentences.
            2. Extract the expected behavior / acceptance criteria from the issue.
            3. Analyze the code changes in the diff.
            4. Map each change to the expected behavior.
            5. Identify missing implementations that the issue requires but the diff does not provide.
               For each gap, assign a severity: CRITICAL, HIGH, MEDIUM, or LOW.
               - CRITICAL: blocks correctness or introduces a security vulnerability.
               - HIGH: significant functional gap or likely bug.
               - MEDIUM: partial implementation or questionable quality.
               - LOW: minor improvement opportunity or style issue.
            6. Identify unrelated changes that are present in the diff but do not relate to the issue.
            7. Consider the previous review cycles (if any) to track whether past findings have been addressed.
            8. Produce a final verdict: FULLY_RESOLVED, PARTIALLY_RESOLVED, or NOT_RESOLVED.
            9. Provide a confidence score between 0.0 and 1.0.
            
            You MUST respond with ONLY a single valid JSON object — no markdown, no prose outside the JSON.
            The JSON must conform exactly to this schema:
            {
              "summary": "<string>",
              "gaps": [
                { "description": "<string>", "severity": "CRITICAL|HIGH|MEDIUM|LOW" },
                ...
              ],
              "unrelated_changes": ["<string>", ...],
              "verdict": "FULLY_RESOLVED | PARTIALLY_RESOLVED | NOT_RESOLVED",
              "confidence": <number 0-1>
            }
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            %s
            ## Issue
            
            **Title:** %s
            
            **Description:**
            %s
            
            ## Local Git Diff (branch vs %s)
            
            Changed files (%d):
            %s
            
            Diff patch:
            ```diff
            %s
            ```
            """;

    // ── Stop-words filtered out during keyword extraction ─────────────────────
    private static final List<String> STOP_WORDS = List.of(
            "the", "a", "an", "is", "in", "on", "at", "to", "for", "of", "and", "or",
            "with", "this", "that", "it", "as", "be", "by", "are", "was", "were",
            "should", "must", "will", "when", "where", "what", "how", "not", "no"
    );

    private final GitLabClient gitLabClient;
    private final GitService gitService;
    private final LiteLLMClient liteLLMClient;
    private final ReviewHistoryService historyService;
    private final ObjectMapper objectMapper;
    private final AppConfig config;

    public ReviewService(GitLabClient gitLabClient, GitService gitService,
                         LiteLLMClient liteLLMClient, ReviewHistoryService historyService,
                         ObjectMapper objectMapper, AppConfig config) {
        this.gitLabClient   = gitLabClient;
        this.gitService     = gitService;
        this.liteLLMClient  = liteLLMClient;
        this.historyService = historyService;
        this.objectMapper   = objectMapper;
        this.config         = config;
    }

    /**
     * Runs the full review pipeline for the given request.
     *
     * @param request incoming API request
     * @return review response from the LLM
     */
    public ReviewResponse review(ReviewRequest request) {
        // 1. Fetch GitLab issue
        GitLabIssue issue = gitLabClient.fetchIssue(request.issueUrl());
        log.info("Fetched issue #{}: {}", issue.id(), issue.title());

        // 2. Get local diff
        GitDiff diff = gitService.getDiff();
        if (diff.changedFiles().isEmpty()) {
            log.warn("No local changes detected — returning NOT_RESOLVED");
            return new ReviewResponse(
                    "No local changes were found between HEAD and the base branch.",
                    List.of(new Finding("No code changes detected in local branch.", "HIGH")),
                    List.of(),
                    "NOT_RESOLVED",
                    1.0
            );
        }

        // 3. Filter diff to relevant files
        GitDiff filteredDiff = filterDiff(diff, issue);
        if (filteredDiff.changedFiles().isEmpty()) {
            log.warn("No relevant files found after filtering — returning NOT_RESOLVED");
            return new ReviewResponse(
                    "Local changes exist but none appear related to issue: " + issue.title(),
                    List.of(new Finding("None of the changed files match issue keywords.", "HIGH")),
                    diff.changedFiles(),
                    "NOT_RESOLVED",
                    0.9
            );
        }

        // 4. Build prompt and call LLM
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(request.effectiveSkills());
        String userPrompt = buildUserPrompt(issue, filteredDiff, request.issueUrl());
        String llmResponse = liteLLMClient.chat(systemPrompt, userPrompt);

        // 5. Parse LLM JSON response
        ReviewResponse response = parseResponse(llmResponse);

        // 6. Persist review to history for future cycles
        historyService.add(request.issueUrl(), response);

        return response;
    }

    // ── Diff filtering ────────────────────────────────────────────────────────

    /**
     * Keeps only the files (and their diff hunks) that are relevant to the issue.
     *
     * <p>Relevance is determined by checking whether any keyword extracted from the issue
     * title/description appears in the file path.
     */
    GitDiff filterDiff(GitDiff diff, GitLabIssue issue) {
        List<String> keywords = extractKeywords(issue);
        log.debug("Issue keywords for filtering: {}", keywords);

        if (keywords.isEmpty()) {
            // No keywords → keep everything
            return diff;
        }

        List<String> relevantFiles = diff.changedFiles().stream()
                .filter(file -> isRelevant(file, keywords))
                .collect(Collectors.toList());

        if (relevantFiles.isEmpty()) {
            return new GitDiff(List.of(), "", false);
        }

        // Re-filter the patch to include only hunks for relevant files
        String filteredPatch = filterPatch(diff.patch(), relevantFiles);
        return new GitDiff(relevantFiles, filteredPatch, diff.truncated());
    }

    /**
     * Extracts lower-cased, alphabetic tokens from the issue title and description,
     * removing stop-words and very short tokens.
     */
    List<String> extractKeywords(GitLabIssue issue) {
        String combined = (issue.title() + " " + issue.description()).toLowerCase(Locale.ROOT);
        return Arrays.stream(combined.split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2)
                .filter(w -> !STOP_WORDS.contains(w))
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean isRelevant(String filePath, List<String> keywords) {
        String lowerPath = filePath.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(lowerPath::contains);
    }

    /**
     * Returns the subset of the unified diff that touches only {@code relevantFiles}.
     * A file section starts with {@code diff --git} and ends just before the next such line.
     */
    private String filterPatch(String fullPatch, List<String> relevantFiles) {
        if (fullPatch == null || fullPatch.isBlank()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        String[] lines = fullPatch.split("\n", -1);
        boolean inRelevant = false;

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                inRelevant = relevantFiles.stream()
                        .anyMatch(f -> line.contains(f));
            }
            if (inRelevant) {
                result.append(line).append("\n");
            }
        }
        return result.toString();
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    private String buildUserPrompt(GitLabIssue issue, GitDiff diff, String issueUrl) {
        String fileList = diff.changedFiles().stream()
                .map(f -> "- " + f)
                .collect(Collectors.joining("\n"));

        String baseBranch = config.getGit().getBaseBranch();
        String historyBlock = historyService.formatHistory(issueUrl);

        return USER_PROMPT_TEMPLATE.formatted(
                historyBlock,
                issue.title(),
                issue.description(),
                baseBranch,
                diff.changedFiles().size(),
                fileList,
                diff.patch()
        );
    }

    // ── LLM response parsing ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ReviewResponse parseResponse(String content) {
        try {
            Map<String, Object> map = objectMapper.readValue(content,
                    new TypeReference<>() {});

            String summary = getString(map, "summary", "N/A");
            List<Finding> gaps = findingList(map, "gaps");
            List<String> unrelated = getStringList(map, "unrelated_changes");
            String verdict = getString(map, "verdict", "NOT_RESOLVED");
            double confidence = getDouble(map, "confidence", 0.0);

            // Normalise verdict to known values
            if (!List.of("FULLY_RESOLVED", "PARTIALLY_RESOLVED", "NOT_RESOLVED").contains(verdict)) {
                log.warn("Unknown verdict '{}' from LLM — defaulting to NOT_RESOLVED", verdict);
                verdict = "NOT_RESOLVED";
            }
            confidence = Math.min(1.0, Math.max(0.0, confidence));

            return new ReviewResponse(summary, gaps, unrelated, verdict, confidence);

        } catch (Exception ex) {
            throw new LLMException("Failed to parse LLM JSON response: " + ex.getMessage(), ex);
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultVal;
    }

    @SuppressWarnings("unchecked")
    private List<Finding> findingList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof Map)
                    .map(o -> {
                        Map<String, Object> entry = (Map<String, Object>) o;
                        String desc = entry.get("description") instanceof String s ? s : String.valueOf(entry.get("description"));
                        String sev = entry.get("severity") instanceof String sv ? sv.toUpperCase(Locale.ROOT) : "MEDIUM";
                        if (!List.of("CRITICAL", "HIGH", "MEDIUM", "LOW").contains(sev)) {
                            sev = "MEDIUM";
                        }
                        return new Finding(desc, sev);
                    })
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof String)
                    .map(o -> (String) o)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        return defaultVal;
    }
}

