package com.opshub.generation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("opshub.generation.templates")
public class TemplateReadinessProperties {
    public static final String DEFAULT_CATALOG_VERSION = "android-v1";
    public static final String DEFAULT_WEB_CATALOG_VERSION = "web-v1";

    private String catalogVersion = DEFAULT_CATALOG_VERSION;
    private String templateRoot = "local-hub/templates/android";
    private String webCatalogVersion = DEFAULT_WEB_CATALOG_VERSION;
    private String webTemplateRoot = "local-hub/templates/web";
    private String nodeExecutable = "";
    private String tscEntry = "";
    private Duration compilerTimeout = Duration.ofSeconds(30);

    public String getCatalogVersion() {
        return catalogVersion;
    }

    public void setCatalogVersion(String catalogVersion) {
        this.catalogVersion = catalogVersion;
    }

    public String getTemplateRoot() {
        return templateRoot;
    }

    public void setTemplateRoot(String templateRoot) {
        this.templateRoot = templateRoot;
    }

    public String getWebCatalogVersion() {
        return webCatalogVersion;
    }

    public void setWebCatalogVersion(String webCatalogVersion) {
        this.webCatalogVersion = webCatalogVersion;
    }

    public String getWebTemplateRoot() {
        return webTemplateRoot;
    }

    public void setWebTemplateRoot(String webTemplateRoot) {
        this.webTemplateRoot = webTemplateRoot;
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
