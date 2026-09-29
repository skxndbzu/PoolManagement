package com.poolguard.service;

import com.poolguard.adapter.*;
import com.poolguard.dto.RunDtos;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class DetectionService {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(DetectionService.class);
    @Value("${poolguard.detection.disable-failures:1}")
    private int disableFailures;
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
    public RunDtos.RunResponse startNow(String trigger) { return start(trigger, null, null); }
    public RunDtos.RunResponse startOne(UUID accountId) { return startOne(accountId, null); }
    public RunDtos.RunResponse startOne(UUID accountId, String model) { return start("RETEST", accountId, model); }
    private RunDtos.RunResponse start(String trigger, UUID accountId, String selectedModel) {
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
                if (selectedModel != null && !account.canRetestModel(selectedModel))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择该账号已配置或从上游同步的模型");
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
            executor.execute(() -> execute(runId, accountId, selectedModel, snapshot, lease));
            return response;
        } catch (RuntimeException e) {
            try {
                if (run != null) { run.setStatus(RunStatus.FAILED); run.setFinishedAt(now()); run.setErrorMessage("无法提交检测任务"); runs.save(run); }
            } finally { lease.close(); }
            throw e;
        }
    }
    private void execute(UUID runId, UUID accountId, String selectedModel, List<CheckPolicy> snapshot, OperationLock.Lease lease) {
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
                run.setTotalAccounts(targets.size());
                run.setPlannedChecks(targets.stream().mapToLong(a -> selectedModel == null ? a.modelsToDetect().size() : 1).sum() * snapshot.size());
                runs.save(run);
                log.info("[检测开始] run={} trigger={} accounts={} questions={} plannedChecks={}", runId, run.getTriggerType(), targets.size(), snapshot.size(), run.getPlannedChecks());
                // 手动复检直接确认本次答题结果，不等待自动巡检的连续失败阈值。
                int failureThreshold = "RETEST".equals(run.getTriggerType()) ? 1 : Math.max(1, disableFailures);
                for (var account : targets) {
                    lease.assertHeld();
                    String outcome = detect(run, account, selectedModel, snapshot, lease, failureThreshold);
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
                log.error("[检测中断] run={} errorType={}", runId, e.getClass().getSimpleName());
            } finally {
                run.setFinishedAt(now()); runs.save(run);
                log.info("[检测结束] run={} status={} passed={} failed={} errors={}", runId, run.getStatus(),
                    run.getPassedAccounts(), run.getFailedAccounts(), run.getErrorAccounts());
            }
        }
    }
    private String detect(DetectionRun run, ManagedAccount account, String selectedModel, List<CheckPolicy> snapshot, OperationLock.Lease lease, int failureThreshold) {
        UUID runId = run.getId();
        var adapter = adapters.forProject(account.getProjectCode());
        var models = selectedModel == null ? account.modelsToDetect() : List.of(selectedModel);
        boolean fullCoverage = models.equals(account.modelsToDetect());
        String fingerprint = fingerprint(String.join("\n", models), snapshot);
        if (!fingerprint.equals(account.getEvaluationFingerprint())) {
            account.setPassStreak(0); account.setFailStreak(0); account.setEvaluationFingerprint(fingerprint);
        }
        try {
            if (!reconcileIsolation(adapter, account)) throw new ControlConflictException(account.getLastFailureReason());
        } catch (AdapterException e) {
            if (e instanceof ControlConflictException && account.getMonitoring()) pauseControl(account, e.getMessage());
            account.setPassStreak(0); account.setFailStreak(0); account.setLastFailureReason(e.getMessage());
            log.warn("[隔离对账异常] account={} reason={}", label(account), e.getMessage());
            results.save(DetectionResult.builder().id(UUID.randomUUID()).runId(runId).accountId(account.getId())
                .passed(false).score(0).outcome("ERROR").reason(e.getMessage()).createdAt(now()).build());
            accounts.save(account); return "ERROR";
        }
        int passed=0, latency=0;
        String problem=null;
        String controlVersion=null;
        questions: for (String model : models) {
          for (var policy : snapshot) {
            lease.assertHeld();
            var result = DetectionResult.builder().id(UUID.randomUUID()).runId(runId).accountId(account.getId())
                .policyId(policy.getId()).question(policy.getTitle()).expectedAnswer(policy.getExpectedAnswer())
                .model(model).matchMode(policy.getMatchMode()).createdAt(now());
            long questionStarted = System.nanoTime();
            try {
                // 只发送题目，标准答案仅用于本地判分。
                var answer=adapter.ask(account.getExternalAccountId(), model, policy.getTitle());
                lease.assertHeld();
                if (controlVersion != null && !controlVersion.equals(answer.controlVersion()))
                    throw new ControlConflictException("答题期间账号启停状态发生变化，自动管控已暂停");
                controlVersion=answer.controlVersion();
                boolean ok=evaluator.passes(answer.text(), policy.getExpectedAnswer(), policy.getMatchMode());
                if (ok) passed++;
                else log.warn("[题目未通过/疑似降智] run={} account={} model={} policy={} question={} reason=回答未匹配标准答案",
                    runId, label(account), model, policy.getId(), logText(policy.getTitle()));
                latency=(int)Math.min(Integer.MAX_VALUE, (long)latency + answer.latencyMs());
                results.save(result.passed(ok).score(ok?100:0).outcome(ok?"PASS":"FAIL").latencyMs(answer.latencyMs())
                    .answerExcerpt(excerpt(answer.text())).reason(ok?null:"回答未匹配标准答案").build());
            } catch (AdapterException e) {
                // 普通上游错误不阻断后续组合，但整轮保持启停状态；保留第一个异常摘要。
                if (problem == null) problem="模型 " + model + "：" + e.getMessage();
                if (e instanceof ControlConflictException) pauseControl(account, e.getMessage());
                log.warn("[检测异常] run={} account={} model={} policy={} reason={}", runId, label(account), model, policy.getId(), e.getMessage());
                int elapsed = (int)Math.min(Integer.MAX_VALUE, (System.nanoTime() - questionStarted) / 1_000_000);
                latency=(int)Math.min(Integer.MAX_VALUE, (long)latency + elapsed);
                results.save(result.passed(false).score(0).outcome("ERROR").latencyMs(elapsed).reason(e.getMessage()).build());
            }
            run.setCompletedChecks(run.getCompletedChecks()+1); runs.save(run);
            if (!account.getMonitoring()) break questions;
          }
        }
        account.setCheckCount(account.getCheckCount()+1);
        account.setLastCheckAt(now()); account.setNextCheckAt(account.getMonitoring() ? nextCheck() : null); account.setLatencyMs(latency);
        String outcome;
        if (problem != null) {
            account.setPassStreak(0); account.setFailStreak(0); account.setLastFailureReason(problem); outcome="ERROR";
        } else {
            if (fullCoverage) account.setScore(100*passed/(snapshot.size()*models.size()));
            boolean ok=passed==snapshot.size()*models.size();
            outcome=ok?"PASS":"FAIL";
            try {
                lease.assertHeld();
                if (!ok) {
                    account.setPassStreak(0); account.setFailStreak(Math.min(account.getFailStreak()+1, 1000));
                    if (account.getDisabledByGuard()) {
                        account.setStatus(AccountStatus.DEGRADED); account.setLastFailureReason("复检未通过，继续隔离");
                        log.info("[继续隔离] account={} score={}", label(account), account.getScore());
                    } else if (account.getFailStreak() >= failureThreshold) {
                        // 先持久化操作 ID；超时或进程中断后以相同回执对账，不能猜测远端是否成功。
                        if (account.getGuardOperationId() == null) account.setGuardOperationId(UUID.randomUUID().toString());
                        account.setLastFailureReason("已请求隔离，等待远端确认"); accounts.save(account);
                        adapter.isolate(account.getExternalAccountId(), account.getGuardOperationId(), controlVersion);
                        account.setRemoteEnabled(false);
                        account.setDisabledByGuard(true); account.setStatus(AccountStatus.DEGRADED);
                        account.setLastFailureReason("答题未达标，远端已确认自动禁用");
                        log.warn("[自动禁用成功] account={} failures={} score={} operation={}", label(account),
                            account.getFailStreak(), account.getScore(), account.getGuardOperationId());
                    } else {
                        account.setLastFailureReason("疑似降智，连续未通过 " + account.getFailStreak() + "/" + failureThreshold + " 轮");
                        log.warn("[降智观察] account={} failures={}/{}", label(account), account.getFailStreak(), failureThreshold);
                    }
                } else if (!fullCoverage) {
                    // 单模型通过不能代表整个账号恢复，也不能累计全模型恢复轮次。
                    account.setPassStreak(0); account.setFailStreak(0);
                    account.setLastFailureReason("指定模型已通过；尚未完成全部配置模型检测，保持账号启停状态");
                } else if (account.getDisabledByGuard()) {
                    account.setFailStreak(0);
                    account.setPassStreak(account.getPassStreak()+1);
                    if (account.getPassStreak()>=settings.get().restorePasses()) {
                        adapter.restoreIsolation(account.getExternalAccountId(), account.getGuardOperationId());
                        account.setRemoteEnabled(true);
                        account.setDisabledByGuard(false); account.setStatus(AccountStatus.HEALTHY);
                        account.setLastFailureReason(null); account.setGuardOperationId(null);
                        log.info("[能力恢复/自动启用成功] account={} passes={}", label(account), account.getPassStreak());
                    } else {
                        account.setStatus(AccountStatus.RECOVERING);
                        account.setLastFailureReason("恢复观察中，尚未达到连续通过次数");
                        log.info("[恢复观察] account={} passes={}/{}，保持隔离", label(account), account.getPassStreak(), settings.get().restorePasses());
                    }
                } else {
                    account.setFailStreak(0); account.setPassStreak(Math.min(account.getPassStreak()+1, 1000)); account.setStatus(AccountStatus.HEALTHY);
                    account.setLastFailureReason(null);
                }
            } catch (AdapterException e) {
                outcome="ERROR"; account.setLastFailureReason("状态回写未确认："+e.getMessage());
                if (e instanceof ControlConflictException) pauseControl(account, e.getMessage());
                log.warn("[启停未确认] account={} operation={} reason={}", label(account), account.getGuardOperationId(), e.getMessage());
                results.save(DetectionResult.builder().id(UUID.randomUUID()).runId(runId).accountId(account.getId())
                    .passed(false).score(account.getScore()).outcome("ERROR").reason(account.getLastFailureReason()).createdAt(now()).build());
            }
        }
        accounts.save(account);
        return outcome;
    }
    private boolean reconcileIsolation(ProjectAdapter adapter, ManagedAccount account) {
        if (!adapter.supportsIsolationReceipts()) return true;
        if (account.getGuardOperationId() == null) {
            if (!account.getDisabledByGuard()) return true;
            pauseControl(account, "缺少 CPR 隔离回执，请手工确认后恢复监测");
            return false;
        }
        switch (adapter.isolationState(account.getExternalAccountId(), account.getGuardOperationId())) {
            case ISOLATED -> {
                if (!account.getDisabledByGuard()) {
                    account.setStatus(AccountStatus.DEGRADED);
                    log.warn("[隔离对账确认] account={} CPR 已自动禁用", label(account));
                }
                account.setDisabledByGuard(true);
                account.setRemoteEnabled(false);
            }
            case RESTORED -> {
                account.setRemoteEnabled(true);
                account.setDisabledByGuard(false); account.setGuardOperationId(null); account.setStatus(AccountStatus.HEALTHY);
                log.info("[恢复对账确认] account={} CPR 已自动启用", label(account));
            }
            case NONE -> { account.setGuardOperationId(null); account.setDisabledByGuard(false); account.setRemoteEnabled(true); }
            case CONFLICT -> {
                pauseControl(account, "CPR 启停已被其他操作改变或隔离回执失效，请手工确认后恢复监测");
                return false;
            }
        }
        return true;
    }
    private void pauseControl(ManagedAccount account, String reason) {
        account.setMonitoring(false); account.setDisabledByGuard(false); account.setGuardOperationId(null);
        account.setPassStreak(0); account.setFailStreak(0); account.setStatus(AccountStatus.DISABLED);
        account.setLastFailureReason(reason); account.setNextCheckAt(null);
        log.warn("[自动管控暂停] account={} reason={}", label(account), reason);
    }
    private String label(ManagedAccount account) {
        return logText(account.getProjectCode()+"/"+account.getExternalAccountId()+"/"+account.getEmailMasked());
    }
    private String logText(String text) { return text == null ? "" : text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").substring(0, Math.min(text.length(), 180)); }
    private String fingerprint(String model, List<CheckPolicy> snapshot) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var values = new ArrayList<String>(); values.add(model);
            for (var p : snapshot) values.addAll(List.of(p.getId().toString(), p.getVersion().toString(), p.getTitle(), p.getExpectedAnswer(), p.getMatchMode().name()));
            for (String value : values) {
                byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
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
    public RunDtos.RunPage search(int page, int pageSize, RunStatus status, String triggerType,
            String project, String account, String outcome, OffsetDateTime from, OffsetDateTime to) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("页码从 1 开始，每页支持 1–100 条记录");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("开始时间不能晚于结束时间");
        if (triggerType != null && !Set.of("MANUAL", "SCHEDULED", "RETEST").contains(triggerType))
            throw new IllegalArgumentException("触发方式无效");
        if (outcome != null && !Set.of("PASS", "FAIL", "ERROR").contains(outcome))
            throw new IllegalArgumentException("答题结果无效");
        if ((account != null && account.length() > 200) || (project != null && project.length() > 128))
            throw new IllegalArgumentException("查询条件过长");
        String keyword = account == null ? "" : account.strip().toLowerCase(Locale.ROOT);
        org.springframework.data.jpa.domain.Specification<DetectionRun> filter = (root, query, cb) -> {
            var conditions = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (status != null) conditions.add(cb.equal(root.get("status"), status));
            if (triggerType != null) conditions.add(cb.equal(root.get("triggerType"), triggerType));
            if (from != null) conditions.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from));
            if (to != null) conditions.add(cb.lessThanOrEqualTo(root.get("startedAt"), to));
            // 用 EXISTS 保证同一批次多道题命中时不重复，账号与结果条件必须落在同一条记录上。
            if (project != null || !keyword.isEmpty() || outcome != null) {
                var matching = query.subquery(Integer.class);
                var result = matching.from(DetectionResult.class);
                var match = new ArrayList<jakarta.persistence.criteria.Predicate>();
                match.add(cb.equal(result.get("runId"), root.get("id")));
                if (outcome != null) match.add(cb.equal(result.get("outcome"), outcome));
                if (project != null || !keyword.isEmpty()) {
                    var owner = matching.from(ManagedAccount.class);
                    match.add(cb.equal(owner.get("id"), result.get("accountId")));
                    if (project != null) match.add(cb.equal(owner.get("projectCode"), project));
                    if (!keyword.isEmpty()) {
                        String pattern = "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                        var names = cb.or(cb.like(cb.lower(owner.get("externalAccountId")), pattern, '\\'),
                            cb.like(cb.lower(owner.get("emailMasked")), pattern, '\\'));
                        // 内部 UUID 也可直接查询，不依赖数据库专有的 UUID 转字符串语法。
                        try { names = cb.or(names, cb.equal(owner.get("id"), UUID.fromString(keyword))); }
                        catch (IllegalArgumentException ignored) { /* 非 UUID 时按账号文字查询。 */ }
                        match.add(names);
                    }
                }
                matching.select(cb.literal(1)).where(match.toArray(jakarta.persistence.criteria.Predicate[]::new));
                conditions.add(cb.exists(matching));
            }
            return cb.and(conditions.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        var found = runs.findAll(filter, org.springframework.data.domain.PageRequest.of(page - 1, pageSize,
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("startedAt"),
                org.springframework.data.domain.Sort.Order.desc("id"))));
        return new RunDtos.RunPage(found.stream().map(this::toResponse).toList(), found.getTotalElements(), page, pageSize, found.getTotalPages());
    }
    private RunDtos.RunResponse toResponse(DetectionRun run) {
        return new RunDtos.RunResponse(run.getId(),run.getTriggerType(),run.getStatus(),run.getTotalAccounts(),
            run.getPassedAccounts(),run.getFailedAccounts(),run.getErrorAccounts(),run.getErrorMessage(),run.getStartedAt(),run.getFinishedAt(),
            Math.max(0, java.time.Duration.between(run.getStartedAt(), run.getFinishedAt() == null ? now() : run.getFinishedAt()).toMillis()),
            run.getPlannedChecks(),run.getCompletedChecks());
    }
    private OffsetDateTime now() { return OffsetDateTime.now(java.time.ZoneOffset.UTC); }
}
