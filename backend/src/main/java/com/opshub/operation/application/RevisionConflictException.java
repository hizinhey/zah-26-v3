package com.opshub.operation.application;

public class RevisionConflictException extends RuntimeException {
    private final int currentRevision;

    public RevisionConflictException(int currentRevision) {
        super("The operation was updated by another request");
        this.currentRevision = currentRevision;
    }

    public int getCurrentRevision() {
        return currentRevision;
    }
}
