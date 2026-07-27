package com.opshub.generation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.generation.domain.TemplateId;
import com.opshub.operation.application.OperationNotFoundException;
import com.opshub.operation.application.RevisionConflictException;
import com.opshub.operation.domain.OfficialAccount;
import com.opshub.operation.domain.Operation;
import com.opshub.operation.domain.OperationStatus;
import com.opshub.validation.application.ContentParser;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TestPlanService {
    public static final String CATALOG_VERSION = "android-v1";

    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final ContentParser contentParser;
    private final ObjectMapper objectMapper;

    public TestPlanService(EntityManager entityManager, JdbcTemplate jdbcTemplate, ContentParser contentParser) {
        this(entityManager, jdbcTemplate, contentParser, new ObjectMapper());
    }

    TestPlanService(EntityManager entityManager, JdbcTemplate jdbcTemplate, ContentParser contentParser, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.contentParser = contentParser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TestPlanDto generate(UUID operationId, int revision) {
        Operation operation = findRequired(operationId);
        if (operation.getRevision() != revision) {
            throw new RevisionConflictException(operation.getRevision());
        }
        requireFullyPassedValidation(operationId, revision);

        UUID planId = UUID.randomUUID();
        List<TestCaseDto> cases = createCases(operation, planId);
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, ?, ?, 'READY', 'PENDING')
                        """, planId, operationId, revision, CATALOG_VERSION);
        for (TestCaseDto testCase : cases) {
            jdbcTemplate.update("""
                            INSERT INTO test_cases (id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status)
                            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                            """, testCase.testCaseId(), planId, testCase.oaOrder(), testCase.order(),
                    testCase.templateId(), testCase.templateVersion(), testCase.templateSha256(),
                    json(testCase.parameters()), testCase.status());
        }
        int updated = jdbcTemplate.update("""
                        UPDATE operations
                        SET status = ?, plan_id = ?, approved_plan_id = null, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND revision = ?
                        """, OperationStatus.READY_FOR_APPROVAL.name(), planId, operationId, revision);
        if (updated == 0) {
            throw new RevisionConflictException(currentRevision(operationId));
        }
        return new TestPlanDto(planId, operationId, revision, CATALOG_VERSION, "READY", "PENDING", List.copyOf(cases));
    }

    @Transactional
    public void approve(UUID planId, int revision) {
        int approved = jdbcTemplate.update("""
                        UPDATE test_plans plan
                        SET approval_status = 'APPROVED'
                        WHERE plan.id = ?
                          AND plan.source_revision = ?
                          AND plan.status = 'READY'
                          AND plan.approval_status = 'PENDING'
                          AND NOT EXISTS (
                              SELECT 1 FROM test_cases test_case
                              WHERE test_case.plan_id = plan.id AND test_case.status <> 'READY'
                          )
                        """, planId, revision);
        if (approved == 0) {
            int currentRevision = currentRevisionForPlan(planId);
            if (currentRevision != revision) {
                throw new RevisionConflictException(currentRevision);
            }
            throw new IllegalStateException("The current plan is not fully ready for approval");
        }
        int updated = jdbcTemplate.update("""
                        UPDATE operations operation
                        SET status = ?, approved_plan_id = ?
                        WHERE operation.plan_id = ? AND operation.revision = ?
                        """, OperationStatus.APPROVED.name(), planId, planId, revision);
        if (updated == 0) {
            throw new RevisionConflictException(currentRevisionForPlan(planId));
        }
    }

    private List<TestCaseDto> createCases(Operation operation, UUID planId) {
        List<TestCaseDto> cases = new ArrayList<>();
        for (OfficialAccount account : operation.getOfficialAccounts()) {
            TemplateParameters parameters = parametersFor(account);
            for (TemplateId template : TemplateId.values()) {
                cases.add(new TestCaseDto(
                        UUID.randomUUID(), planId, account.getOaOrder(), template.ordinal() + 1,
                        template.id(), template.version(), template.sha256(), parameters, "READY"
                ));
            }
        }
        return cases;
    }

    private TemplateParameters parametersFor(OfficialAccount account) {
        ContentParser.ParsedContent content = contentParser.parse(account.getContent());
        try {
            URI redirect = new URI(account.getRedirectUrl());
            if (redirect.getHost() == null || redirect.getHost().isBlank()) {
                throw new IllegalStateException("Validated redirect URL has no hostname");
            }
            return new TemplateParameters(
                    account.getOaName(), account.getThumbnailUrl(), content.header(), content.body(),
                    account.getButtonText(), account.getRedirectUrl(), redirect.getHost()
            );
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Validated redirect URL is malformed", exception);
        }
    }

    private void requireFullyPassedValidation(UUID operationId, int revision) {
        Integer passingRuns = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM validation_runs
                        WHERE operation_id = ? AND source_revision = ? AND status = 'VALIDATED'
                        """, Integer.class, operationId, revision);
        Integer nonPassedFindings = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM field_findings finding
                        JOIN validation_runs validation_run ON validation_run.id = finding.validation_run_id
                        WHERE validation_run.operation_id = ?
                          AND validation_run.source_revision = ?
                          AND validation_run.status = 'VALIDATED'
                          AND finding.status <> 'PASSED'
                        """, Integer.class, operationId, revision);
        if (!isGenerationAllowed(passingRuns, nonPassedFindings)) {
            throw new IllegalStateException("Every validation finding must be PASSED before generating tests");
        }
    }

    public static boolean isGenerationAllowed(Integer passingRuns, Integer nonPassedFindings) {
        return passingRuns != null && passingRuns == 1 && nonPassedFindings != null && nonPassedFindings == 0;
    }

    private Operation findRequired(UUID operationId) {
        return entityManager.createQuery("""
                        SELECT DISTINCT operation FROM Operation operation
                        LEFT JOIN FETCH operation.officialAccounts
                        WHERE operation.id = :operationId
                        """, Operation.class)
                .setParameter("operationId", operationId)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new OperationNotFoundException(operationId));
    }

    private int currentRevision(UUID operationId) {
        Integer revision = jdbcTemplate.queryForObject("SELECT revision FROM operations WHERE id = ?", Integer.class, operationId);
        if (revision == null) {
            throw new OperationNotFoundException(operationId);
        }
        return revision;
    }

    private int currentRevisionForPlan(UUID planId) {
        Integer revision = jdbcTemplate.queryForObject("""
                        SELECT operation.revision FROM operations operation
                        JOIN test_plans plan ON plan.operation_id = operation.id
                        WHERE plan.id = ?
                        """, Integer.class, planId);
        if (revision == null) {
            throw new IllegalStateException("Test plan not found: " + planId);
        }
        return revision;
    }

    private String json(TemplateParameters parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize template parameters", exception);
        }
    }

    public record TemplateParameters(
            String oaName,
            String thumbnailUrl,
            String expectedHeader,
            String expectedBody,
            String expectedButtonText,
            String expectedRedirectUrl,
            String expectedRedirectDomain
    ) {
    }

    public record TestCaseDto(
            UUID testCaseId,
            UUID planId,
            int oaOrder,
            int order,
            String templateId,
            int templateVersion,
            String templateSha256,
            TemplateParameters parameters,
            String status
    ) {
    }

    public record TestPlanDto(
            UUID planId,
            UUID operationId,
            int sourceRevision,
            String templateCatalogVersion,
            String status,
            String approvalStatus,
            List<TestCaseDto> cases
    ) {
    }
}
