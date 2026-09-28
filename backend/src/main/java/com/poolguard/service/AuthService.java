package com.poolguard.service;

import com.poolguard.dto.AuthDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final String adminUsername;
    private final String adminPassword;
    private final TokenService tokenService;

    public AuthService(
        @Value("${poolguard.auth.username:admin}") String adminUsername,
        @Value("${poolguard.auth.password:pool-admin}") String adminPassword,
        TokenService tokenService
    ) {
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.tokenService = tokenService;
    }

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        if (request == null || !adminUsername.equals(request.username()) || !adminPassword.equals(request.password())) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        String token = tokenService.issue(adminUsername);
        return new AuthDtos.LoginResponse(token, adminUsername, "SUPER_ADMIN", tokenService.expiresInSeconds());
    }
}
