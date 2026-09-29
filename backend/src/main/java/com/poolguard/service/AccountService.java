package com.poolguard.service;

import com.poolguard.adapter.*;
import com.poolguard.dto.AccountDtos;
import com.poolguard.model.*;
import com.poolguard.repository.ManagedAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class AccountService {
    private final ManagedAccountRepository repository;
    private final ProjectAdapterRegistry adapters;
    private final OperationLock lock;
    private final AccountSyncService sync;
    public AccountService(ManagedAccountRepository repository, ProjectAdapterRegistry adapters, OperationLock lock, AccountSyncService sync) {
        this.repository=repository;this.adapters=adapters;this.lock=lock;this.sync=sync;
    }
    public List<AccountDtos.AccountResponse> search(String project, String search) {
        return repository.search(blank(project),blank(search)).stream().filter(ManagedAccount::getSourcePresent).map(this::toResponse).toList();
    }
    public AccountDtos.AccountSummary summary() {
        var all=repository.findAll().stream().filter(ManagedAccount::getSourcePresent).toList();
        return new AccountDtos.AccountSummary(all.size(),count(all,AccountStatus.HEALTHY),count(all,AccountStatus.RECOVERING),
            count(all,AccountStatus.DEGRADED),all.stream().filter(a -> !a.getRemoteEnabled()).count());
    }
    private long count(List<ManagedAccount> accounts, AccountStatus status) { return accounts.stream().filter(a->a.getStatus()==status).count(); }
    public List<AccountSyncService.Report> sync() {
        try(var lease=lock.acquire()) { return sync.sync(lease); }
    }
    public AccountDtos.AccountResponse syncModels(UUID id) {
        try (var lease = lock.acquire()) {
            var account = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
            if (!account.getSourcePresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已从源项目移除");
            var fetched = adapters.forProject(account.getProjectCode()).refreshModels(account.getExternalAccountId());
            if (fetched == null || fetched.size() > 500) throw new AdapterException("上游模型目录格式无效或超过 500 个模型");
            var models = new LinkedHashSet<String>();
            for (String model : fetched) {
                if (model == null || model.isBlank() || model.length() > 200 || model.chars().anyMatch(Character::isWhitespace)
                        || model.chars().anyMatch(Character::isISOControl))
                    throw new AdapterException("上游模型 ID 格式无效，已保留原目录");
                models.add(model);
            }
            lease.assertHeld();
            account.setUpstreamModels(String.join("\n", models));
            account.setModelsSyncedAt(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
            return toResponse(repository.save(account));
        }
    }
    public AccountDtos.AccountResponse updateModels(UUID id, AccountDtos.DetectionModelsRequest request) {
        try (var lease = lock.acquire()) {
            var account = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
            if (!account.getSourcePresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已从源项目移除");
            var models = new LinkedHashSet<String>();
            for (String value : request.models()) {
                String model = value.trim();
                if (model.isEmpty() || model.length() > 200 || model.chars().anyMatch(Character::isWhitespace)
                        || model.chars().anyMatch(Character::isISOControl))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型 ID 不能包含空白或控制字符，且不能超过 200 字符");
                models.add(model);
            }
            lease.assertHeld();
            account.setDetectionModels(String.join("\n", models));
            account.setPassStreak(0); account.setFailStreak(0); account.setEvaluationFingerprint(null);
            return toResponse(repository.save(account));
        }
    }
    public AccountDtos.AccountResponse action(UUID id, AccountDtos.AccountActionRequest request) {
        try(var lease=lock.acquire()) {
            var account=repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"账号不存在"));
            if (!account.getSourcePresent()) throw new ResponseStatusException(HttpStatus.CONFLICT,"账号已从源项目移除");
            var adapter=adapters.forProject(account.getProjectCode());
            lease.assertHeld();
            switch(request.action()) {
                case "restore" -> {
                    adapter.setSchedulable(account.getExternalAccountId(),true);
                    account.setRemoteEnabled(true);
                    account.setMonitoring(true); account.setDisabledByGuard(false); account.setPassStreak(0);
                    account.setStatus(AccountStatus.UNTESTED); account.setLastFailureReason(null);
                }
                case "disable" -> {
                    adapter.setSchedulable(account.getExternalAccountId(),false);
                    account.setRemoteEnabled(false);
                    account.setMonitoring(false); account.setDisabledByGuard(false); account.setPassStreak(0);
                    account.setStatus(AccountStatus.DISABLED); account.setNextCheckAt(null);
                }
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"不支持的账号动作");
            }
            account.setGuardOperationId(null); account.setFailStreak(0);
            org.slf4j.LoggerFactory.getLogger(getClass()).info("[人工操作] project={} account={} email={} action={}",
                account.getProjectCode(), account.getExternalAccountId(), account.getEmailMasked(), request.action());
            return toResponse(repository.save(account));
        }
    }
    public AccountDtos.AccountResponse toResponse(ManagedAccount a) {
        return new AccountDtos.AccountResponse(a.getId(),a.getExternalAccountId(),a.getEmailMasked(),a.getProjectCode(),a.getModel(),a.getStatus(),
            a.getScore(),a.getLatencyMs(),a.getCheckCount(),a.getLastCheckAt(),a.getNextCheckAt(),a.getMonitoring(),a.getSourcePresent(),
            a.getDisabledByGuard(),a.getPassStreak(),a.getLastFailureReason(),a.getRemoteEnabled(),a.modelsToDetect(),
            a.modelsFromUpstream(),a.getModelsSyncedAt());
    }
    private String blank(String s) { return s==null||s.isBlank()?null:s; }
}
