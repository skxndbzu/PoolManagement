package com.poolguard.controller;

import com.poolguard.dto.RunDtos;
import com.poolguard.service.DetectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/detection-runs")
public class DetectionController {
    private final DetectionService detectionService;

    public DetectionController(DetectionService detectionService) {
        this.detectionService = detectionService;
    }

    @PostMapping
    public RunDtos.RunResponse start() { return detectionService.startNow("MANUAL"); }

    @GetMapping("/{id}/results")
    public Object results(@org.springframework.web.bind.annotation.PathVariable java.util.UUID id) { return detectionService.results(id); }

    @GetMapping("/latest")
    public RunDtos.RunResponse latest() { return detectionService.latest(); }

    @GetMapping
    public List<RunDtos.RunResponse> history() { return detectionService.history(); }
}
