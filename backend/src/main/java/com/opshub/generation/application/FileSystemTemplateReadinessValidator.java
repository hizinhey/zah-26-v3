package com.opshub.generation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opshub.generation.domain.TemplateDescriptor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class FileSystemTemplateReadinessValidator implements TemplateReadinessValidator {
    private static final Pattern JSON_PARAMETER = Pattern.compile("\\{\\{\\{json ([A-Za-z][A-Za-z0-9]*)}}}");

    private final TemplateReadinessProperties properties;
    private final ObjectMapper objectMapper;

    public FileSystemTemplateReadinessValidator(TemplateReadinessProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Readiness validate(TemplateDescriptor template, TestPlanService.TemplateParameters parameters) {
        try {
            Map<String, String> values = parameters.asMap();
            validateParameters(values);
            Path root = Path.of(rootFor(template.platform())).toAbsolutePath().normalize();
            JsonNode entry = manifestEntry(root, template, catalogVersion(template.platform()));
            Path templateFile = templateFile(root, entry);
            String templateSource = Files.readString(templateFile, StandardCharsets.UTF_8);
            if (!sha256(templateSource.getBytes(StandardCharsets.UTF_8)).equals(template.sha256())) {
                return Readiness.notReady("Template checksum does not match the approved catalog");
            }
            return compileRenderedTemplate(root, templateFile, render(templateSource, values));
        } catch (Exception exception) {
            return Readiness.notReady(exception.getMessage() == null ? "Template readiness validation failed" : exception.getMessage());
        }
    }

    @Override
    public String catalogVersion(String platform) {
        return "WEB".equals(platform) ? properties.getWebCatalogVersion() : properties.getCatalogVersion();
    }

    private String rootFor(String platform) {
        return "WEB".equals(platform) ? properties.getWebTemplateRoot() : properties.getTemplateRoot();
    }

    private JsonNode manifestEntry(Path root, TemplateDescriptor template, String expectedCatalogVersion) throws IOException {
        Path manifest = root.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IllegalStateException("Template manifest is missing");
        }
        JsonNode catalog = objectMapper.readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!expectedCatalogVersion.equals(catalog.path("catalogVersion").asText())) {
            throw new IllegalStateException("Template manifest catalog version does not match the configured catalog version");
        }
        JsonNode entries = catalog.path("templates");
        for (JsonNode entry : entries) {
            if (template.id().equals(entry.path("id").asText())) {
                if (entry.path("version").asInt(-1) != template.version()
                        || !template.sha256().equals(entry.path("sha256").asText())
                        || !"template-parameters-v1".equals(entry.path("parameterSchema").asText())) {
                    throw new IllegalStateException("Template manifest entry does not match the approved template");
                }
                return entry;
            }
        }
        throw new IllegalStateException("Template is missing from the manifest");
    }

    private Path templateFile(Path root, JsonNode entry) {
        String relativePath = entry.path("path").asText();
        if (relativePath.isBlank()) {
            throw new IllegalStateException("Template manifest entry has no path");
        }
        Path template = root.resolve(relativePath).normalize();
        if (!template.startsWith(root) || !Files.isRegularFile(template)) {
            throw new IllegalStateException("Template file is missing or outside the catalog");
        }
        return template;
    }

    private void validateParameters(Map<String, String> values) {
        if (values.size() != 7 || values.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalStateException("Template parameters are incomplete");
        }
        URI thumbnail = URI.create(values.get("thumbnailUrl"));
        URI redirect = URI.create(values.get("expectedRedirectUrl"));
        if (thumbnail.getScheme() == null || thumbnail.getHost() == null || redirect.getScheme() == null
                || redirect.getHost() == null || !redirect.getHost().equals(values.get("expectedRedirectDomain"))) {
            throw new IllegalStateException("Template parameters contain invalid URLs or redirect domain");
        }
    }

    private String render(String source, Map<String, String> values) throws IOException {
        Matcher matcher = JSON_PARAMETER.matcher(source);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String parameter = matcher.group(1);
            String value = values.get(parameter);
            if (value == null) {
                throw new IllegalStateException("Template references an unknown parameter");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(objectMapper.writeValueAsString(value)));
        }
        matcher.appendTail(rendered);
        if (rendered.indexOf("{{") >= 0) {
            throw new IllegalStateException("Template contains unsupported interpolation syntax");
        }
        return rendered.toString();
    }

    private Readiness compileRenderedTemplate(Path root, Path templateFile, String rendered) throws IOException, InterruptedException {
        if (properties.getNodeExecutable().isBlank() || properties.getTscEntry().isBlank()) {
            return Readiness.notReady("Node executable and TypeScript entry must be configured");
        }
        Path tempDirectory = Files.createTempDirectory("opshub-template-");
        try {
            Path tests = Files.createDirectories(tempDirectory.resolve("tests"));
            linkCompilerDependencies(tempDirectory);
            copyDirectory(root.resolve("pages"), tempDirectory.resolve("pages"));
            String filename = templateFile.getFileName().toString().replaceFirst("\\.hbs$", "");
            Files.writeString(tests.resolve(filename), rendered, StandardCharsets.UTF_8);
            Files.writeString(tempDirectory.resolve("tsconfig.json"), """
                    {"compilerOptions":{"moduleResolution":"node16","module":"node16","target":"es2022","lib":["es2022","dom"],"types":["node","@wdio/globals/types","expect-webdriverio","@wdio/mocha-framework"],"skipLibCheck":true,"noEmit":true,"strict":true},"include":["tests","pages"]}
                    """, StandardCharsets.UTF_8);
            Path output = tempDirectory.resolve("tsc-output.txt");
            Process process = new ProcessBuilder(
                    properties.getNodeExecutable(), properties.getTscEntry(), "--noEmit", "--project", tempDirectory.resolve("tsconfig.json").toString()
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Duration timeout = properties.getCompilerTimeout();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return Readiness.notReady("TypeScript validation timed out");
            }
            if (process.exitValue() != 0) {
                return Readiness.notReady("TypeScript validation failed: " + Files.readString(output, StandardCharsets.UTF_8).strip());
            }
            return Readiness.readyResult();
        } finally {
            deleteDirectory(tempDirectory);
        }
    }

    private void linkCompilerDependencies(Path tempDirectory) throws IOException {
        Path tscEntry = Path.of(properties.getTscEntry()).toAbsolutePath().normalize();
        Path typescriptDirectory = tscEntry.getParent() == null ? null : tscEntry.getParent().getParent();
        Path nodeModules = typescriptDirectory == null ? null : typescriptDirectory.getParent();
        if (nodeModules == null || !Files.isDirectory(nodeModules)) {
            throw new IllegalStateException("TypeScript entry must be located below a node_modules directory");
        }
        Files.createSymbolicLink(tempDirectory.resolve("node_modules"), nodeModules);
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("Template page objects are missing");
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target);
                }
            }
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String sha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
