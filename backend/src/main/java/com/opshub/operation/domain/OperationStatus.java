package com.opshub.operation.domain;

public enum OperationStatus {
    DRAFT,
    VALIDATING,
    VALIDATION_FAILED,
    VALIDATED,
    GENERATING,
    GENERATION_FAILED,
    READY_FOR_APPROVAL,
    APPROVED,
    QUEUED,
    RUNNING,
    PASSED,
    FAILED,
    ERROR
}
