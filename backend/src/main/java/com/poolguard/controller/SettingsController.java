package com.poolguard.controller;

import com.poolguard.dto.SettingsDtos;
import com.poolguard.service.SettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsDtos.SettingsResponse get() { return settingsService.get(); }

    @PatchMapping
    public SettingsDtos.SettingsResponse update(@Valid @RequestBody SettingsDtos.SettingsRequest request) { return settingsService.update(request); }
}
