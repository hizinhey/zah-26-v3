package com.opshub.generation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("opshub.generation.templates")
public class TemplateReadinessProperties {
    private String templateRoot = "local-hub/templates/android";
    private String nodeExecutable = "";
    private String tscEntry = "";
    private Duration compilerTimeout = Duration.ofSeconds(30);

    public String getTemplateRoot() {
        return templateRoot;
    }

    public void setTemplateRoot(String templateRoot) {
        this.templateRoot = templateRoot;
    }

    public String getNodeExecutable() {
        return nodeExecutable;
    }

    public void setNodeExecutable(String nodeExecutable) {
        this.nodeExecutable = nodeExecutable;
    }

    public String getTscEntry() {
        return tscEntry;
    }

    public void setTscEntry(String tscEntry) {
        this.tscEntry = tscEntry;
    }

    public Duration getCompilerTimeout() {
        return compilerTimeout;
    }

    public void setCompilerTimeout(Duration compilerTimeout) {
        this.compilerTimeout = compilerTimeout;
    }
}
