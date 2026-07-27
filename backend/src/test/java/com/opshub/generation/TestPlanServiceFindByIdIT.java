package com.opshub.generation;

import com.opshub.generation.application.TestPlanNotFoundException;
import com.opshub.generation.application.TestPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-DB coverage for {@link TestPlanService#findById}, the read path backing the browser-facing
 * {@code GET /api/v1/plans/{planId}} endpoint added to close Task 11's live-status gap. The rest of
 * {@link TestPlanService} is covered against a fake JdbcTemplate in {@link TestPlanServiceTest}; this
 * one query is exercised against a real Postgres instance because it round-trips the jsonb
 * {@code parameters} column through Jackson.
 */
@SpringBootTest
@Testcontainers
class TestPlanServiceFindByIdIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TestPlanService testPlanService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void returnsThePlanAndItsCasesInOrder() {
        UUID operationId = createDraftOperation("MOB-700");
        UUID planId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, 1, 'catalog-v1', 'READY', 'PENDING')
                        """, planId, operationId);
        UUID firstCase = UUID.randomUUID();
        UUID secondCase = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                        VALUES (?, ?, 1, 1, 'android-template-1', 1, 'sha', CAST(? AS jsonb), 'READY')
                        """, firstCase, planId, sampleParametersJson());
        jdbcTemplate.update("""
                        INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                        VALUES (?, ?, 1, 2, 'android-template-2', 1, 'sha', CAST(? AS jsonb), 'READY')
                        """, secondCase, planId, sampleParametersJson());

        TestPlanService.TestPlanDto plan = testPlanService.findById(planId);

        assertThat(plan.planId()).isEqualTo(planId);
        assertThat(plan.operationId()).isEqualTo(operationId);
        assertThat(plan.status()).isEqualTo("READY");
        assertThat(plan.approvalStatus()).isEqualTo("PENDING");
        assertThat(plan.cases()).hasSize(2);
        assertThat(plan.cases().get(0).testCaseId()).isEqualTo(firstCase);
        assertThat(plan.cases().get(1).testCaseId()).isEqualTo(secondCase);
        assertThat(plan.cases().get(0).parameters().oaName()).isEqualTo("Test OA");
    }

    @Test
    void throwsWhenThePlanDoesNotExist() {
        assertThatThrownBy(() -> testPlanService.findById(UUID.randomUUID()))
                .isInstanceOf(TestPlanNotFoundException.class);
    }

    private String sampleParametersJson() {
        return """
                {"oaName":"Test OA","thumbnailUrl":"https://example.com/thumb.png",
                 "expectedHeader":"Header","expectedBody":"Body","expectedButtonText":"Button",
                 "expectedRedirectUrl":"https://example.com/redirect","expectedRedirectDomain":"example.com"}
                """;
    }

    private UUID createDraftOperation(String jiraId) {
        UUID operationId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO operations (id, jira_id, revision, status, created_at, updated_at)
                        VALUES (?, ?, 1, 'DRAFT', now(), now())
                        """, operationId, jiraId);
        return operationId;
    }
}
