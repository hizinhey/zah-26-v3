package com.opshub.execution;

import com.opshub.execution.application.WebWorkerLauncher;
import com.opshub.execution.application.WebWorkerProperties;
import com.opshub.hub.application.HubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WebWorkerLauncherTest {
    @Test
    void springCanCreateTheLauncherWithItsProductionDependencies() {
        new ApplicationContextRunner()
                .withBean(WebWorkerProperties.class)
                .withBean(HubProperties.class)
                .withBean(WebWorkerLauncher.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WebWorkerLauncher.class);
                });
    }

    @Test
    void doesNothingWhenDisabled() {
        WebWorkerProperties properties = new WebWorkerProperties();
        properties.setEnabled(false);
        AtomicInteger startCount = new AtomicInteger();
        WebWorkerLauncher launcher = new WebWorkerLauncher(properties, (command, workingDirectory, env) -> {
            startCount.incrementAndGet();
            return new FakeProcess();
        });

        launcher.launchIfNeeded();

        assertThat(startCount.get()).isZero();
    }

    @Test
    void startsThePythonWorkerWithTheConfiguredCommandAndEnvironmentWhenEnabled() {
        WebWorkerProperties properties = enabledProperties();
        List<List<String>> commands = new ArrayList<>();
        List<Map<String, String>> envs = new ArrayList<>();
        WebWorkerLauncher launcher = new WebWorkerLauncher(properties, (command, workingDirectory, env) -> {
            commands.add(command);
            envs.add(env);
            return new FakeProcess();
        });

        launcher.launchIfNeeded();

        assertThat(commands).hasSize(1);
        assertThat(commands.get(0)).containsExactly("python3", "-m", "opshub_hub.main");
        assertThat(envs.get(0))
                .containsEntry("OPSHUB_HUB_ID", "web-worker")
                .containsEntry("OPSHUB_PLATFORM", "WEB")
                .containsEntry("OPSHUB_BACKEND_URL", "https://backend.example.test");
    }

    @Test
    void doesNotStartASecondWorkerWhileOneIsStillRunning() {
        WebWorkerProperties properties = enabledProperties();
        AtomicInteger startCount = new AtomicInteger();
        WebWorkerLauncher launcher = new WebWorkerLauncher(properties, (command, workingDirectory, env) -> {
            startCount.incrementAndGet();
            return new FakeProcess();
        });

        launcher.launchIfNeeded();
        launcher.launchIfNeeded();

        assertThat(startCount.get()).isEqualTo(1);
    }

    private static WebWorkerProperties enabledProperties() {
        WebWorkerProperties properties = new WebWorkerProperties();
        properties.setEnabled(true);
        properties.setPythonExecutable("python3");
        properties.setWorkingDirectory("/opt/opshub/local-hub");
        properties.setHubId("web-worker");
        properties.setBackendUrl("https://backend.example.test");
        properties.setTemplateRoot("/opt/opshub/local-hub/templates/web");
        properties.setDataRoot("/opt/opshub/data/web-worker");
        return properties;
    }

    private static class FakeProcess extends Process {
        @Override
        public java.io.OutputStream getOutputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getInputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getErrorStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("still running");
        }

        @Override
        public void destroy() {
        }
    }
}
