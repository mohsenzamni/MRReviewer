package com.mohsenzamni.mrreviewer.service;

import com.mohsenzamni.mrreviewer.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectConfigServiceTest {

    @TempDir
    Path tempDir;

    private AppConfig config;
    private ProjectConfigService service;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        config.getGit().setWorkingDir(tempDir.toAbsolutePath().toString());
        service = new ProjectConfigService(config);
    }

    // ── File absent ───────────────────────────────────────────────────────────

    @Test
    void loadSkills_returnsNull_whenConfigFileAbsent() {
        assertThat(service.loadSkills()).isNull();
    }

    // ── Skills key present ────────────────────────────────────────────────────

    @Test
    void loadSkills_returnsSkills_whenKeyPresent() throws IOException {
        writeConfig("skills: \"Security, Performance, SOLID\"\n");

        assertThat(service.loadSkills()).isEqualTo("Security, Performance, SOLID");
    }

    @Test
    void loadSkills_stripsLeadingAndTrailingWhitespace() throws IOException {
        writeConfig("skills: \"  Security, Performance  \"\n");

        assertThat(service.loadSkills()).isEqualTo("Security, Performance");
    }

    @Test
    void loadSkills_handlesMultiLineYaml() throws IOException {
        writeConfig("""
                # MRReviewer project configuration
                skills: "3DS protocol correctness, thread-safety, idempotency"
                other_key: ignored
                """);

        assertThat(service.loadSkills()).isEqualTo("3DS protocol correctness, thread-safety, idempotency");
    }

    // ── Skills key absent or blank ────────────────────────────────────────────

    @Test
    void loadSkills_returnsNull_whenSkillsKeyAbsent() throws IOException {
        writeConfig("some_other_key: value\n");

        assertThat(service.loadSkills()).isNull();
    }

    @Test
    void loadSkills_returnsNull_whenSkillsValueIsBlank() throws IOException {
        writeConfig("skills: \"   \"\n");

        assertThat(service.loadSkills()).isNull();
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void loadSkills_returnsNull_whenFileIsEmpty() throws IOException {
        writeConfig("");

        assertThat(service.loadSkills()).isNull();
    }

    @Test
    void loadSkills_returnsNull_whenFileContainsOnlyComments() throws IOException {
        writeConfig("# Just a comment\n# No actual config\n");

        assertThat(service.loadSkills()).isNull();
    }

    @Test
    void loadSkills_returnsNull_whenSkillsValueIsNotAString() throws IOException {
        // YAML value is a list, not a string — should be gracefully ignored
        writeConfig("skills:\n  - item1\n  - item2\n");

        assertThat(service.loadSkills()).isNull();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void writeConfig(String content) throws IOException {
        Files.writeString(tempDir.resolve(ProjectConfigService.CONFIG_FILE_NAME), content);
    }
}
