package com.mohsenzamni.mrreviewer.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mohsenzamni.mrreviewer.client.GitLabClient;
import com.mohsenzamni.mrreviewer.client.LiteLLMClient;
import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.AcReview;
import com.mohsenzamni.mrreviewer.dto.Evidence;
import com.mohsenzamni.mrreviewer.dto.GitDiff;
import com.mohsenzamni.mrreviewer.dto.GitLabIssue;
import com.mohsenzamni.mrreviewer.dto.ReviewRequest;
import com.mohsenzamni.mrreviewer.dto.ReviewResponse;
import com.mohsenzamni.mrreviewer.exception.LLMException;
import com.mohsenzamni.mrreviewer.tool.SearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates the full pre-push review pipeline:
 *
 * <ol>
 *   <li>Fetch issue metadata from GitLab.</li>
 *   <li>Retrieve the local git diff.</li>
 *   <li>Run local static-analysis tools (if configured) — non-fatal.</li>
 *   <li>Extract acceptance criteria from the issue via a lightweight LLM call — non-fatal fallback.</li>
 *   <li>Filter the diff to files relevant to the issue.</li>
 *   <li>Gather repository context for the extracted ACs via {@link SearchTool}.</li>
 *   <li>Build a structured prompt (history, issue, ACs, context, static analysis, diff) and call the LLM.</li>
 *   <li>Parse the structured JSON response, persist to history, and return a {@link ReviewResponse}.</li>
 * </ol>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    // ── Stop-words filtered out during keyword extraction ─────────────────────
    private static final List<String> STOP_WORDS = List.of(
            "the", "a", "an", "is", "in", "on", "at", "to", "for", "of", "and", "or",
            "with", "this", "that", "it", "as", "be", "by", "are", "was", "were",
            "should", "must", "will", "when", "where", "what", "how", "not", "no"
    );

    // ── AC extraction prompt ──────────────────────────────────────────────────

    private static final String AC_EXTRACTION_SYSTEM_PROMPT = """
            You are a requirements analyst.
            Extract all acceptance criteria from the GitLab issue description provided by the user.
            Return ONLY a valid JSON object with key "items" whose value is an array of strings.
            Each string is one distinct acceptance criterion.
            Infer criteria from requirements if not stated explicitly. Limit to 10 items.
            Example: {"items": ["Login endpoint returns a signed JWT", "Errors are returned as RFC 7807 problem JSON"]}
            """;

    // ── Review system prompt ──────────────────────────────────────────────────

    /**
     * System prompt template. {@code %s} is replaced with the resolved reviewer skills.
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You are a senior software architect performing a thorough pre-push code review.
            
            Your reviewer focus areas / skill set:
            %s
            
            You will receive:
            - A GitLab issue (title + description).
            - Extracted acceptance criteria (ACs) — one item per required behaviour.
            - Optionally, a history of previous review cycles for this issue.
            - Optionally, static analysis results (checkstyle, spotbugs, test output, etc.).
            - Optionally, repository context: relevant symbol locations found by search.
            - A git diff of the current local changes.
            
            ## Output format
            
            Produce ONLY a single valid JSON object — no markdown, no prose outside the JSON.
            
            ### JSON schema (strict)
            {
              "summary": "<2-3 sentence executive summary: what the MR does, overall quality, AC coverage>",
              "acceptance_criteria_review": [
                {
                  "ac": "<exact acceptance criterion text>",
                  "status": "covered | partial | missing",
                  "evidence": [
                    { "file": "<path/to/File.java>", "lines": "<startLine-endLine or single line>" }
                  ],
                  "issues": ["<specific issue — only when status is partial or missing>"]
                }
              ],
              "risks": ["<cross-cutting risk: security, performance, backward compatibility, race condition, etc.>"],
              "suggestions": ["<non-blocking improvement: code quality, test coverage, design pattern, etc.>"]
            }
            
            ### Rules
            - Include ALL extracted ACs in "acceptance_criteria_review" — one entry per AC.
            - "status" must be exactly "covered", "partial", or "missing" (lowercase).
            - "evidence" must reference actual file paths and line numbers visible in the diff. Use [] if none.
            - "issues" must be specific and actionable. Use [] when status is "covered".
            - "risks" are cross-cutting concerns not tied to a single AC.
            - "suggestions" are non-blocking improvement ideas.
            - Do NOT include prose outside the JSON object.
            """;

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final GitLabClient gitLabClient;
    private final GitService gitService;
    private final LiteLLMClient liteLLMClient;
    private final ReviewHistoryService historyService;
    private final LocalToolsService localToolsService;
    private final ProjectConfigService projectConfigService;
    private final SearchTool searchTool;
    private final ObjectMapper objectMapper;
    private final AppConfig config;

    public ReviewService(GitLabClient gitLabClient, GitService gitService,
                         LiteLLMClient liteLLMClient, ReviewHistoryService historyService,
                         LocalToolsService localToolsService, ProjectConfigService projectConfigService,
                         SearchTool searchTool, ObjectMapper objectMapper, AppConfig config) {
        this.gitLabClient         = gitLabClient;
        this.gitService           = gitService;
        this.liteLLMClient        = liteLLMClient;
        this.historyService       = historyService;
        this.localToolsService    = localToolsService;
        this.projectConfigService = projectConfigService;
        this.searchTool           = searchTool;
        this.objectMapper         = objectMapper;
        this.config               = config;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Runs the full review pipeline for the given request.
     *
     * @param request incoming API request
     * @return structured review response
     */
    public ReviewResponse review(ReviewRequest request) {
        // 1. Fetch GitLab issue
        GitLabIssue issue = gitLabClient.fetchIssue(request.issueUrl());
        log.info("Fetched issue #{}: {}", issue.id(), issue.title());

        // 2. Get local diff
        GitDiff diff = gitService.getDiff();
        if (diff.changedFiles().isEmpty()) {
            log.warn("No local changes detected — returning empty review");
            return noChangesResponse();
        }

        // 3. Run local static-analysis tools (non-fatal — failures are logged and skipped)
        List<LocalToolsService.ToolResult> toolResults = localToolsService.runAll();

        // 4. Extract acceptance criteria (LLM call 1 — lightweight, non-fatal)
        List<String> acs = extractAcs(issue);
        log.info("Extracted {} acceptance criteria for issue #{}", acs.size(), issue.id());

        // 5. Filter diff to relevant files
        GitDiff filteredDiff = filterDiff(diff, issue);
        if (filteredDiff.changedFiles().isEmpty()) {
            log.warn("No relevant files found after filtering — returning early review");
            return noRelevantFilesResponse(diff, issue);
        }

        // 6. Gather repository context (symbol search per AC)
        String codeContext = gatherCodeContext(acs, filteredDiff);

        // 7. Build prompt and call LLM (call 2 — the main review)
        String systemPrompt = SYSTEM_PROMPT_TEMPLATE.formatted(resolveSkills(request));
        String userPrompt   = buildUserPrompt(issue, filteredDiff, acs, codeContext,
                toolResults, request.issueUrl());
        String llmResponse  = liteLLMClient.chat(systemPrompt, userPrompt);

        // 8. Parse structured JSON response
        ReviewResponse response = parseResponse(llmResponse);

        // 9. Persist review to history for future cycles
        historyService.add(request.issueUrl(), response);

        return response;
    }

    // ── Acceptance-criteria extraction ────────────────────────────────────────

    /**
     * Calls the LLM to extract structured acceptance criteria from the issue title and
     * description. Returns an empty list on any failure so the review continues without ACs.
     */
    List<String> extractAcs(GitLabIssue issue) {
        String userPrompt = "Title: " + issue.title() + "\n\nDescription:\n" + issue.description();
        try {
            String raw = liteLLMClient.chat(AC_EXTRACTION_SYSTEM_PROMPT, userPrompt);
            Map<String, Object> parsed = objectMapper.readValue(
                    stripMarkdownCodeFence(raw), new TypeReference<>() {});
            List<String> items = getStringList(parsed, "items");
            if (items.isEmpty()) {
                log.debug("AC extraction returned no items — LLM response: {}", raw);
            }
            return items;
        } catch (Exception ex) {
            log.warn("AC extraction failed — review will proceed without structured ACs: {}", ex.getMessage());
            return List.of();
        }
    }

    // ── Repository context gathering ──────────────────────────────────────────

    /**
     * Searches the repository for symbols mentioned in the extracted ACs and returns a
     * compact Markdown section with the most relevant file + line references.
     *
     * <p>The context is intentionally brief (≤ 15 references) to avoid prompt bloat while
     * still giving the LLM a "map" of the codebase for each requirement.
     */
    String gatherCodeContext(List<String> acs, GitDiff diff) {
        if (acs.isEmpty()) {
            return "";
        }

        Set<String> alreadySearched = new LinkedHashSet<>();
        List<String> contextLines   = new ArrayList<>();
        final int maxRefs = 15;

        for (String ac : acs) {
            if (contextLines.size() >= maxRefs) break;

            // Extract significant words (> 4 chars, alphanumeric) as search terms
            for (String word : ac.split("[\\s,;.()\\/\\[\\]\"']+")) {
                String term = word.replaceAll("[^a-zA-Z0-9]", "");
                if (term.length() <= 4 || !alreadySearched.add(term.toLowerCase(Locale.ROOT))) {
                    continue;
                }

                SearchTool.SearchResult result = searchTool.searchInRepo(term, 3);
                for (SearchTool.SearchMatch match : result.matches()) {
                    contextLines.add("- `" + match.file() + ":" + match.line()
                            + "` — " + truncateLine(match.content(), 100));
                    if (contextLines.size() >= maxRefs) break;
                }
                if (contextLines.size() >= maxRefs) break;
            }
        }

        if (contextLines.isEmpty()) {
            return "";
        }

        return "## Repository Context (relevant symbol locations)\n\n"
                + String.join("\n", contextLines)
                + "\n\n";
    }

    // ── Diff filtering ────────────────────────────────────────────────────────

    /**
     * Keeps only the files (and their diff hunks) that are relevant to the issue.
     * Relevance is determined by keyword matching against the issue title and description.
     */
    GitDiff filterDiff(GitDiff diff, GitLabIssue issue) {
        List<String> keywords = extractKeywords(issue);
        log.debug("Issue keywords for filtering: {}", keywords);

        if (keywords.isEmpty()) {
            return diff;
        }

        List<String> relevantFiles = diff.changedFiles().stream()
                .filter(file -> isRelevant(file, keywords))
                .collect(Collectors.toList());

        if (relevantFiles.isEmpty()) {
            return new GitDiff(List.of(), "", false);
        }

        String filteredPatch = filterPatch(diff.patch(), relevantFiles);
        return new GitDiff(relevantFiles, filteredPatch, diff.truncated());
    }

    /**
     * Extracts lower-cased alphabetic tokens from the issue title and description,
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

    private String buildUserPrompt(GitLabIssue issue, GitDiff diff, List<String> acs,
                                   String codeContext,
                                   List<LocalToolsService.ToolResult> toolResults,
                                   String issueUrl) {
        StringBuilder sb = new StringBuilder();

        // 1. Previous review history (oldest first)
        String historyBlock = historyService.formatHistory(issueUrl);
        if (!historyBlock.isBlank()) {
            sb.append(historyBlock).append("\n");
        }

        // 2. Issue
        sb.append("## Issue\n\n");
        sb.append("**Title:** ").append(issue.title()).append("\n\n");
        sb.append("**Description:**\n").append(issue.description()).append("\n\n");

        // 3. Acceptance Criteria
        sb.append("## Acceptance Criteria\n\n");
        if (!acs.isEmpty()) {
            for (int i = 0; i < acs.size(); i++) {
                sb.append(i + 1).append(". ").append(acs.get(i)).append("\n");
            }
        } else {
            sb.append("_(Infer acceptance criteria from the issue description above.)_\n");
        }
        sb.append("\n");

        // 4. Repository context (symbol search results)
        if (!codeContext.isBlank()) {
            sb.append(codeContext);
        }

        // 5. Static analysis results
        if (toolResults != null && !toolResults.isEmpty()) {
            sb.append(formatStaticAnalysis(toolResults));
        }

        // 6. Git diff
        String baseBranch = config.getGit().getBaseBranch();
        String fileList   = diff.changedFiles().stream()
                .map(f -> "- " + f)
                .collect(Collectors.joining("\n"));

        sb.append("## Git Diff (changes vs ").append(baseBranch).append(")\n\n");
        sb.append("Changed files (").append(diff.changedFiles().size()).append("):\n");
        sb.append(fileList).append("\n\n");
        sb.append("Diff patch:\n```diff\n").append(diff.patch()).append("\n```\n");

        return sb.toString();
    }

    /**
     * Formats static-analysis results as a Markdown section.
     * Returns an empty string when there are no results.
     */
    private static String formatStaticAnalysis(List<LocalToolsService.ToolResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## Static Analysis Results\n\n");
        for (LocalToolsService.ToolResult result : results) {
            sb.append("### ").append(result.name()).append("\n```\n")
              .append(result.output()).append("\n```\n\n");
        }
        return sb.toString();
    }

    // ── Skills resolution ─────────────────────────────────────────────────────

    /**
     * Resolves reviewer skills applying the following priority:
     * <ol>
     *   <li>Explicit {@code reviewerSkills} in the API request (highest).</li>
     *   <li>Project-level skills from {@code .mrreviewer.yml}.</li>
     *   <li>Built-in {@link ReviewRequest#DEFAULT_SKILLS} fallback.</li>
     * </ol>
     */
    private String resolveSkills(ReviewRequest request) {
        if (request.reviewerSkills() != null && !request.reviewerSkills().isBlank()) {
            return request.reviewerSkills();
        }
        String projectSkills = projectConfigService.loadSkills();
        if (projectSkills != null) {
            return projectSkills;
        }
        return ReviewRequest.DEFAULT_SKILLS;
    }

    // ── LLM response parsing ──────────────────────────────────────────────────

    /**
     * Strips a markdown code fence ({@code ```json ... ```}) from the LLM output.
     */
    private static String stripMarkdownCodeFence(String content) {
        if (content == null) return content;
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline < 0) return trimmed;
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

            String          summary     = getString(map, "summary", "N/A");
            List<AcReview>  acReviews   = parseAcReviews(map);
            List<String>    risks       = getStringList(map, "risks");
            List<String>    suggestions = getStringList(map, "suggestions");

            return new ReviewResponse(summary, acReviews, risks, suggestions);

        } catch (Exception ex) {
            throw new LLMException("Failed to parse LLM JSON response: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<AcReview> parseAcReviews(Map<String, Object> map) {
        Object val = map.get("acceptance_criteria_review");
        if (!(val instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(o -> o instanceof Map)
                .map(o -> {
                    Map<String, Object> entry = (Map<String, Object>) o;
                    String         ac       = getString(entry, "ac", "");
                    String         status   = normaliseAcStatus(getString(entry, "status", "missing"));
                    List<Evidence> evidence = parseEvidence(entry);
                    List<String>   issues   = getStringList(entry, "issues");
                    return new AcReview(ac, status, evidence, issues);
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<Evidence> parseEvidence(Map<String, Object> acEntry) {
        Object val = acEntry.get("evidence");
        if (!(val instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(o -> o instanceof Map)
                .map(o -> {
                    Map<String, Object> entry = (Map<String, Object>) o;
                    String file  = getString(entry, "file", "");
                    String lines = getString(entry, "lines", "");
                    return new Evidence(file, lines);
                })
                .collect(Collectors.toList());
    }

    private static String normaliseAcStatus(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT).strip()) {
            case "covered" -> "covered";
            case "partial" -> "partial";
            default        -> "missing";
        };
    }

    // ── Early-return responses ────────────────────────────────────────────────

    private static ReviewResponse noChangesResponse() {
        return new ReviewResponse(
                "No local changes were found between HEAD and the base branch.",
                List.of(),
                List.of("No code changes detected — ensure you are running the reviewer from the correct git working directory."),
                List.of()
        );
    }

    private static ReviewResponse noRelevantFilesResponse(GitDiff diff, GitLabIssue issue) {
        String fileList = String.join(", ", diff.changedFiles());
        return new ReviewResponse(
                "Local changes exist but none appear related to issue: " + issue.title(),
                List.of(),
                List.of("Changed files [" + fileList + "] do not match issue keywords. "
                        + "Verify that the correct branch is checked out and the changes target files described in the issue."),
                List.of()
        );
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

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

    private static String truncateLine(String line, int maxLen) {
        if (line == null) return "";
        return line.length() > maxLen ? line.substring(0, maxLen) + "…" : line;
    }
}
