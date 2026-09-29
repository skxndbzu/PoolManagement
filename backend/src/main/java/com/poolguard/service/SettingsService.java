package com.poolguard.service;

import com.poolguard.dto.SettingsDtos;
import com.poolguard.model.AppSetting;
import com.poolguard.repository.AppSettingRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class SettingsService {
    private static final String SCHEDULE_VALUE = "schedule_value";
    private static final String SCHEDULE_UNIT = "schedule_unit";
    private static final String RESTORE_PASSES = "restore_passes";
    private static final String NEXT_RUN_AT = "next_run_at";

    private final AppSettingRepository repository;
    private final OperationLock lock;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public SettingsService(AppSettingRepository repository, OperationLock lock, org.springframework.transaction.PlatformTransactionManager manager) {
        this.repository = repository;
        this.lock = lock;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    public SettingsDtos.SettingsResponse get() {
        int value = intValue(SCHEDULE_VALUE, 5);
        String unit = stringValue(SCHEDULE_UNIT, "minutes");
        int restorePasses = intValue(RESTORE_PASSES, 1);
        OffsetDateTime nextRun = nextRunAt();
        return new SettingsDtos.SettingsResponse(value, unit, restorePasses, nextRun);
    }

    public SettingsDtos.SettingsResponse update(SettingsDtos.SettingsRequest request) {
        if (request == null || request.scheduleValue() == null || request.restorePasses() == null
                || request.scheduleValue() < 1 || request.scheduleValue() > 999
                || request.restorePasses() < 1 || request.restorePasses() > 20
                || !isSupportedUnit(request.scheduleUnit())) {
            throw new IllegalArgumentException("间隔必须为 1–999，单位为 minutes/hours/days，恢复次数为 1–20");
        }
        try (var lease = lock.acquire()) {
        lease.assertHeld();
        return transactions.execute(status -> {
        put(SCHEDULE_VALUE, String.valueOf(request.scheduleValue()));
        put(SCHEDULE_UNIT, request.scheduleUnit());
        put(RESTORE_PASSES, String.valueOf(request.restorePasses()));
        put(NEXT_RUN_AT, OffsetDateTime.now(ZoneOffset.UTC).plus(request.scheduleValue(), unit(request.scheduleUnit())).toString());
        return get();
        });
        }
    }

    @Transactional
    public void moveNextRunForward() {
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
        put(NEXT_RUN_AT, base.plus(intValue(SCHEDULE_VALUE, 5), unit(stringValue(SCHEDULE_UNIT, "minutes"))).toString());
    }

    public OffsetDateTime nextRunAt() {
        String raw = stringValue(NEXT_RUN_AT, null);
        if (raw == null) return OffsetDateTime.now(ZoneOffset.UTC);
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(raw.replace(' ', 'T'));
            } catch (Exception ignoredAgain) {
                return OffsetDateTime.now(ZoneOffset.UTC);
            }
        }
    }

    public int scheduleValue() { return intValue(SCHEDULE_VALUE, 5); }
    public String scheduleUnit() { return stringValue(SCHEDULE_UNIT, "minutes"); }

    private void put(String key, String value) {
        AppSetting setting = repository.findById(key).orElseGet(() -> new AppSetting(key, value, OffsetDateTime.now(ZoneOffset.UTC)));
        setting.setValue(value);
        setting.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        repository.save(setting);
    }

    private int intValue(String key, int fallback) {
        try { return Integer.parseInt(stringValue(key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private String stringValue(String key, String fallback) {
        return repository.findById(key).map(AppSetting::getValue).orElse(fallback);
    }

    private boolean isSupportedUnit(String unit) { return "minutes".equals(unit) || "hours".equals(unit) || "days".equals(unit); }
    private ChronoUnit unit(String value) { return switch (value) { case "hours" -> ChronoUnit.HOURS; case "days" -> ChronoUnit.DAYS; default -> ChronoUnit.MINUTES; }; }
}
