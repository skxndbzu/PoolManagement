package com.poolguard.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.LoggerFactory;
import java.time.OffsetDateTime;

@Component
@ConditionalOnProperty(name="poolguard.scheduler.enabled",havingValue="true",matchIfMissing=true)
public class SchedulerService {
    private final SettingsService settings;
    private final DetectionService detection;
    public SchedulerService(SettingsService settings,DetectionService detection) { this.settings=settings;this.detection=detection; }
    @Scheduled(fixedDelayString="${poolguard.scheduler.tick-ms:30000}")
    public void dispatchDueRun() {
        if (settings.nextRunAt().isAfter(OffsetDateTime.now())) return;
        try { detection.startNow("SCHEDULED"); }
        catch(ResponseStatusException e) { LoggerFactory.getLogger(getClass()).debug("暂缓自动检测：{}",e.getReason()); }
    }
}
