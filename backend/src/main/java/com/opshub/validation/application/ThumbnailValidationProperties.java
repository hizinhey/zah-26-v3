package com.opshub.validation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("opshub.validation.thumbnail")
public class ThumbnailValidationProperties {
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(3);
    private int maxBytes = 5 * 1024 * 1024;
    private int maxRedirects = 5;

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    void validate() {
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                || requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                || maxBytes < 1 || maxRedirects < 0) {
            throw new IllegalArgumentException("Thumbnail validation limits must be positive, with a non-negative redirect limit");
        }
    }
}
