package com.opshub.evidence.api;

import com.opshub.evidence.application.EvidenceService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Browser-facing evidence content endpoint - unlike EvidenceController's upload endpoint,
 * this is a plain read with no Hub token check, matching every other browser-facing GET
 * (operations, plans, executions).
 */
@RestController
@RequestMapping("/api/v1/evidence/{evidenceId}/content")
public class EvidenceContentController {
    private final EvidenceService evidenceService;

    public EvidenceContentController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@PathVariable UUID evidenceId) {
        EvidenceService.EvidenceContent content = evidenceService.loadContent(evidenceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .body(content.bytes());
    }
}
