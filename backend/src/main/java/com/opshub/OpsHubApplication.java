package com.opshub;

import com.opshub.validation.application.ThumbnailValidationProperties;
import com.opshub.generation.application.TemplateReadinessProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableConfigurationProperties({ThumbnailValidationProperties.class, TemplateReadinessProperties.class})
public class OpsHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsHubApplication.class, args);
    }
}
