package com.mohsenzamni.mrreviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Central application configuration loaded from {@code application.yml}.
 */
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private GitLab gitlab = new GitLab();
    private LiteLLM litellm = new LiteLLM();
    private Git git = new Git();
    private LocalTools localTools = new LocalTools();

    // ── Getters / setters (Spring needs setters for @ConfigurationProperties) ──

    public GitLab getGitlab() { return gitlab; }
    public void setGitlab(GitLab gitlab) { this.gitlab = gitlab; }

    public LiteLLM getLitellm() { return litellm; }
    public void setLitellm(LiteLLM litellm) { this.litellm = litellm; }

    public Git getGit() { return git; }
    public void setGit(Git git) { this.git = git; }

    public LocalTools getLocalTools() { return localTools; }
    public void setLocalTools(LocalTools localTools) { this.localTools = localTools; }

    // ── Nested config groups ───────────────────────────────────────────────────

    public static class GitLab {
        private String baseUrl = "https://gitlab.com";
        private String token = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    public static class LiteLLM {
        private String baseUrl = "http://localhost:4000";
        private String apiKey = "";
        private String modelName = "gpt-4o";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }

    public static class Git {
        /** Working directory in which git commands are executed. Defaults to current JVM directory. */
        private String workingDir = ".";
        /** Maximum number of diff lines forwarded to the LLM (prevents huge prompts). */
        private int maxDiffLines = 4000;
        /** Remote branch used as the comparison base. */
        private String baseBranch = "origin/main";
        /**
         * Number of unified context lines included around each changed hunk ({@code git diff -U<n>}).
         * Larger values give the LLM more surrounding code for each change (e.g. full method bodies),
         * at the cost of a larger diff. 50 is a good default for most Java codebases.
         */
        private int contextLines = 50;

        public String getWorkingDir() { return workingDir; }
        public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

        public int getMaxDiffLines() { return maxDiffLines; }
        public void setMaxDiffLines(int maxDiffLines) { this.maxDiffLines = maxDiffLines; }

        public String getBaseBranch() { return baseBranch; }
        public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }

        public int getContextLines() { return contextLines; }
        public void setContextLines(int contextLines) { this.contextLines = contextLines; }
    }

    // ── Shared beans ──────────────────────────────────────────────────────────

    /**
     * Configuration for local static-analysis tools that run in the git working directory.
     * Each tool is a subprocess whose combined stdout/stderr is injected into the LLM prompt.
     */
    public static class LocalTools {

        /**
         * Master switch — when {@code false} no tools are executed regardless of per-tool settings.
         * Defaults to {@code false} so the feature is opt-in.
         */
        private boolean enabled = false;

        /** Seconds each tool subprocess is allowed to run before it is killed. */
        private int timeoutSeconds = 120;

        /** Maximum number of output lines captured per tool before truncation. */
        private int maxOutputLines = 200;

        /** Ordered list of tools to run. */
        private List<Tool> tools = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public int getMaxOutputLines() { return maxOutputLines; }
        public void setMaxOutputLines(int maxOutputLines) { this.maxOutputLines = maxOutputLines; }

        public List<Tool> getTools() { return tools; }
        public void setTools(List<Tool> tools) { this.tools = tools; }

        /** A single configured tool (name + command argv). */
        public static class Tool {

            /** Human-readable label shown in the prompt section header. */
            private String name = "";

            /** Whether this particular tool is active. Defaults to {@code true}. */
            private boolean enabled = true;

            /**
             * Command to run as a discrete argument list — never shell-expanded.
             * Example: {@code ["mvn", "-q", "checkstyle:check"]}.
             */
            private List<String> command = new ArrayList<>();

            public String getName() { return name; }
            public void setName(String name) { this.name = name; }

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public List<String> getCommand() { return command; }
            public void setCommand(List<String> command) { this.command = command; }
        }
    }

    // ── Shared beans ──────────────────────────────────────────────────────────

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
