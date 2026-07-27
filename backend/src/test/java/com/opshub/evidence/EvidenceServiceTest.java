package com.opshub.evidence;

import com.opshub.evidence.application.EvidenceProperties;
import com.opshub.evidence.application.EvidenceService;
import com.opshub.evidence.application.EvidenceValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class EvidenceServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path evidenceRoot;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("opshub.evidence.root", evidenceRoot::toString);
        registry.add("opshub.evidence.max-bytes", () -> "1000");
    }

    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private EvidenceProperties evidenceProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID testResultId;

    @BeforeEach
    void seedTestResult() {
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO operations (id, jira_id, revision, status, created_at, updated_at)
                        VALUES (?, 'MOB-700', 1, 'APPROVED', now(), now())
                        """, operationId);
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, 1, 'catalog-v1', 'READY', 'APPROVED')
                        """, planId, operationId);
        UUID testCaseId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                        VALUES (?, ?, 1, 1, 'android-oa-delivery-v1', 1, 'sha', '{}', 'READY')
                        """, testCaseId, planId);
        UUID executionId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO executions (id, operation_id, plan_id, source_revision, idempotency_key, status, queued_at)
                        VALUES (?, ?, ?, 1, ?, 'RUNNING', now())
                        """, executionId, operationId, planId, "evidence-key-" + UUID.randomUUID());
        testResultId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO test_results (id, execution_id, test_case_id, attempt, status, duration_ms)
                        VALUES (?, ?, ?, 1, 'PASSED', 100)
                        """, testResultId, executionId, testCaseId);
    }

    @Test
    void storesEvidenceUnderAGeneratedNameAndPersistsMetadata() throws Exception {
        byte[] content = "screenshot-bytes".getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(content);

        UUID evidenceId = evidenceService.store(testResultId, "SCREENSHOT", "../../evil/../device.png",
                content.length, sha256, new ByteArrayInputStream(content));

        String relativePath = jdbcTemplate.queryForObject(
                "SELECT relative_path FROM evidence WHERE id = ?", String.class, evidenceId);
        assertThat(relativePath).doesNotContain("..").doesNotContain("evil").doesNotContain("device.png");
        assertThat(Files.exists(evidenceRoot.resolve(relativePath))).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT checksum FROM evidence WHERE id = ?", String.class, evidenceId))
                .isEqualToIgnoringCase(sha256);
    }

    @Test
    void rejectsAnUnsupportedFileType() {
        byte[] content = "not-a-screenshot".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> evidenceService.store(testResultId, "SCREENSHOT", "device.exe",
                content.length, sha256Hex(content), new ByteArrayInputStream(content)))
                .isInstanceOf(EvidenceValidationException.class);
    }

    @Test
    void rejectsFilesLargerThanTheConfiguredMaximum() {
        byte[] content = new byte[2000];

        assertThatThrownBy(() -> evidenceService.store(testResultId, "SCREENSHOT", "device.png",
                content.length, sha256Hex(content), new ByteArrayInputStream(content)))
                .isInstanceOf(EvidenceValidationException.class);
    }

    @Test
    void rejectsAMismatchedDeclaredChecksum() {
        byte[] content = "screenshot-bytes".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> evidenceService.store(testResultId, "SCREENSHOT", "device.png",
                content.length, "0000000000000000000000000000000000000000000000000000000000000000",
                new ByteArrayInputStream(content)))
                .isInstanceOf(EvidenceValidationException.class);
    }

    private static String sha256Hex(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
