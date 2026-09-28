package com.poolguard.controller;

import com.poolguard.dto.AccountDtos;
import com.poolguard.dto.RunDtos;
import com.poolguard.service.AccountService;
import com.poolguard.service.DetectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService accountService;
    private final DetectionService detectionService;

    public AccountController(AccountService accountService, DetectionService detectionService) {
        this.accountService = accountService;
        this.detectionService = detectionService;
    }

    @GetMapping
    public Map<String, Object> list(
        @RequestParam(required = false) String project,
        @RequestParam(required = false) String search
    ) {
        List<AccountDtos.AccountResponse> accounts = accountService.search(project, search);
        return Map.of("items", accounts, "total", accounts.size(), "summary", accountService.summary());
    }

    @PostMapping("/{id}/actions")
    public Object action(@PathVariable UUID id, @Valid @RequestBody AccountDtos.AccountActionRequest request) {
        if ("retest".equals(request.action())) return detectionService.startOne(id);
        return accountService.action(id, request);
    }

    @PostMapping("/sync")
    public Object sync() { return accountService.sync(); }

    @PostMapping("/check")
    public RunDtos.RunResponse check() {
        return detectionService.startNow("MANUAL");
    }
}
