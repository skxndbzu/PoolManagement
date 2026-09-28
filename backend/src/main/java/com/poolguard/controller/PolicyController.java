package com.poolguard.controller;

import com.poolguard.dto.PolicyDtos;
import com.poolguard.service.PolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/check-policies")
public class PolicyController {
    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    public List<PolicyDtos.PolicyResponse> list() { return policyService.list(); }

    @PutMapping
    public List<PolicyDtos.PolicyResponse> replace(@Valid @RequestBody List<PolicyDtos.PolicySave> items) {
        return policyService.replace(items);
    }

    @PostMapping
    public PolicyDtos.PolicyResponse create(@Valid @RequestBody PolicyDtos.PolicyRequest request) { return policyService.create(request); }

    @PutMapping("/{id}")
    public PolicyDtos.PolicyResponse update(@PathVariable UUID id, @Valid @RequestBody PolicyDtos.PolicyRequest request) { return policyService.update(id, request); }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) { policyService.delete(id); }
}
