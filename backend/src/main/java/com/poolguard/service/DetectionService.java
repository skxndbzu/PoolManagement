package com.poolguard.service;

import com.poolguard.adapter.*;
import com.poolguard.dto.RunDtos;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DetectionService {
    private final DetectionRunRepository runs;
    private final DetectionResultRepository results;
    private final ManagedAccountRepository accounts;
    private final CheckPolicyRepository policies;
    private final SettingsService settings;
    private final TaskExecutor executor;
    private final ProjectAdapterRegistry adapters;
    private final AnswerEvaluator evaluator;
    private final OperationLock lock;
    private final AccountSyncService sync;

    public DetectionService(DetectionRunRepository runs, DetectionResultRepository results,
            ManagedAccountRepository accounts, CheckPolicyRepository policies, SettingsService settings,
            TaskExecutor detectionExecutor, ProjectAdapterRegistry adapters, AnswerEvaluator evaluator,
            OperationLock lock, AccountSyncService sync) {
        this.runs=runs; this.results=results; this.accounts=accounts; this.policies=policies;
        this.settings=settings; this.executor=detectionExecutor; this.adapters=adapters;
        this.evaluator=evaluator; this.lock=lock; this.sync=sync;
    }
    public RunDtos.RunResponse startNow(String trigger) { return start(trigger, null); }
    public RunDtos.RunResponse startOne(UUID accountId) { return start("RETEST", accountId); }
    private RunDtos.RunResponse start(String trigger, UUID accountId) {
        var lease = lock.acquire();
        DetectionRun run = null;
        try {
            if ("SCHEDULED".equals(trigger) && settings.nextRunAt().isAfter(now())) {
                lease.close(); return latest();
            }
            if (adapters.enabledAdapters().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "尚未配置任何项目管理 API Key");
            var snapshot = policies.findByActiveTrueOrderBySortOrderAsc();
            if (snapshot.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请先启用至少一道检测题目");
            if (accountId != null) {
                var account = accounts.findById(accountId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
                if (!account.getMonitoring() || !account.getSourcePresent())
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已手工停用或已从源项目移除");
            }
            // 获得全局锁说明不存在活跃批次，之前遗留的 RUNNING 属于中断任务。
            for (var interrupted : runs.findByStatus(RunStatus.RUNNING)) {
                interrupted.setStatus(RunStatus.FAILED); interrupted.setFinishedAt(now());
                interrupted.setErrorMessage("进程中断；未完成账号将在下轮重新检测"); runs.save(interrupted);
            }
            run = runs.save(DetectionRun.builder().id(UUID.randomUUID()).triggerType(trigger).status(RunStatus.RUNNING)
                .totalAccounts(0).passedAccounts(0).failedAccounts(0).errorAccounts(0).startedAt(now()).build());
            UUID runId = run.getId();
            RunDtos.RunResponse response = toResponse(run);
            if ("SCHEDULED".equals(trigger)) settings.moveNextRunForward();
            executor.execute(() -> execute(runId, accountId, snapshot, lease));
            return response;
        } catch (RuntimeException e) {
            try {
                if (run != null) { run.setStatus(RunStatus.FAILED); run.setFinishedAt(now()); run.setErrorMessage("无法提交检测任务"); runs.save(run); }
            } finally { lease.close(); }
            throw e;
        }
    }
    private void execute(UUID runId, UUID accountId, List<CheckPolicy> snapshot, OperationLock.Lease lease) {
        try (lease) {
            var run = runs.findById(runId).orElseThrow();
            try {
                var reports = sync.sync(lease);
                Set<String> available = new HashSet<>();
                var errors = new ArrayList<String>();
                for (var report : reports) {
                    if (report.success()) available.add(report.project()); else errors.add(report.message());
                }
                var targets = accounts.findAll().stream().filter(a -> a.getSourcePresent() && a.getMonitoring()
                    && available.contains(a.getProjectCode()) && (accountId == null || accountId.equals(a.getId()))).toList();
                run.setTotalAccounts(targets.size()); runs.save(run);
                for (var account : targets) {
                    lease.assertHeld();
                    String outcome = detect(runId, account, snapshot, lease);
                    if (outcome.equals("PASS")) run.setPassedAccounts(run.getPassedAccounts()+1);
                    else if (outcome.equals("FAIL")) run.setFailedAccounts(run.getFailedAccounts()+1);
                    else run.setErrorAccounts(run.getErrorAccounts()+1);
                    runs.save(run);
                }
                if (!errors.isEmpty()) run.setErrorMessage(String.join("；", errors));
                run.setStatus(!errors.isEmpty() || run.getErrorAccounts()>0 ? RunStatus.FAILED : RunStatus.SUCCEEDED);
                if (targets.isEmpty() && errors.isEmpty()) run.setErrorMessage("没有可检测账号，请先同步账号或恢复监测");
            } catch (Exception e) {
                run.setStatus(RunStatus.FAILED); run.setErrorMessage("检测任务中断，请检查后端与数据库连接");
            } finally { run.setFinishedAt(now()); runs.save(run); }
        }
    }
    private String detect(UUID runId, ManagedAccount account, List<CheckPolicy> snapshot, OperationLock.Lease lease) {
        var adapter = adapters.forProject(account.getProjectCode());
        int passed=0, latency=0;
        String problem=null;
        for (var policy : snapshot) {
            var result = DetectionResult.builder().id(UUID.randomUUID()).runId(runId).accountId(account.getId())
                .policyId(policy.getId()).question(policy.getTitle()).expectedAnswer(policy.getExpectedAnswer()).createdAt(now());
            try {
                // 只发送题目，标准答案仅用于本地判分。
                var answer=adapter.ask(account.getExternalAccountId(), account.getModel(), policy.getTitle());
                lease.assertHeld();
                boolean ok=evaluator.passes(answer.text(), policy.getExpectedAnswer());
                if (ok) passed++;
                latency+=answer.latencyMs();
                results.save(result.passed(ok).score(ok?100:0).outcome(ok?"PASS":"FAIL").latencyMs(answer.latencyMs())
                    .answerExcerpt(excerpt(answer.text())).reason(ok?null:"回答未匹配标准答案").build());
            } catch (AdapterException e) {
                problem=e.getMessage();
                results.save(result.passed(false).score(0).outcome("ERROR").reason(problem).build());
                break;
            }
        }
        account.setCheckCount(account.getCheckCount()+1);
        account.setLastCheckAt(now()); account.setNextCheckAt(nextCheck()); account.setLatencyMs(latency);
        String outcome;
        if (problem != null) {
            account.setPassStreak(0); account.setLastFailureReason(problem); outcome="ERROR";
        } else {
            account.setScore(100*passed/snapshot.size());
            boolean ok=passed==snapshot.size();
            outcome=ok?"PASS":"FAIL";
            try {
                lease.assertHeld();
                if (!ok) {
                    account.setPassStreak(0); account.setStatus(AccountStatus.DEGRADED);
                    account.setLastFailureReason("回答未匹配标准答案，已请求隔离");
                    // 先记录隔离所有权，以便远程成功后数据库中断仍可复检恢复。
                    account.setDisabledByGuard(true); accounts.save(account);
                    adapter.setSchedulable(account.getExternalAccountId(), false);
                } else if (account.getDisabledByGuard()) {
                    account.setPassStreak(account.getPassStreak()+1);
                    if (account.getPassStreak()>=settings.get().restorePasses()) {
                        adapter.setSchedulable(account.getExternalAccountId(), true);
                        account.setDisabledByGuard(false); account.setStatus(AccountStatus.HEALTHY);
                        account.setLastFailureReason(null);
                    } else {
                        account.setStatus(AccountStatus.RECOVERING);
                        account.setLastFailureReason("恢复观察中，尚未达到连续通过次数");
                    }
                } else {
                    account.setPassStreak(account.getPassStreak()+1); account.setStatus(AccountStatus.HEALTHY);
                    account.setLastFailureReason(null);
                }
            } catch (AdapterException e) {
                outcome="ERROR"; account.setLastFailureReason("状态回写未确认："+e.getMessage());
                results.save(DetectionResult.builder().id(UUID.randomUUID()).runId(runId).accountId(account.getId())
                    .passed(false).score(account.getScore()).outcome("ERROR").reason(account.getLastFailureReason()).createdAt(now()).build());
            }
        }
        accounts.save(account);
        return outcome;
    }
    private OffsetDateTime nextCheck() {
        return now().plus(settings.scheduleValue(), switch(settings.scheduleUnit()) {
            case "hours" -> ChronoUnit.HOURS; case "days" -> ChronoUnit.DAYS; default -> ChronoUnit.MINUTES;
        });
    }
    private String excerpt(String value) { return value==null?null:value.substring(0,Math.min(value.length(),4000)); }
    public RunDtos.RunResponse latest() { return history().stream().findFirst().orElse(null); }
    public List<RunDtos.RunResponse> history() { return runs.findTop20ByOrderByStartedAtDesc().stream().map(this::toResponse).toList(); }
    public List<DetectionResult> results(UUID id) { return results.findByRunIdOrderByCreatedAtAsc(id); }
    private RunDtos.RunResponse toResponse(DetectionRun run) {
        return new RunDtos.RunResponse(run.getId(),run.getTriggerType(),run.getStatus(),run.getTotalAccounts(),
            run.getPassedAccounts(),run.getFailedAccounts(),run.getErrorAccounts(),run.getErrorMessage(),run.getStartedAt(),run.getFinishedAt());
    }
    private OffsetDateTime now() { return OffsetDateTime.now(java.time.ZoneOffset.UTC); }
}
