package com.ragproject.ragserver.controller;

import com.ragproject.ragserver.common.ApiResponse;
import com.ragproject.ragserver.dto.request.LoginRequest;
import com.ragproject.ragserver.dto.request.RegisterRequest;
import com.ragproject.ragserver.dto.response.LoginResponse;
import com.ragproject.ragserver.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/register")
    public ApiResponse<Void> register(@RequestBody RegisterRequest request) {
        authService.register(request.getUsername(), request.getPassword(), request.getDisplayName());
        return ApiResponse.ok(null);
    }
}
