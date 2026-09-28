package com.poolguard.controller;

import com.poolguard.dto.AuthDtos;
import com.poolguard.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    private final com.poolguard.service.TokenService tokenService;

    public AuthController(AuthService authService, com.poolguard.service.TokenService tokenService) {
        this.tokenService = tokenService;
        this.authService = authService;
    }

    @PostMapping("/logout")
    public void logout(@org.springframework.web.bind.annotation.RequestHeader("Authorization") String header,
                       jakarta.servlet.http.HttpServletRequest request) {
        tokenService.revoke(header.startsWith("Bearer ") ? header.substring(7) : "");
    }

    @PostMapping("/login")
    public AuthDtos.LoginResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
    }
}
