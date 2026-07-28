package com.opshub.generation.domain;

public enum TemplateId implements TemplateDescriptor {
    OA_DELIVERY("android-oa-delivery-v1", 1, "578a8074cc9c58c27565e70dc798fa815d940632cdee8140b58d2b18a8919132"),
    THUMBNAIL("android-thumbnail-v1", 1, "84d686d049ccd8def4d3bcb986e51ca6f66ca89ba0cc48fefe7bbf8f515ab079"),
    CONTENT("android-content-v1", 1, "e2532e5f248e804748b05a3c2995ea70e80465262b57d793936ec1861a6f53a5"),
    BUTTON_TEXT("android-button-text-v1", 1, "3a50d4b798c13f5c383d97281911471aa486155ed1e8cb7aa98b396a215c60b7"),
    REDIRECT("android-redirect-v1", 1, "ff72c8998ca04de21d7a2392ed26842adf64653946bdc194b99f16c6e3d0852d");

    private final String id;
    private final int version;
    private final String sha256;

    TemplateId(String id, int version, String sha256) {
        this.id = id;
        this.version = version;
        this.sha256 = sha256;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public String sha256() {
        return sha256;
    }

    @Override
    public String platform() {
        return "ANDROID";
    }
}
