package com.opshub.generation.domain;

public interface TemplateDescriptor {
    String id();

    int version();

    String sha256();

    String platform();
}
