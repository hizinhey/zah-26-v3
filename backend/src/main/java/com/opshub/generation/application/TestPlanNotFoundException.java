package com.opshub.generation.application;

import java.util.UUID;

public class TestPlanNotFoundException extends RuntimeException {
    public TestPlanNotFoundException(UUID planId) {
        super("Test plan not found: " + planId);
    }
}
