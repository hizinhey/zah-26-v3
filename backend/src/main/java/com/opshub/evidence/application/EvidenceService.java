package com.opshub.evidence.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evidence (screenshots, logs) is stored on the filesystem below the configured evidence root
 * using generated UUID names - client-supplied file names/paths are never trusted for storage
 * location, only used to determine the file extension. PostgreSQL stores metadata and the
 * relative path only.
 */
@Service
public class EvidenceService {
    private static final Map<String, Set<String>> ALLOWED_EXTENSIONS_BY_TYPE = Map.of(
            "SCREENSHOT", Set.of("png", "jpg", "jpeg"),
            "LOG", Set.of("log", "txt")
    );

    private final EvidenceProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Path evidenceRoot;

    public EvidenceService(EvidenceProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.evidenceRoot = Path.of(properties.getRoot()).toAbsolutePath().normalize();
    }

    public UUID store(UUID testResultId, String evidenceType, String originalFilename,
                       long declaredSize, String declaredSha256Hex, InputStream content) {
        requireTestResultExists(testResultId);
        String extension = extension(originalFilename, evidenceType);
        if (declaredSize < 0 || declaredSize > properties.getMaxBytes()) {
            throw new EvidenceValidationException("Declared size exceeds the allowed maximum");
        }

        UUID evidenceId = UUID.randomUUID();
        String generatedName = UUID.randomUUID() + "." + extension;
        Path directory = evidenceRoot.resolve(testResultId.toString()).normalize();
        if (!directory.startsWith(evidenceRoot)) {
            throw new EvidenceValidationException("Resolved evidence path escapes the evidence root");
        }
        Path target = directory.resolve(generatedName).normalize();
        if (!target.startsWith(directory)) {
            throw new EvidenceValidationException("Resolved evidence path escapes the evidence root");
        }

        try {
            Files.createDirectories(directory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long actualSize = 0;
            try (var out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    actualSize += read;
                    if (actualSize > properties.getMaxBytes()) {
                        throw new EvidenceValidationException("File exceeds the allowed maximum size");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (actualSize != declaredSize) {
                Files.deleteIfExists(target);
                throw new EvidenceValidationException("Declared size does not match the stored file");
            }
            if (declaredSha256Hex != null && !declaredSha256Hex.equalsIgnoreCase(actualSha256)) {
                Files.deleteIfExists(target);
                throw new EvidenceValidationException("Declared SHA-256 does not match the stored file");
            }

            String relativePath = evidenceRoot.relativize(target).toString();
            jdbcTemplate.update("""
                            INSERT INTO evidence (id, test_result_id, evidence_type, relative_path, size_bytes, checksum)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """, evidenceId, testResultId, evidenceType, relativePath, actualSize, actualSha256);
            return evidenceId;
        } catch (EvidenceValidationException exception) {
            throw exception;
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new EvidenceValidationException("Could not persist evidence: " + exception.getMessage());
        }
    }

    private void requireTestResultExists(UUID testResultId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM test_results WHERE id = ?", Integer.class, testResultId);
        if (count == null || count == 0) {
            throw new EvidenceValidationException("Unknown test result: " + testResultId);
        }
    }

    private String extension(String originalFilename, String evidenceType) {
        Set<String> allowed = ALLOWED_EXTENSIONS_BY_TYPE.get(evidenceType);
        if (allowed == null) {
            throw new EvidenceValidationException("Unsupported evidence type: " + evidenceType);
        }
        if (originalFilename == null || originalFilename.contains("/") || originalFilename.contains("\\") || originalFilename.contains("..")) {
            throw new EvidenceValidationException("Unsupported or unsafe file name");
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new EvidenceValidationException("File name must have an extension");
        }
        String extension = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!allowed.contains(extension)) {
            throw new EvidenceValidationException("Unsupported file type: " + extension);
        }
        return extension;
    }
}
