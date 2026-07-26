package com.opshub.operation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "official_accounts")
public class OfficialAccount {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false)
    private Operation operation;

    @Column(name = "oa_order", nullable = false)
    private int oaOrder;

    @Column(nullable = false)
    private String platform;

    @Column(name = "oa_name", nullable = false)
    private String oaName;

    @Column(name = "thumbnail_url", nullable = false)
    private String thumbnailUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "button_text", nullable = false)
    private String buttonText;

    @Column(name = "redirect_url", nullable = false)
    private String redirectUrl;

    protected OfficialAccount() {
    }

    public OfficialAccount(
            Operation operation,
            int oaOrder,
            String platform,
            String oaName,
            String thumbnailUrl,
            String content,
            String buttonText,
            String redirectUrl
    ) {
        this.id = UUID.randomUUID();
        this.operation = operation;
        this.oaOrder = oaOrder;
        this.platform = platform;
        this.oaName = oaName;
        this.thumbnailUrl = thumbnailUrl;
        this.content = content;
        this.buttonText = buttonText;
        this.redirectUrl = redirectUrl;
    }

    public UUID getId() {
        return id;
    }

    public int getOaOrder() {
        return oaOrder;
    }

    public String getPlatform() {
        return platform;
    }

    public String getOaName() {
        return oaName;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getContent() {
        return content;
    }

    public String getButtonText() {
        return buttonText;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }
}
