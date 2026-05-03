package com.mohsenzamni.mrreviewer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Central application configuration loaded from {@code application.yml}.
 */
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private GitLab gitlab = new GitLab();
    private LiteLLM litellm = new LiteLLM();
    private Git git = new Git();

    // ── Getters / setters (Spring needs setters for @ConfigurationProperties) ──

    public GitLab getGitlab() { return gitlab; }
    public void setGitlab(GitLab gitlab) { this.gitlab = gitlab; }

    public LiteLLM getLitellm() { return litellm; }
    public void setLitellm(LiteLLM litellm) { this.litellm = litellm; }

    public Git getGit() { return git; }
    public void setGit(Git git) { this.git = git; }

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
        private int maxDiffLines = 2000;
        /** Remote branch used as the comparison base. */
        private String baseBranch = "origin/main";

        public String getWorkingDir() { return workingDir; }
        public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

        public int getMaxDiffLines() { return maxDiffLines; }
        public void setMaxDiffLines(int maxDiffLines) { this.maxDiffLines = maxDiffLines; }

        public String getBaseBranch() { return baseBranch; }
        public void setBaseBranch(String baseBranch) { this.baseBranch = baseBranch; }
    }

    // ── Shared beans ──────────────────────────────────────────────────────────

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
