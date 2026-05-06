package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Reads project-level reviewer configuration from a {@code .mrreviewer.yml} file in the
 * root of the git working directory.
 *
 * <p>This lets every developer on the same project share the same domain-specific reviewer
 * focus without having to pass {@code reviewerSkills} on every API request.
 *
 * <h2>Supported keys</h2>
 * <pre>
 * # .mrreviewer.yml
 * skills: "Security vulnerabilities, 3DS protocol correctness, thread-safety"
 * </pre>
 *
 * <p>When the file is absent or the {@code skills} key is missing / blank, {@code null} is
 * returned so the caller can fall back to the request-level or built-in default skills.
 */
@Service
public class ProjectConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigService.class);

    /** Name of the per-project configuration file resolved relative to the git working dir. */
    static final String CONFIG_FILE_NAME = ".mrreviewer.yml";

    private final AppConfig config;

    public ProjectConfigService(AppConfig config) {
        this.config = config;
    }

    /**
     * Reads the {@code skills} value from {@code .mrreviewer.yml} in the git working directory.
     *
     * @return the configured skills string, or {@code null} if the file is absent / skills key missing
     */
    public String loadSkills() {
        File configFile = new File(config.getGit().getWorkingDir(), CONFIG_FILE_NAME);
        if (!configFile.exists()) {
            log.debug("{} not found in {} — using fallback skills", CONFIG_FILE_NAME,
                    config.getGit().getWorkingDir());
            return null;
        }

        log.debug("Loading project reviewer config from {}", configFile.getAbsolutePath());
        try (InputStream is = new FileInputStream(configFile)) {
            Yaml yaml = new Yaml();
            Object doc = yaml.load(is);
            if (!(doc instanceof Map<?, ?> map)) {
                log.warn("{} is not a YAML mapping — ignoring", CONFIG_FILE_NAME);
                return null;
            }
            Object skills = map.get("skills");
            if (skills instanceof String s && !s.isBlank()) {
                log.info("Using project-level reviewer skills from {}", CONFIG_FILE_NAME);
                return s.strip();
            }
            log.debug("{} has no 'skills' key — using fallback skills", CONFIG_FILE_NAME);
            return null;
        } catch (IOException ex) {
            log.warn("Failed to read {} — using fallback skills: {}", CONFIG_FILE_NAME, ex.getMessage());
            return null;
        }
    }
}
