package com.opshub.execution.application;

import java.util.UUID;

public class ExecutionNotFoundException extends RuntimeException {
    public ExecutionNotFoundException(UUID executionId) {
        super("Execution not found: " + executionId);
    }
}
