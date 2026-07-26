package com.opshub.validation.application;

import com.opshub.operation.application.OperationNotFoundException;
import com.opshub.operation.application.RevisionConflictException;
import com.opshub.operation.domain.OfficialAccount;
import com.opshub.operation.domain.Operation;
import com.opshub.operation.domain.OperationStatus;
import com.opshub.validation.domain.FieldFinding;
import com.opshub.validation.domain.FieldStatus;
import com.opshub.validation.llm.TextValidationPort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ValidationService {
    private static final String GENERATE_DISABLED_REASON =
            "Resolve every failed, warning, or unavailable validation finding before generating tests.";

    @PersistenceContext
    private EntityManager entityManager;

    private final JdbcTemplate jdbcTemplate;
    private final ContentParser contentParser;
    private final UrlDirectionValidator urlDirectionValidator;
    private final ThumbnailValidator thumbnailValidator;
    private final TextValidationPort textValidationPort;

    public ValidationService(
            JdbcTemplate jdbcTemplate,
            ContentParser contentParser,
            UrlDirectionValidator urlDirectionValidator,
            ThumbnailValidator thumbnailValidator,
            TextValidationPort textValidationPort
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.contentParser = contentParser;
        this.urlDirectionValidator = urlDirectionValidator;
        this.thumbnailValidator = thumbnailValidator;
        this.textValidationPort = textValidationPort;
    }

    @Transactional
    public ValidationRunDto validate(UUID operationId, int revision) {
        Operation operation = findRequired(operationId);
        if (operation.getRevision() != revision) {
            throw new RevisionConflictException(operation.getRevision());
        }

        List<FieldFinding> findings = collectFindings(operation);
        boolean canGenerate = findings.stream().allMatch(finding -> finding.status() == FieldStatus.PASSED);
        OperationStatus status = canGenerate ? OperationStatus.VALIDATED : OperationStatus.VALIDATION_FAILED;
        UUID validationRunId = UUID.randomUUID();

        jdbcTemplate.update("""
                        UPDATE validation_runs
                        SET status = 'INVALID'
                        WHERE operation_id = ? AND source_revision = ? AND status <> 'INVALID'
                        """, operationId, revision);
        jdbcTemplate.update("""
                        INSERT INTO validation_runs (id, operation_id, source_revision, policy_version, model, status)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, validationRunId, operationId, revision,
                "deterministic-v1+" + textValidationPort.policyVersion(), textValidationPort.model(), status.name());
        for (FieldFinding finding : findings) {
            jdbcTemplate.update("""
                            INSERT INTO field_findings (id, validation_run_id, field_name, validator_type, status, issue, location, suggestion, severity, confidence)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), validationRunId, finding.fieldName(), finding.validatorType(), finding.status().name(),
                    finding.issue(), finding.location(), finding.suggestion(), finding.severity(), finding.confidence());
        }

        int updated = jdbcTemplate.update("""
                        UPDATE operations
                        SET status = ?, validation_run_id = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND revision = ?
                        """, status.name(), validationRunId, operationId, revision);
        if (updated == 0) {
            throw new RevisionConflictException(currentRevision(operationId));
        }

        return new ValidationRunDto(
                validationRunId,
                operationId,
                revision,
                status,
                List.copyOf(findings),
                canGenerate,
                canGenerate ? List.of() : List.of(GENERATE_DISABLED_REASON)
        );
    }

    List<FieldFinding> collectFindings(Operation operation) {
        List<FieldFinding> findings = new ArrayList<>();
        List<TextValidationPort.TextField> textFields = new ArrayList<>();
        if (operation.getOfficialAccounts().isEmpty()) {
            findings.add(FieldFinding.failed("oas", "required", "At least one official account is required"));
        }
        for (OfficialAccount account : operation.getOfficialAccounts()) {
            String fieldPrefix = "oa[" + account.getOaOrder() + "].";
            findings.add(required(fieldPrefix + "oaName", account.getOaName()));
            findings.add(thumbnailValidator.validate(account.getThumbnailUrl()).forField(fieldPrefix + "thumbnailUrl"));
            ContentParser.ParsedContent parsed = contentParser.parse(account.getContent());
            findings.add(required(fieldPrefix + "content.header", parsed.header()));
            findings.add(required(fieldPrefix + "content.body", parsed.body()));
            findings.add(required(fieldPrefix + "buttonText", account.getButtonText()));
            textFields.add(new TextValidationPort.TextField(fieldPrefix + "content.header", parsed.header()));
            textFields.add(new TextValidationPort.TextField(fieldPrefix + "content.body", parsed.body()));
            textFields.add(new TextValidationPort.TextField(fieldPrefix + "buttonText", account.getButtonText()));
            findings.add(urlDirectionValidator.validate(account.getRedirectUrl()).forField(fieldPrefix + "redirectUrl"));
        }
        if (!textFields.isEmpty()) {
            findings.addAll(textValidationPort.validate(new TextValidationPort.TextValidationRequest(textFields)));
        }
        return findings;
    }

    private FieldFinding required(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            return FieldFinding.failed(fieldName, "required", "A value is required");
        }
        return FieldFinding.passed(fieldName, "required");
    }

    private Operation findRequired(UUID operationId) {
        return entityManager.createQuery("""
                        SELECT DISTINCT operation
                        FROM Operation operation
                        LEFT JOIN FETCH operation.officialAccounts
                        WHERE operation.id = :operationId
                        """, Operation.class)
                .setParameter("operationId", operationId)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new OperationNotFoundException(operationId));
    }

    private int currentRevision(UUID operationId) {
        Integer currentRevision = jdbcTemplate.query("SELECT revision FROM operations WHERE id = ?", resultSet ->
                resultSet.next() ? resultSet.getInt(1) : null, operationId);
        if (currentRevision == null) {
            throw new OperationNotFoundException(operationId);
        }
        return currentRevision;
    }
}
