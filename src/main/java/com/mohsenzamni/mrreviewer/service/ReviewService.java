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
import java.util.HashMap;
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
 *   <li>Parse the JSON response, auto-assign finding IDs, persist to history, and return a {@link ReviewResponse}.</li>
 * </ol>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    // ── Severity ordering ─────────────────────────────────────────────────────
    private static final List<String> SEVERITY_ORDER = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    private static final Map<String, String> SEVERITY_ID_PREFIX;
    static {
        SEVERITY_ID_PREFIX = new HashMap<>();
        SEVERITY_ID_PREFIX.put("CRITICAL", "C");
        SEVERITY_ID_PREFIX.put("HIGH", "H");
        SEVERITY_ID_PREFIX.put("MEDIUM", "M");
        SEVERITY_ID_PREFIX.put("LOW", "L");
    }

    // ── Prompt constants ──────────────────────────────────────────────────────

    /**
     * System prompt template. {@code %s} is replaced with the caller-supplied reviewer skills.
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a senior software engineer performing a thorough pre-push code review.
            
            Your reviewer focus areas / skill set:
            %s
            
            You will receive:
            - A GitLab issue (title + description).
            - Optionally, a history of previous review cycles for this issue.
            - A git diff of the current local changes.
            
            ## Output format
            
            Produce ONLY a single valid JSON object — no markdown, no prose outside the JSON.
            
            ### Executive summary rules
            The "summary" field must follow this exact professional format:
            "This MR delivers fixes for issue [issue title] — [X]/[Y] items are addressed: [comma-separated list of addressed items in brief]. [history sentence]."
            - X = number of acceptance criteria addressed by this diff
            - Y = total number of acceptance criteria identified in the issue
            - history sentence = "No previous automated reviews exist on this MR — this is the first review." OR "Previous review cycle [N] raised [K] findings; [how many] have been addressed in this cycle."
            
            ### Acceptance criteria rules
            - Read the issue description carefully and extract every distinct acceptance criterion or required behaviour as a separate item.
            - For each item decide: is it addressed (fully or partially) by the diff?
            - List addressed items in "addressed_items".
            - Total count goes in "total_items".
            
            ### Finding rules
            Each entry in "gaps" represents a problem found in the diff:
            - "severity": one of CRITICAL, HIGH, MEDIUM, LOW
              * CRITICAL: correctness bug, data corruption, or security vulnerability
              * HIGH: significant functional gap or likely bug that will surface at runtime
              * MEDIUM: partial/questionable implementation, missing test, design issue
              * LOW: minor improvement, style, naming, or non-blocking nit
            - "file_location": "FileName.java:startLine-endLine" or "FileName.java:line" — the exact location in the diff. Use null if not pinpointable.
            - "description": detailed explanation. Include the method name, the problematic lines, and WHY it is a problem (e.g. Hibernate dirty-tracking, race condition, NPE path).
            - "recommendation": concrete, actionable advice on how to fix it.
            
            ### JSON schema (strict)
            {
              "summary": "<executive summary string>",
              "addressed_items": ["<string>", ...],
              "total_items": <integer>,
              "gaps": [
                {
                  "severity": "CRITICAL|HIGH|MEDIUM|LOW",
                  "file_location": "<FileName.java:line> or null",
                  "description": "<detailed description>",
                  "recommendation": "<actionable fix>"
                },
                ...
              ],
              "unrelated_changes": ["<string>", ...],
              "verdict": "FULLY_RESOLVED | PARTIALLY_RESOLVED | NOT_RESOLVED",
              "confidence": <number 0.0-1.0>
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
                    List.of(),
                    0,
                    List.of(new Finding("C1", "CRITICAL", null,
                            "No code changes detected in local branch.",
                            "Ensure you are running the reviewer from the correct git working directory.")),
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
                    List.of(),
                    0,
                    List.of(new Finding("H1", "HIGH", null,
                            "None of the changed files match issue keywords.",
                            "Verify that the correct branch is checked out and that the changes target the files described in the issue.")),
                    diff.changedFiles(),
                    "NOT_RESOLVED",
                    0.9
            );
        }

        // 4. Build prompt and call LLM
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(request.effectiveSkills());
        String userPrompt = buildUserPrompt(issue, filteredDiff, request.issueUrl());
        String llmResponse = liteLLMClient.chat(systemPrompt, userPrompt);

        // 5. Parse LLM JSON response and auto-assign finding IDs
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
                inRelevant = relevantFiles.stream().anyMatch(f -> line.contains(f));
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

    /**
     * Strips a markdown code fence wrapper (e.g. {@code ```json ... ```}) from the LLM output,
     * returning the bare JSON string. If no fence is present the input is returned unchanged.
     */
    private static String stripMarkdownCodeFence(String content) {
        if (content == null) {
            return content;
        }
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline < 0) {
                return trimmed;
            }
            String afterFenceOpen = trimmed.substring(firstNewline + 1);
            if (afterFenceOpen.endsWith("```")) {
                return afterFenceOpen.substring(0, afterFenceOpen.length() - 3).strip();
            }
        }
        return content;
    }

    @SuppressWarnings("unchecked")
    private ReviewResponse parseResponse(String content) {
        try {
            Map<String, Object> map = objectMapper.readValue(
                    stripMarkdownCodeFence(content), new TypeReference<>() {});

            String summary         = getString(map, "summary", "N/A");
            List<String> addressed = getStringList(map, "addressed_items");
            int totalItems         = getInt(map, "total_items", addressed.size());
            List<Finding> gaps     = findingList(map, "gaps");
            List<String> unrelated = getStringList(map, "unrelated_changes");
            String verdict         = getString(map, "verdict", "NOT_RESOLVED");
            double confidence      = getDouble(map, "confidence", 0.0);

            // Normalise verdict
            if (!List.of("FULLY_RESOLVED", "PARTIALLY_RESOLVED", "NOT_RESOLVED").contains(verdict)) {
                log.warn("Unknown verdict '{}' from LLM — defaulting to NOT_RESOLVED", verdict);
                verdict = "NOT_RESOLVED";
            }
            confidence = Math.min(1.0, Math.max(0.0, confidence));

            return new ReviewResponse(summary, addressed, totalItems, gaps, unrelated, verdict, confidence);

        } catch (Exception ex) {
            throw new LLMException("Failed to parse LLM JSON response: " + ex.getMessage(), ex);
        }
    }

    /**
     * Parses the "gaps" array from the LLM response and auto-assigns short IDs
     * (C1, C2 … H1, H2 … M1 … L1 …) sorted by severity order.
     */
    @SuppressWarnings("unchecked")
    private List<Finding> findingList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof List<?> list)) {
            return Collections.emptyList();
        }

        // Parse raw entries
        List<Finding> raw = list.stream()
                .filter(o -> o instanceof Map)
                .map(o -> {
                    Map<String, Object> entry = (Map<String, Object>) o;
                    String sev = normaliseSeverity(
                            entry.get("severity") instanceof String sv ? sv : "MEDIUM");
                    String fileLoc = entry.get("file_location") instanceof String fl ? fl : null;
                    String desc = entry.get("description") instanceof String d ? d
                            : String.valueOf(entry.getOrDefault("description", ""));
                    String rec = entry.get("recommendation") instanceof String r ? r : "";
                    // id will be assigned below
                    return new Finding(null, sev, fileLoc, desc, rec);
                })
                .sorted((a, b) -> {
                    int ia = SEVERITY_ORDER.indexOf(a.severity());
                    int ib = SEVERITY_ORDER.indexOf(b.severity());
                    return Integer.compare(ia < 0 ? 99 : ia, ib < 0 ? 99 : ib);
                })
                .collect(Collectors.toList());

        // Assign IDs per-severity counter
        Map<String, Integer> counters = new HashMap<>();
        return raw.stream()
                .map(f -> {
                    String prefix = SEVERITY_ID_PREFIX.getOrDefault(f.severity(), "F");
                    int n = counters.merge(f.severity(), 1, Integer::sum);
                    return new Finding(prefix + n, f.severity(), f.fileLocation(),
                            f.description(), f.recommendation());
                })
                .collect(Collectors.toList());
    }

    private String normaliseSeverity(String raw) {
        String upper = raw.toUpperCase(Locale.ROOT);
        return SEVERITY_ORDER.contains(upper) ? upper : "MEDIUM";
    }

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val instanceof String s ? s : defaultVal;
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
        return val instanceof Number n ? n.doubleValue() : defaultVal;
    }

    private int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        return val instanceof Number n ? n.intValue() : defaultVal;
    }
}

