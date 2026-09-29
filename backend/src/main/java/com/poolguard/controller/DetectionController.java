package com.poolguard.controller;

import com.poolguard.dto.RunDtos;
import com.poolguard.service.DetectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.OffsetDateTime;
import com.poolguard.model.RunStatus;

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

    @GetMapping("/search")
    public RunDtos.RunPage search(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String account,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return detectionService.search(page, pageSize, status, triggerType, project, account, outcome, from, to);
    }
}
