package com.opshub.operation;

import com.opshub.operation.application.OperationService;
import com.opshub.operation.application.RevisionConflictException;
import com.opshub.operation.application.SaveOaCommand;
import com.opshub.operation.application.UnsupportedPlatformException;
import com.opshub.operation.domain.Operation;
import com.opshub.operation.domain.OperationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OperationServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private OperationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsADraftOperationAtRevisionOne() {
        Operation operation = service.create("MOB-123");

        assertThat(operation.getJiraId()).isEqualTo("MOB-123");
        assertThat(operation.getRevision()).isOne();
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.DRAFT);
    }

    @Test
    void rejectsNonAndroidOfficialAccounts() {
        Operation operation = service.create("MOB-124");

        assertThatThrownBy(() -> service.replaceOas(operation.getId(), 1, List.of(
                oa("IOS", "iOS account")
        ))).isInstanceOf(UnsupportedPlatformException.class);
    }

    @Test
    void replacesOfficialAccountsInProvidedOrderAndInvalidatesDownstreamReferences() {
        Operation operation = service.create("MOB-125");
        UUID validationRunId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID approvedPlanId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO validation_runs (id, operation_id, source_revision, policy_version, status)
                VALUES (?, ?, 1, 'policy-v1', 'VALIDATED')
                """, validationRunId, operation.getId());
        jdbcTemplate.update("""
                INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                VALUES (?, ?, 1, 'catalog-v1', 'READY', 'APPROVED')
                """, planId, operation.getId());
        jdbcTemplate.update("""
                INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                VALUES (?, ?, 1, 'catalog-v1', 'READY', 'APPROVED')
                """, approvedPlanId, operation.getId());
        jdbcTemplate.update("""
                UPDATE operations
                SET validation_run_id = ?, plan_id = ?, approved_plan_id = ?
                WHERE id = ?
                """, validationRunId, planId, approvedPlanId, operation.getId());

        Operation updated = service.replaceOas(operation.getId(), 1, List.of(
                oa("ANDROID", "Second account"),
                oa("ANDROID", "First account")
        ));

        assertThat(updated.getRevision()).isEqualTo(2);
        assertThat(updated.getStatus()).isEqualTo(OperationStatus.DRAFT);
        assertThat(updated.getValidationRunId()).isNull();
        assertThat(updated.getPlanId()).isNull();
        assertThat(updated.getApprovedPlanId()).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM validation_runs WHERE id = ?", String.class, validationRunId))
                .isEqualTo("INVALID");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM test_plans WHERE id = ?", String.class, planId))
                .isEqualTo("INVALID");
        assertThat(jdbcTemplate.queryForObject("SELECT approval_status FROM test_plans WHERE id = ?", String.class, approvedPlanId))
                .isEqualTo("INVALID");
        assertThat(updated.getOfficialAccounts())
                .extracting(account -> account.getOaName())
                .containsExactly("Second account", "First account");
        assertThat(updated.getOfficialAccounts())
                .extracting(account -> account.getOaOrder())
                .containsExactly(1, 2);
    }

    @Test
    void rejectsAStaleOfficialAccountReplacementWithTheCurrentRevision() {
        Operation operation = service.create("MOB-126");
        service.replaceOas(operation.getId(), 1, List.of(oa("ANDROID", "Current account")));

        assertThatThrownBy(() -> service.replaceOas(operation.getId(), 1, List.of(oa("ANDROID", "Stale account"))))
                .isInstanceOfSatisfying(RevisionConflictException.class,
                        conflict -> assertThat(conflict.getCurrentRevision()).isEqualTo(2));
    }

    private static SaveOaCommand oa(String platform, String oaName) {
        return new SaveOaCommand(
                platform,
                oaName,
                "https://example.test/thumbnail.png",
                "Expected header\nExpected body",
                "Open now",
                "https://example.test/redirect"
        );
    }
}
