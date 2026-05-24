package com.appchat.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private String status;
    private String event;
    private String mes;
    private T data;

    public ApiResponse(String event, T data) {
        this.status = "success";
        this.event = event;
        this.data = data;
    }
}
