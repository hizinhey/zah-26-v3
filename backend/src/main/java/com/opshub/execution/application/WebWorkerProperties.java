package com.opshub.execution.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("opshub.web-worker")
public class WebWorkerProperties {
    private boolean enabled = false;
    private String pythonExecutable = "python3";
    private String workingDirectory = "";
    private String hubId = "web-worker";
    private String backendUrl = "";
    private String templateRoot = "";
    private String dataRoot = "";
    private String wdioProjectRoot = "";
    private String nodeExecutable = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPythonExecutable() {
        return pythonExecutable;
    }

    public void setPythonExecutable(String pythonExecutable) {
        this.pythonExecutable = pythonExecutable;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public String getHubId() {
        return hubId;
    }

    public void setHubId(String hubId) {
        this.hubId = hubId;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    public String getTemplateRoot() {
        return templateRoot;
    }

    public void setTemplateRoot(String templateRoot) {
        this.templateRoot = templateRoot;
    }

    public String getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(String dataRoot) {
        this.dataRoot = dataRoot;
    }

    public String getWdioProjectRoot() {
        return wdioProjectRoot;
    }

    public void setWdioProjectRoot(String wdioProjectRoot) {
        this.wdioProjectRoot = wdioProjectRoot;
    }

    public String getNodeExecutable() {
        return nodeExecutable;
    }

    public void setNodeExecutable(String nodeExecutable) {
        this.nodeExecutable = nodeExecutable;
    }
}
