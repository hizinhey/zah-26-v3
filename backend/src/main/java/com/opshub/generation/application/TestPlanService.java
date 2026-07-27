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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TestPlanService {

    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;
    private final ContentParser contentParser;
    private final ObjectMapper objectMapper;
    private final TemplateReadinessValidator templateReadinessValidator;

    public TestPlanService(EntityManager entityManager, JdbcTemplate jdbcTemplate, ContentParser contentParser,
                           TemplateReadinessValidator templateReadinessValidator) {
        this(entityManager, jdbcTemplate, contentParser, templateReadinessValidator, new ObjectMapper());
    }

    TestPlanService(EntityManager entityManager, JdbcTemplate jdbcTemplate, ContentParser contentParser,
                    TemplateReadinessValidator templateReadinessValidator, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
        this.contentParser = contentParser;
        this.templateReadinessValidator = templateReadinessValidator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TestPlanDto generate(UUID operationId, int revision) {
        Operation operation = findRequired(operationId);
        if (operation.getRevision() != revision) {
            throw new RevisionConflictException(operation.getRevision());
        }
        requireFullyPassedValidation(operationId, revision);
        String catalogVersion = templateReadinessValidator.catalogVersion();

        UUID planId = UUID.randomUUID();
        List<TestCaseDto> cases = createCases(operation, planId);
        String planStatus = cases.stream().allMatch(testCase -> "READY".equals(testCase.status())) ? "READY" : "GENERATION_FAILED";
        jdbcTemplate.update("""
                        INSERT INTO test_plans (id, operation_id, source_revision, template_catalog_version, status, approval_status)
                        VALUES (?, ?, ?, ?, ?, 'PENDING')
                        """, planId, operationId, revision, catalogVersion, planStatus);
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
                        """, "READY".equals(planStatus) ? OperationStatus.READY_FOR_APPROVAL.name() : OperationStatus.GENERATION_FAILED.name(), planId, operationId, revision);
        if (updated == 0) {
            throw new RevisionConflictException(currentRevision(operationId));
        }
        return new TestPlanDto(planId, operationId, revision, catalogVersion, planStatus, "PENDING", List.copyOf(cases));
    }

    public TestPlanDto findById(UUID planId) {
        TestPlanRow plan = jdbcTemplate.query("""
                        SELECT id, operation_id, source_revision, template_catalog_version, status, approval_status
                        FROM test_plans WHERE id = ?
                        """, rs -> rs.next()
                        ? new TestPlanRow((UUID) rs.getObject("id"), (UUID) rs.getObject("operation_id"),
                                rs.getInt("source_revision"), rs.getString("template_catalog_version"),
                                rs.getString("status"), rs.getString("approval_status"))
                        : null,
                planId);
        if (plan == null) {
            throw new TestPlanNotFoundException(planId);
        }
        List<TestCaseDto> cases = jdbcTemplate.query("""
                        SELECT id, plan_id, oa_order, case_order, template_id, template_version, template_sha256, parameters, status
                        FROM test_cases WHERE plan_id = ? ORDER BY oa_order, case_order
                        """, (rs, rowNum) -> {
                    try {
                        Object parameters = objectMapper.readValue(rs.getString("parameters"), TemplateParameters.class);
                        return new TestCaseDto(
                                (UUID) rs.getObject("id"), (UUID) rs.getObject("plan_id"), rs.getInt("oa_order"), rs.getInt("case_order"),
                                rs.getString("template_id"), rs.getInt("template_version"), rs.getString("template_sha256"),
                                (TemplateParameters) parameters, rs.getString("status"), null);
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("Could not parse stored template parameters", exception);
                    }
                }, planId);
        return new TestPlanDto(plan.id(), plan.operationId(), plan.sourceRevision(), plan.templateCatalogVersion(),
                plan.status(), plan.approvalStatus(), cases);
    }

    private record TestPlanRow(UUID id, UUID operationId, int sourceRevision, String templateCatalogVersion,
                                String status, String approvalStatus) {
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
                TemplateReadinessValidator.Readiness readiness;
                try {
                    readiness = templateReadinessValidator.validate(template, parameters);
                } catch (RuntimeException exception) {
                    readiness = TemplateReadinessValidator.Readiness.notReady("Template readiness validation failed");
                }
                cases.add(new TestCaseDto(
                        UUID.randomUUID(), planId, account.getOaOrder(), template.ordinal() + 1,
                        template.id(), template.version(), template.sha256(), parameters,
                        readiness.ready() ? "READY" : "NOT_READY", readiness.reason()
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
        public Map<String, String> asMap() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("oaName", oaName);
            values.put("thumbnailUrl", thumbnailUrl);
            values.put("expectedHeader", expectedHeader);
            values.put("expectedBody", expectedBody);
            values.put("expectedButtonText", expectedButtonText);
            values.put("expectedRedirectUrl", expectedRedirectUrl);
            values.put("expectedRedirectDomain", expectedRedirectDomain);
            return Map.copyOf(values);
        }
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
            String status,
            String readinessReason
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
