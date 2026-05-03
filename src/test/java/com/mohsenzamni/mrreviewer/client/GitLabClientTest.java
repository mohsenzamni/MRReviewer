package com.mohsenzamni.mrreviewer.client;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.exception.GitLabException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitLabClientTest {

    @Mock
    private RestTemplate restTemplate;

    private AppConfig config;
    private GitLabClient gitLabClient;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        config.getGitlab().setBaseUrl("https://gitlab.com");
        config.getGitlab().setToken("test-token");
        gitLabClient = new GitLabClient(restTemplate, config);
    }

    @Test
    void fetchIssue_throwsGitLabException_forCompletelyInvalidUrl() {
        assertThatThrownBy(() -> gitLabClient.fetchIssue("https://not-a-gitlab-url.com"))
                .isInstanceOf(GitLabException.class)
                .hasMessageContaining("Invalid GitLab issue URL");
    }

    @Test
    void fetchIssue_throwsGitLabException_forUrlWithNonNumericIssueId() {
        assertThatThrownBy(() -> gitLabClient.fetchIssue(
                "https://gitlab.com/group/project/-/issues/abc"))
                .isInstanceOf(GitLabException.class)
                .hasMessageContaining("Invalid GitLab issue URL");
    }

    @Test
    void fetchIssue_throwsGitLabException_forUrlMissingIssuesSegment() {
        assertThatThrownBy(() -> gitLabClient.fetchIssue(
                "https://gitlab.com/group/project/123"))
                .isInstanceOf(GitLabException.class)
                .hasMessageContaining("Invalid GitLab issue URL");
    }

    @Test
    void fetchIssue_throwsGitLabException_onNotFound() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), any(Class.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThatThrownBy(() -> gitLabClient.fetchIssue(
                "https://gitlab.com/group/project/-/issues/1"))
                .isInstanceOf(GitLabException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void fetchIssue_throwsGitLabException_onConnectionError() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(), any(Class.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> gitLabClient.fetchIssue(
                "https://gitlab.com/group/project/-/issues/1"))
                .isInstanceOf(GitLabException.class)
                .hasMessageContaining("Failed to fetch GitLab issue");
    }
}
