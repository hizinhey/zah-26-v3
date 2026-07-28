package com.opshub.operation.application;

import com.opshub.operation.domain.OfficialAccount;
import com.opshub.operation.domain.Operation;
import com.opshub.operation.domain.OperationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OperationService {
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Operation create(String jiraId) {
        Operation operation = Operation.create(jiraId);
        entityManager.persist(operation);
        return operation;
    }

    @Transactional
    public Operation replaceOas(UUID operationId, int expectedRevision, List<SaveOaCommand> oas) {
        validateOfficialAccounts(oas);

        int updatedOperations = entityManager.createQuery("""
                        UPDATE Operation operation
                        SET operation.revision = operation.revision + 1,
                            operation.status = :status,
                            operation.validationRunId = null,
                            operation.planId = null,
                            operation.approvedPlanId = null,
                            operation.updatedAt = :updatedAt
                        WHERE operation.id = :operationId
                          AND operation.revision = :expectedRevision
                        """)
                .setParameter("status", OperationStatus.DRAFT)
                .setParameter("updatedAt", Instant.now())
                .setParameter("operationId", operationId)
                .setParameter("expectedRevision", expectedRevision)
                .executeUpdate();

        if (updatedOperations == 0) {
            throwConflictOrNotFound(operationId);
        }

        entityManager.createQuery("DELETE FROM OfficialAccount account WHERE account.operation.id = :operationId")
                .setParameter("operationId", operationId)
                .executeUpdate();
        invalidateDownstreamRecords(operationId);

        entityManager.clear();
        Operation operation = findRequired(operationId);
        for (int index = 0; index < oas.size(); index++) {
            SaveOaCommand command = oas.get(index);
            OfficialAccount account = new OfficialAccount(
                    operation,
                    index + 1,
                    command.platform(),
                    command.oaName(),
                    command.thumbnailUrl(),
                    command.content(),
                    command.buttonText(),
                    command.redirectUrl()
            );
            operation.addOfficialAccount(account);
            entityManager.persist(account);
        }
        entityManager.flush();
        return operation;
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public Operation get(UUID operationId) {
        return findRequired(operationId);
    }

    private void validateOfficialAccounts(List<SaveOaCommand> oas) {
        if (oas == null) {
            throw new IllegalArgumentException("oas must not be null");
        }
        String platform = null;
        for (SaveOaCommand oa : oas) {
            if (oa == null) {
                throw new IllegalArgumentException("oa must not be null");
            }
            if (!"ANDROID".equals(oa.platform()) && !"WEB".equals(oa.platform())) {
                throw new UnsupportedPlatformException(oa.platform());
            }
            if (platform == null) {
                platform = oa.platform();
            } else if (!platform.equals(oa.platform())) {
                throw new IllegalArgumentException("All official accounts in one Operation must share the same platform");
            }
            if (isBlank(oa.oaName()) || isBlank(oa.thumbnailUrl()) || isBlank(oa.content())
                    || isBlank(oa.buttonText()) || isBlank(oa.redirectUrl())) {
                throw new IllegalArgumentException("All official account fields must be provided");
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void invalidateDownstreamRecords(UUID operationId) {
        entityManager.createNativeQuery("""
                        UPDATE validation_runs
                        SET status = 'INVALID'
                        WHERE operation_id = :operationId
                        """)
                .setParameter("operationId", operationId)
                .executeUpdate();
        entityManager.createNativeQuery("""
                        UPDATE test_plans
                        SET status = 'INVALID', approval_status = 'INVALID'
                        WHERE operation_id = :operationId
                        """)
                .setParameter("operationId", operationId)
                .executeUpdate();
    }

    private void throwConflictOrNotFound(UUID operationId) {
        Integer currentRevision = entityManager.createQuery("""
                        SELECT operation.revision
                        FROM Operation operation
                        WHERE operation.id = :operationId
                        """, Integer.class)
                .setParameter("operationId", operationId)
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (currentRevision == null) {
            throw new OperationNotFoundException(operationId);
        }
        throw new RevisionConflictException(currentRevision);
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
}
