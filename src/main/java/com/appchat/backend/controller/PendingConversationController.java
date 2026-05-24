package com.appchat.backend.controller;

import com.appchat.backend.dto.ApiResponse;
import com.appchat.backend.dto.PendingRequest;
import com.appchat.backend.entity.PendingConversation;
import com.appchat.backend.service.PendingConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/chat/pending-conversations")
@RequiredArgsConstructor
public class PendingConversationController {

    private final PendingConversationService service;

    @PostMapping
    public ApiResponse<PendingConversation> createRequest(@RequestBody PendingRequest req) {
        PendingConversation pc = service.createRequest(req.getFromUsername(), req.getToUsername());
        return new ApiResponse<>("PENDING_CREATE", pc);
    }

    @GetMapping("/incoming")
    public ApiResponse<List<PendingConversation>> getIncomingRequests(@RequestParam String username) {
        List<PendingConversation> list = service.getIncomingRequests(username);
        return new ApiResponse<>("PENDING_INCOMING", list);
    }

    @PostMapping("/accept")
    public ApiResponse<Void> acceptRequest(@RequestBody PendingRequest req) {
        service.acceptRequest(req.getFromUsername(), req.getToUsername());
        return new ApiResponse<>("PENDING_ACCEPT", null);
    }

    @PostMapping("/delete")
    public ApiResponse<Void> deleteRequest(@RequestBody PendingRequest req) {
        service.deleteRequest(req.getFromUsername(), req.getToUsername());
        return new ApiResponse<>("PENDING_DELETE", null);
    }
}
