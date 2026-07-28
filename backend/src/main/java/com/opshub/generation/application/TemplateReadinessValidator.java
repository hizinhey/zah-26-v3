package com.opshub.generation.application;

import com.opshub.generation.domain.TemplateDescriptor;

public interface TemplateReadinessValidator {
    Readiness validate(TemplateDescriptor template, TestPlanService.TemplateParameters parameters);

    static TemplateReadinessValidator alwaysReady() {
        return (template, parameters) -> Readiness.readyResult();
    }

    default String catalogVersion(String platform) {
        return "WEB".equals(platform)
                ? TemplateReadinessProperties.DEFAULT_WEB_CATALOG_VERSION
                : TemplateReadinessProperties.DEFAULT_CATALOG_VERSION;
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
