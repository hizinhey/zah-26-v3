package com.opshub.execution.application;

import com.opshub.hub.application.HubProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spawns the Web (WebdriverIO + Chrome) Local Hub as a subprocess on demand, the first
 * time it's needed, instead of requiring it to run as an always-on service like the
 * Android Hub. Disabled by default ({@code opshub.web-worker.enabled=false}) until an
 * operator has provisioned Chrome, Node, and a logged-in profile on the host running
 * the backend.
 */
@Component
public class WebWorkerLauncher {
    private final WebWorkerProperties properties;
    private final HubProperties hubProperties;
    private final ProcessStarter processStarter;
    private final ReentrantLock lock = new ReentrantLock();
    private Process runningProcess;

    public WebWorkerLauncher(WebWorkerProperties properties, HubProperties hubProperties) {
        this(properties, hubProperties, WebWorkerLauncher::startRealProcess);
    }

    public WebWorkerLauncher(WebWorkerProperties properties, ProcessStarter processStarter) {
        this(properties, new HubProperties(), processStarter);
    }

    WebWorkerLauncher(WebWorkerProperties properties, HubProperties hubProperties, ProcessStarter processStarter) {
        this.properties = properties;
        this.hubProperties = hubProperties;
        this.processStarter = processStarter;
    }

    public void launchIfNeeded() {
        if (!properties.isEnabled()) {
            return;
        }
        lock.lock();
        try {
            if (runningProcess != null && runningProcess.isAlive()) {
                return;
            }
            List<String> command = List.of(properties.getPythonExecutable(), "-m", "opshub_hub.main");
            Map<String, String> env = Map.of(
                    "OPSHUB_BACKEND_URL", properties.getBackendUrl(),
                    "OPSHUB_HUB_ID", properties.getHubId(),
                    "OPSHUB_HUB_TOKEN", hubProperties.getSharedToken(),
                    "OPSHUB_TEMPLATE_DIR", properties.getTemplateRoot(),
                    "OPSHUB_WORK_DIR", properties.getDataRoot(),
                    "OPSHUB_PLATFORM", "WEB"
            );
            try {
                runningProcess = processStarter.start(command, Path.of(properties.getWorkingDirectory()), env);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not start the Web worker process", exception);
            }
        } finally {
            lock.unlock();
        }
    }

    public interface ProcessStarter {
        Process start(List<String> command, Path workingDirectory, Map<String, String> env) throws IOException;
    }

    private static Process startRealProcess(List<String> command, Path workingDirectory, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(workingDirectory.resolve("web-worker.log").toFile()));
        builder.environment().putAll(env);
        return builder.start();
    }
}
