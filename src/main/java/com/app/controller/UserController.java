package com.app.controller;

import com.app.dto.ApiResponse;
import com.app.dto.ChangeRoleRequest;
import com.app.dto.UserProfileResponse;
import com.app.entity.Role;
import com.app.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "User profile operations (requires authentication)")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    @Operation(summary = "Get current user's profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(Authentication authentication) {
        UserProfileResponse profile = userService.getUserProfile(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success("Profile retrieved successfully", profile));
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Admin-only endpoint")
    public ResponseEntity<ApiResponse<String>> adminEndpoint() {
        return ResponseEntity.ok(ApiResponse.success("Admin access granted", "Welcome, Admin!"));
    }

    @PutMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change a user's role (Admin only)")
    public ResponseEntity<ApiResponse<UserProfileResponse>> changeRole(
            @PathVariable Long userId,
            @Valid @RequestBody ChangeRoleRequest request) {
        Role role = Role.valueOf(request.getRole());
        UserProfileResponse profile = userService.changeUserRole(userId, role);
        return ResponseEntity.ok(ApiResponse.success("Role updated successfully", profile));
    }
}
