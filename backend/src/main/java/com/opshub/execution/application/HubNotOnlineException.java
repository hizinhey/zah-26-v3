package com.opshub.execution.application;

import java.util.UUID;

public class HubNotOnlineException extends RuntimeException {
    public HubNotOnlineException(UUID hubId) {
        super("Hub is not online: " + hubId);
    }
}
