package com.appchat.backend.controller;

import com.appchat.backend.dto.ApiResponse;
import com.appchat.backend.dto.PendingRequest;
import com.appchat.backend.entity.PendingConversation;
import com.appchat.backend.service.PendingConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/chat/pending-conversations")
@RequiredArgsConstructor
public class PendingConversationController {

    private final PendingConversationService service;

    @PostMapping
    public ApiResponse<PendingConversation> createRequest(
            @RequestBody PendingRequest req,
            Authentication authentication
    ) {
        validateUser(authentication, req.getFromUsername());

        PendingConversation pc = service.createRequest(
                req.getFromUsername(),
                req.getToUsername()
        );

        return new ApiResponse<>("PENDING_CREATE", pc);
    }

    @GetMapping("/incoming")
    public ApiResponse<List<PendingConversation>> incoming(
            @RequestParam String username,
            Authentication authentication
    ) {
        validateUser(authentication, username);

        return new ApiResponse<>(
                "PENDING_INCOMING",
                service.getIncomingRequests(username)
        );
    }

    @GetMapping("/outgoing")
    public ApiResponse<List<PendingConversation>> outgoing(
            @RequestParam String username,
            Authentication authentication
    ) {
        validateUser(authentication, username);

        return new ApiResponse<>(
                "PENDING_OUTGOING",
                service.getOutgoingRequests(username)
        );
    }

    @GetMapping("/accepted")
    public ApiResponse<List<PendingConversation>> accepted(
            @RequestParam String username,
            Authentication authentication
    ) {
        validateUser(authentication, username);

        return new ApiResponse<>(
                "PENDING_ACCEPTED",
                service.getAcceptedConversations(username)
        );
    }

    @PostMapping("/accept")
    public ApiResponse<Void> accept(
            @RequestBody PendingRequest req,
            Authentication authentication
    ) {
        validateUser(authentication, req.getToUsername());

        service.acceptRequest(
                req.getFromUsername(),
                req.getToUsername()
        );

        return new ApiResponse<>("PENDING_ACCEPT", null);
    }

    @PostMapping("/delete")
    public ApiResponse<Void> delete(
            @RequestBody PendingRequest req,
            Authentication authentication
    ) {
        if (
                authentication == null ||
                        !authentication.isAuthenticated() ||
                        (
                                !authentication.getName().equals(req.getFromUsername()) &&
                                        !authentication.getName().equals(req.getToUsername())
                        )
        ) {
            throw new AccessDeniedException("Unauthorized");
        }

        service.deleteRequest(
                req.getFromUsername(),
                req.getToUsername()
        );

        return new ApiResponse<>("PENDING_DELETE", null);
    }

    private void validateUser(Authentication authentication, String username) {
        if (
                authentication == null ||
                        !authentication.isAuthenticated() ||
                        !authentication.getName().equals(username)
        ) {
            throw new AccessDeniedException("Unauthorized");
        }
    }

    @PostMapping("/remove-contact")
    public ApiResponse<Void> removeContact(
            @RequestBody PendingRequest req,
            Authentication authentication
    ) {
        if (
                authentication == null ||
                        !authentication.isAuthenticated() ||
                        (
                                !authentication.getName().equals(req.getFromUsername()) &&
                                        !authentication.getName().equals(req.getToUsername())
                        )
        ) {
            throw new AccessDeniedException("Unauthorized");
        }

        String currentUser = authentication.getName();
        String otherUser = currentUser.equals(req.getFromUsername())
                ? req.getToUsername()
                : req.getFromUsername();

        service.removeContact(currentUser, otherUser);

        return new ApiResponse<>("REMOVE_CONTACT", null);
    }
}