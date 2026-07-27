package com.opshub.generation.application;

import com.opshub.generation.domain.TemplateId;

public interface TemplateReadinessValidator {
    Readiness validate(TemplateId template, TestPlanService.TemplateParameters parameters);

    static TemplateReadinessValidator alwaysReady() {
        return (template, parameters) -> Readiness.readyResult();
    }

    default String catalogVersion() {
        return TemplateReadinessProperties.DEFAULT_CATALOG_VERSION;
    }

    record Readiness(boolean ready, String reason) {
        public static Readiness readyResult() {
            return new Readiness(true, null);
        }

        public static Readiness notReady(String reason) {
            return new Readiness(false, reason);
        }
    }
}
