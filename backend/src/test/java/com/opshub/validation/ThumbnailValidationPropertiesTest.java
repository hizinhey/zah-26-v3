package com.opshub.validation;

import com.opshub.validation.application.ThumbnailValidationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ThumbnailValidationPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "opshub.validation.thumbnail.connect-timeout=750ms",
                    "opshub.validation.thumbnail.request-timeout=2s",
                    "opshub.validation.thumbnail.max-bytes=4096",
                    "opshub.validation.thumbnail.max-redirects=3"
            );

    @Test
    void bindsThumbnailValidationLimitsFromApplicationProperties() {
        contextRunner.run(context -> {
            ThumbnailValidationProperties properties = context.getBean(ThumbnailValidationProperties.class);

            assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(750));
            assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getMaxBytes()).isEqualTo(4_096);
            assertThat(properties.getMaxRedirects()).isEqualTo(3);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ThumbnailValidationProperties.class)
    static class PropertiesConfiguration {
    }
}
