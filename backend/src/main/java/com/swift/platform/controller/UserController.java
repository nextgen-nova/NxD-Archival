package com.swift.platform.controller;

import com.swift.platform.dto.*;
import com.swift.platform.service.AuditService;
import com.swift.platform.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService  userService;
    private final AuditService auditService;

    // ── User Management — ADMIN ONLY ──────────────────────────────────────

    @GetMapping("/api/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<UserDTO>>> getUsers(
            @RequestParam(defaultValue = "")     String  search,
            @RequestParam(defaultValue = "ALL")  String  role,
            @RequestParam(required = false)      Boolean active,
            @RequestParam(defaultValue = "0")    int     page,
            @RequestParam(defaultValue = "20")   int     size,
            @RequestParam(defaultValue = "name") String  sort,
            @RequestParam(defaultValue = "desc") String  dir) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUsers(search, role, active, page, size, sort, dir)));
    }

    @GetMapping("/api/users/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUserStats() {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserStats()));
    }

    @GetMapping("/api/users/{employeeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserDTO>> getUserById(@PathVariable String employeeId) {
        return ResponseEntity.ok(ApiResponse.ok(userService.getUserByEmployeeId(employeeId)));
    }

    @PostMapping("/api/users")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserDTO>> createUser(@RequestBody UserDTO dto,
                                                           HttpServletRequest req) {
        UserDTO created = userService.createUser(dto);
        auditService.log((String) req.getAttribute("employeeId"),
                "USER_CREATE", "Created: " + dto.getEmployeeId(), req.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("User created successfully", created));
    }

    @PutMapping("/api/users/{employeeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserDTO>> updateUser(@PathVariable String employeeId,
                                                           @RequestBody UserDTO dto,
                                                           HttpServletRequest req) {
        UserDTO updated = userService.updateUser(employeeId, dto);
        auditService.log((String) req.getAttribute("employeeId"),
                "USER_UPDATE", "Updated: " + employeeId, req.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("User updated successfully", updated));
    }

    @PatchMapping("/api/users/{employeeId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserDTO>> toggleStatus(@PathVariable String employeeId,
                                                             @RequestBody StatusUpdateRequest body,
                                                             HttpServletRequest req) {
        UserDTO updated = userService.toggleUserStatus(employeeId, body.isActive());
        auditService.log((String) req.getAttribute("employeeId"),
                "USER_STATUS", employeeId + " active=" + body.isActive(), req.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok(body.isActive() ? "User activated" : "User deactivated", updated));
    }

    @DeleteMapping("/api/users/{employeeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable String employeeId,
                                                        HttpServletRequest req) {
        userService.deleteUser(employeeId);
        auditService.log((String) req.getAttribute("employeeId"),
                "USER_DELETE", "Deleted: " + employeeId, req.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("User deleted successfully", null));
    }

    // ── Profile — ANY AUTHENTICATED USER ─────────────────────────────────

    @GetMapping("/api/profile")
    public ResponseEntity<ApiResponse<UserDTO>> getProfile(HttpServletRequest req) {
        String employeeId = (String) req.getAttribute("employeeId");
        return ResponseEntity.ok(ApiResponse.ok(userService.getProfile(employeeId)));
    }

    @PutMapping("/api/profile")
    public ResponseEntity<ApiResponse<UserDTO>> updateProfile(@RequestBody UpdateProfileRequest body,
                                                              HttpServletRequest req) {
        String employeeId = (String) req.getAttribute("employeeId");
        UserDTO updated = userService.updateProfile(employeeId, body);
        auditService.log(employeeId, "PROFILE_UPDATE", "Updated profile", req.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("Profile updated successfully", updated));
    }

    @PutMapping("/api/profile/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@RequestBody ChangePasswordRequest body,
                                                            HttpServletRequest req) {
        String employeeId = (String) req.getAttribute("employeeId");
        userService.changePassword(employeeId, body);
        auditService.log(employeeId, "PASSWORD_CHANGE", "Password changed", req.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("Password changed successfully", null));
    }
}
