package com.opshub.operation.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "operations")
public class Operation {
    @Id
    private UUID id;

    @Column(name = "jira_id", nullable = false)
    private String jiraId;

    @Column(nullable = false)
    private int revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OperationStatus status;

    @Column(name = "validation_run_id")
    private UUID validationRunId;

    @Column(name = "plan_id")
    private UUID planId;

    @Column(name = "approved_plan_id")
    private UUID approvedPlanId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "operation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("oaOrder ASC")
    private List<OfficialAccount> officialAccounts = new ArrayList<>();

    protected Operation() {
    }

    private Operation(String jiraId) {
        this.id = UUID.randomUUID();
        this.jiraId = jiraId;
        this.revision = 1;
        this.status = OperationStatus.DRAFT;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public static Operation create(String jiraId) {
        if (jiraId == null || jiraId.isBlank()) {
            throw new IllegalArgumentException("jiraId must not be blank");
        }
        return new Operation(jiraId.trim());
    }

    public void addOfficialAccount(OfficialAccount officialAccount) {
        officialAccounts.add(officialAccount);
    }

    public UUID getId() {
        return id;
    }

    public String getJiraId() {
        return jiraId;
    }

    public int getRevision() {
        return revision;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public UUID getValidationRunId() {
        return validationRunId;
    }

    public UUID getPlanId() {
        return planId;
    }

    public UUID getApprovedPlanId() {
        return approvedPlanId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OfficialAccount> getOfficialAccounts() {
        return List.copyOf(officialAccounts);
    }
}
