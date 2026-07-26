package com.opshub.validation.application;

import com.opshub.operation.domain.OperationStatus;
import com.opshub.validation.domain.FieldFinding;

import java.util.List;
import java.util.UUID;

public record ValidationRunDto(
        UUID id,
        UUID operationId,
        int sourceRevision,
        OperationStatus status,
        List<FieldFinding> findings,
        boolean canGenerate,
        List<String> generateDisabledReasons
) {
}
