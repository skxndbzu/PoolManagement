package com.poolguard.service;

import com.poolguard.adapter.*;
import com.poolguard.model.*;
import com.poolguard.repository.ManagedAccountRepository;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class AccountSyncService {
    private final ProjectAdapterRegistry adapters;
    private final ManagedAccountRepository accounts;
    public AccountSyncService(ProjectAdapterRegistry adapters, ManagedAccountRepository accounts) {
        this.adapters = adapters; this.accounts = accounts;
    }
    public record Report(String project, int accounts, boolean success, String message) {}
    public List<Report> sync(OperationLock.Lease lease) {
        var reports = new ArrayList<Report>();
        for (var adapter : adapters.enabledAdapters()) {
            try {
                // 完整拉取成功后才标记目录中消失的账号。
                var external = adapter.listAccounts();
                lease.assertHeld();
                var seen = new HashSet<String>();
                for (var item : external) {
                    seen.add(item.id());
                    var account = accounts.findByProjectCodeAndExternalAccountId(adapter.projectCode(), item.id())
                        .orElseGet(() -> ManagedAccount.builder().id(UUID.randomUUID()).projectCode(adapter.projectCode())
                            .externalAccountId(item.id()).status(item.schedulable() ? AccountStatus.UNTESTED : AccountStatus.DISABLED)
                            .monitoring(item.schedulable()).checkCount(0).score(0).build());
                    account.setEmailMasked(item.email());
                    account.setModel(item.model());
                    account.setRemoteEnabled(item.schedulable());
                    account.setSourcePresent(true);
                    // 外部手工停用的账号不取得自动恢复所有权。
                    if (!item.schedulable() && !account.getDisabledByGuard() && account.getGuardOperationId() == null) {
                        account.setMonitoring(false); account.setStatus(AccountStatus.DISABLED);
                    }
                    accounts.save(account);
                }
                for (var account : accounts.findByProjectCode(adapter.projectCode())) {
                    if (!seen.contains(account.getExternalAccountId())) {
                        account.setSourcePresent(false); accounts.save(account);
                    }
                }
                reports.add(new Report(adapter.projectCode(), external.size(), true, adapter.capability()));
            } catch (AdapterException e) { reports.add(new Report(adapter.projectCode(), 0, false, e.getMessage())); }
        }
        return reports;
    }
}
