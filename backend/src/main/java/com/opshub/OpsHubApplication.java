package com.opshub;

import com.opshub.validation.application.ThumbnailValidationProperties;
import com.opshub.generation.application.TemplateReadinessProperties;
import com.opshub.hub.application.HubProperties;
import com.opshub.evidence.application.EvidenceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableConfigurationProperties({ThumbnailValidationProperties.class, TemplateReadinessProperties.class,
        HubProperties.class, EvidenceProperties.class})
public class OpsHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsHubApplication.class, args);
    }
}
