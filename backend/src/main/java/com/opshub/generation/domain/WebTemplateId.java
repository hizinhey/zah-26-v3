package com.opshub.generation.domain;

public enum WebTemplateId implements TemplateDescriptor {
    OA_DELIVERY("web-oa-delivery-v1", 1, "8227c41e3d72efaf46aa84ad4015f4e1b375c1340d08a3b942bda020205d4d37"),
    THUMBNAIL("web-thumbnail-v1", 1, "ecde058d750c87ba8e2d1ca51714fc9ba4e84df595615bd9942725e3b63a8075"),
    CONTENT("web-content-v1", 1, "b3791ba0c3d98c2f61d7b58a2a6d7d507c5db7386d82918cf1f59416cb97fd8e"),
    BUTTON_TEXT("web-button-text-v1", 1, "3cbb3f81338e5c3f12eb7cc3e26dec11fa8b4ecf3f2d5f7bac2d32266211bea3"),
    REDIRECT("web-redirect-v1", 1, "d9fce69c3dd55cb4cb953d603677685afd89270598e1dda9bcdd32bf8a8c8bb1");

    private final String id;
    private final int version;
    private final String sha256;

    WebTemplateId(String id, int version, String sha256) {
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
        return "WEB";
    }
}
