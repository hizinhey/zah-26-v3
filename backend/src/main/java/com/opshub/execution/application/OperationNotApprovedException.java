package com.opshub.execution.application;

import java.util.UUID;

public class OperationNotApprovedException extends RuntimeException {
    public OperationNotApprovedException(UUID operationId) {
        super("Operation is not currently approved for execution: " + operationId);
    }
}
