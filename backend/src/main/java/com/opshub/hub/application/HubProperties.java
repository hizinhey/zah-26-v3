package com.opshub.hub.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opshub.hub")
public class HubProperties {
    /** Shared secret every Local Hub authenticates with; validated with a constant-time comparison. */
    private String sharedToken = "dev-hub-token";
    private long pollWaitCapSeconds = 25;

    public String getSharedToken() {
        return sharedToken;
    }

    public void setSharedToken(String sharedToken) {
        this.sharedToken = sharedToken;
    }

    public long getPollWaitCapSeconds() {
        return pollWaitCapSeconds;
    }

    public void setPollWaitCapSeconds(long pollWaitCapSeconds) {
        this.pollWaitCapSeconds = pollWaitCapSeconds;
    }
}
