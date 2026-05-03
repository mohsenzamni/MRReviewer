package com.mohsenzamni.mrreviewer.client;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import com.mohsenzamni.mrreviewer.dto.GitLabIssue;
import com.mohsenzamni.mrreviewer.exception.GitLabException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches GitLab issues via the GitLab REST API v4.
 *
 * <p>The token is sent in the {@code PRIVATE-TOKEN} header and is never logged.
 */
@Component
public class GitLabClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabClient.class);

    /**
     * Captures {@code <host>}, {@code <projectPath>} (may contain slashes) and {@code <issueId>}
     * from URLs like {@code https://gitlab.com/group/sub/project/-/issues/42}.
     */
    private static final Pattern ISSUE_URL_PATTERN =
            Pattern.compile("^(https?://[^/]+)/(.+)/-/issues/(\\d+)$");

    private final RestTemplate restTemplate;
    private final AppConfig config;

    public GitLabClient(RestTemplate restTemplate, AppConfig config) {
        this.restTemplate = restTemplate;
        this.config = config;
    }

    /**
     * Fetches the issue identified by {@code issueUrl}.
     *
     * @param issueUrl full GitLab issue URL
     * @return the issue metadata
     * @throws GitLabException if the URL is invalid, the issue is not found, or the API fails
     */
    public GitLabIssue fetchIssue(String issueUrl) {
        Matcher matcher = ISSUE_URL_PATTERN.matcher(issueUrl.trim());
        if (!matcher.matches()) {
            throw new GitLabException("Invalid GitLab issue URL: " + issueUrl);
        }

        String projectPath = matcher.group(2);   // e.g. "group/sub/project"
        String issueId     = matcher.group(3);   // e.g. "42"

        String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
        String apiUrl = config.getGitlab().getBaseUrl()
                + "/api/v4/projects/" + encodedPath
                + "/issues/" + issueId;

        log.info("Fetching GitLab issue {} for project '{}'", issueId, projectPath);

        try {
            HttpHeaders headers = buildHeaders();
            HttpEntity<Void> request = new HttpEntity<>(headers);
            ResponseEntity<GitLabIssueApiResponse> response =
                    restTemplate.exchange(URI.create(apiUrl), HttpMethod.GET, request,
                            GitLabIssueApiResponse.class);

            GitLabIssueApiResponse body = response.getBody();
            if (body == null) {
                throw new GitLabException("Empty response from GitLab API for issue " + issueId);
            }
            return new GitLabIssue(body.iid(), body.title(),
                    body.description() != null ? body.description() : "");

        } catch (HttpClientErrorException.NotFound ex) {
            throw new GitLabException("GitLab issue not found: " + issueUrl);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden ex) {
            throw new GitLabException(
                    "Access denied to GitLab issue (check PRIVATE-TOKEN): " + issueUrl);
        } catch (RestClientException ex) {
            throw new GitLabException("Failed to fetch GitLab issue: " + ex.getMessage(), ex);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        String token = config.getGitlab().getToken();
        if (token != null && !token.isBlank()) {
            // Token is intentionally NOT logged
            headers.set("PRIVATE-TOKEN", token);
        }
        return headers;
    }

    // ── Internal DTO for the raw GitLab API response ─────────────────────────

    private record GitLabIssueApiResponse(
            long iid,
            String title,
            String description
    ) {}
}
