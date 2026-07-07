package com.appchat.backend.controller;

import com.appchat.backend.dto.ApiResponse;
import com.appchat.backend.dto.admin.*;
import com.appchat.backend.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

/**
 * Admin REST API — yêu cầu ROLE_ADMIN (được bảo vệ bởi SecurityConfig).
 *
 * Endpoints:
 *   GET    /admin/stats                    — Dashboard thống kê
 *   GET    /admin/users                    — Danh sách users (paged + search)
 *   GET    /admin/users/{id}               — Chi tiết user
 *   PUT    /admin/users/{id}               — Cập nhật user
 *   DELETE /admin/users/{id}               — Xóa user
 *   GET    /admin/rooms                    — Danh sách rooms (paged)
 *   GET    /admin/rooms/{name}             — Chi tiết room + members
 *   DELETE /admin/rooms/{name}             — Xóa room + messages
 *   GET    /admin/messages                 — Tìm kiếm messages
 *   DELETE /admin/messages/{id}            — Xóa tin nhắn
 *   PATCH  /admin/messages/{id}/recall     — Thu hồi tin nhắn
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // =========================================================
    // DASHBOARD
    // =========================================================

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<AdminStatsDto>> getStats() {
        return ok("ADMIN_STATS", adminService.getStats());
    }

    // =========================================================
    // USER MANAGEMENT
    // =========================================================

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<PagedResponse<AdminUserDto>>> getAllUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search
    ) {
        return ok("ADMIN_USERS", adminService.getAllUsers(page, size, search));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<AdminUserDto>> getUserById(@PathVariable Long id) {
        try {
            return ok("ADMIN_USER_DETAIL", adminService.getUserById(id));
        } catch (NoSuchElementException e) {
            return notFound("ADMIN_USER_NOT_FOUND", e.getMessage());
        }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<ApiResponse<AdminUserDto>> updateUser(
            @PathVariable Long id,
            @RequestBody AdminUserUpdateRequest request
    ) {
        try {
            return ok("ADMIN_USER_UPDATED", adminService.updateUser(id, request));
        } catch (NoSuchElementException e) {
            return notFound("ADMIN_USER_NOT_FOUND", e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest("ADMIN_USER_BAD_REQUEST", e.getMessage());
        }
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        try {
            adminService.deleteUser(id);
            return ok("ADMIN_USER_DELETED", null);
        } catch (NoSuchElementException e) {
            return notFound("ADMIN_USER_NOT_FOUND", e.getMessage());
        }
    }

    // =========================================================
    // ROOM MANAGEMENT
    // =========================================================

    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<PagedResponse<AdminRoomDto>>> getAllRooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ok("ADMIN_ROOMS", adminService.getAllRooms(page, size));
    }

    @GetMapping("/rooms/{name}")
    public ResponseEntity<ApiResponse<AdminRoomDetailDto>> getRoomDetail(@PathVariable String name) {
        try {
            return ok("ADMIN_ROOM_DETAIL", adminService.getRoomDetail(name));
        } catch (NoSuchElementException e) {
            return notFound("ADMIN_ROOM_NOT_FOUND", e.getMessage());
        }
    }

    @DeleteMapping("/rooms/{name}")
    public ResponseEntity<ApiResponse<Void>> deleteRoom(@PathVariable String name) {
        try {
            adminService.deleteRoom(name);
            return ok("ADMIN_ROOM_DELETED", null);
        } catch (NoSuchElementException e) {
            return notFound("ADMIN_ROOM_NOT_FOUND", e.getMessage());
        }
    }

    // =========================================================
    // MESSAGE MANAGEMENT
    // =========================================================

    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<PagedResponse<AdminMessageDto>>> searchMessages(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String receiver,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ok("ADMIN_MESSAGES", adminService.searchMessages(type, sender, receiver, page, size));
    }

    @DeleteMapping("/messages/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteMessage(@PathVariable String id) {
        try {
            adminService.deleteMessage(id);
            return ok("ADMIN_MESSAGE_DELETED", null);
        } catch (NoSuchElementException e) {
            return notFound("ADMIN_MESSAGE_NOT_FOUND", e.getMessage());
        }
    }

    @PatchMapping("/messages/{id}/recall")
    public ResponseEntity<ApiResponse<AdminMessageDto>> recallMessage(@PathVariable String id) {
        try {
            return ok("ADMIN_MESSAGE_RECALLED", adminService.recallMessage(id));
        } catch (NoSuchElementException e) {
            return notFound("ADMIN_MESSAGE_NOT_FOUND", e.getMessage());
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private <T> ResponseEntity<ApiResponse<T>> ok(String event, T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setStatus("success");
        response.setEvent(event);
        response.setMes("OK");
        response.setData(data);
        return ResponseEntity.ok(response);
    }

    private <T> ResponseEntity<ApiResponse<T>> notFound(String event, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setStatus("error");
        response.setEvent(event);
        response.setMes(message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    private <T> ResponseEntity<ApiResponse<T>> badRequest(String event, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setStatus("error");
        response.setEvent(event);
        response.setMes(message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
