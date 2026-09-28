package com.poolguard.controller;

import com.poolguard.dto.AccountDtos;
import com.poolguard.dto.RunDtos;
import com.poolguard.dto.SettingsDtos;
import com.poolguard.service.AccountService;
import com.poolguard.service.DetectionService;
import com.poolguard.service.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/overview")
public class OverviewController {
    private final AccountService accountService;
    private final SettingsService settingsService;
    private final DetectionService detectionService;

    public OverviewController(AccountService accountService, SettingsService settingsService, DetectionService detectionService) {
        this.accountService = accountService;
        this.settingsService = settingsService;
        this.detectionService = detectionService;
    }

    @GetMapping
    public Map<String, Object> overview() {
        SettingsDtos.SettingsResponse settings = settingsService.get();
        RunDtos.RunResponse latest = detectionService.latest();
        return Map.of(
            "accounts", accountService.summary(),
            "settings", settings,
            "latestRun", latest == null ? Map.of() : latest
        );
    }
}
