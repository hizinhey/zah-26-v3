package com.opshub.evidence.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opshub.evidence")
public class EvidenceProperties {
    /** Filesystem root all evidence files are stored under. PostgreSQL only stores the relative path. */
    private String root = "evidence";
    private long maxBytes = 10 * 1024 * 1024;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }
}
