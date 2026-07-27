package com.opshub.evidence.application;

import java.util.UUID;

public class EvidenceNotFoundException extends RuntimeException {
    public EvidenceNotFoundException(UUID evidenceId) {
        super("Unknown evidence: " + evidenceId);
    }
}
