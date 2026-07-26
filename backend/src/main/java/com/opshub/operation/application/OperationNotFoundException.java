package com.opshub.operation.application;

import java.util.UUID;

public class OperationNotFoundException extends RuntimeException {
    public OperationNotFoundException(UUID operationId) {
        super("Operation not found: " + operationId);
    }
}
