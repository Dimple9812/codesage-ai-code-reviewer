package com.codesage.settings;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codesage.security.CurrentUser;
import com.codesage.settings.SettingsDtos.ApiKeyRequest;
import com.codesage.settings.SettingsDtos.SettingsResponse;
import com.codesage.settings.SettingsDtos.UpdateSettingsRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings")
@Tag(name = "Settings")
public class SettingsController {

    private final SettingsService service;
    private final CurrentUser currentUser;

    public SettingsController(SettingsService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public SettingsResponse get() {
        return service.get(currentUser.requireId());
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody UpdateSettingsRequest request) {
        return service.update(currentUser.requireId(), request);
    }

    @Operation(summary = "Store a personal AI provider key (encrypted; only the last 4 characters are ever shown)")
    @PutMapping("/ai-key")
    public SettingsResponse setKey(@Valid @RequestBody ApiKeyRequest request) {
        return service.setApiKey(currentUser.requireId(), request.apiKey());
    }

    @DeleteMapping("/ai-key")
    public SettingsResponse clearKey() {
        return service.clearApiKey(currentUser.requireId());
    }
}
