package com.appchat.backend.dto;

import lombok.Data;

@Data
public class PendingRequest {
    private String fromUsername;
    private String toUsername;
}
