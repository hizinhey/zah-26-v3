package com.opshub.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.generation.application.FileSystemTemplateReadinessValidator;
import com.opshub.generation.application.TemplateReadinessProperties;
import com.opshub.generation.application.TemplateReadinessValidator;
import com.opshub.generation.application.TestPlanService;
import com.opshub.generation.domain.TemplateId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemTemplateReadinessValidatorTest {
    @TempDir
    Path tempDirectory;

    @Test
    void keepsTemplatesNotReadyWhenTheManifestCatalogVersionIsWrong() throws Exception {
        Files.writeString(tempDirectory.resolve("manifest.json"), """
                {"catalogVersion":"android-v2","templates":[]}
                """);
        TemplateReadinessProperties properties = new TemplateReadinessProperties();
        properties.setTemplateRoot(tempDirectory.toString());
        FileSystemTemplateReadinessValidator validator = new FileSystemTemplateReadinessValidator(properties, new ObjectMapper());

        TemplateReadinessValidator.Readiness readiness = validator.validate(
                TemplateId.OA_DELIVERY,
                new TestPlanService.TemplateParameters(
                        "Account", "https://example.test/thumb.png", "Header", "Body", "Open",
                        "https://example.test/path", "example.test"
                )
        );

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.reason()).contains("catalog version");
    }

    @Test
    void keepsWebTemplatesNotReadyWhenTheWebManifestCatalogVersionIsWrong() throws Exception {
        Files.writeString(tempDirectory.resolve("manifest.json"), """
                {"catalogVersion":"web-v2","templates":[]}
                """);
        TemplateReadinessProperties properties = new TemplateReadinessProperties();
        properties.setWebTemplateRoot(tempDirectory.toString());
        FileSystemTemplateReadinessValidator validator = new FileSystemTemplateReadinessValidator(properties, new ObjectMapper());

        TemplateReadinessValidator.Readiness readiness = validator.validate(
                com.opshub.generation.domain.WebTemplateId.OA_DELIVERY,
                new TestPlanService.TemplateParameters(
                        "Account", "https://example.test/thumb.png", "Header", "Body", "Open",
                        "https://example.test/path", "example.test"
                )
        );

        assertThat(readiness.ready()).isFalse();
        assertThat(readiness.reason()).contains("catalog version");
    }

    @Test
    void catalogVersionIsPlatformSpecific() {
        TemplateReadinessProperties properties = new TemplateReadinessProperties();
        FileSystemTemplateReadinessValidator validator = new FileSystemTemplateReadinessValidator(properties, new ObjectMapper());

        assertThat(validator.catalogVersion("ANDROID")).isEqualTo(TemplateReadinessProperties.DEFAULT_CATALOG_VERSION);
        assertThat(validator.catalogVersion("WEB")).isEqualTo(TemplateReadinessProperties.DEFAULT_WEB_CATALOG_VERSION);
    }
}
