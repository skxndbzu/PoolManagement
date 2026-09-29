package com.poolguard;

import com.poolguard.adapter.*;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import com.poolguard.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:scheduled-coverage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
class ScheduledCoverageTest {
    @Autowired AccountService service;
    @Autowired DetectionService detection;
    @Autowired SettingsService settings;
    @Autowired AppSettingRepository settingRows;
    @Autowired ManagedAccountRepository accounts;
    @Autowired CheckPolicyRepository policies;
    @Autowired DetectionRunRepository runs;
    @Autowired DetectionResultRepository results;
    @Autowired OperationLock lock;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter adapter;

    @Test void scheduledMatrixContinuesAfterErrorsAndNeverOverlaps() throws Exception {
        service.sync();
        var first = policies.findByActiveTrueOrderBySortOrderAsc().get(0);
        var second = policies.save(CheckPolicy.builder().id(UUID.randomUUID()).title("第二题").expectedAnswer("408")
            .capabilityTag("coverage").sortOrder(2).active(true).version(1).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        for (var account : accounts.findAll()) {
            boolean target = account.getProjectCode().equals("codex-proxy-rs") && Set.of("demo-good","demo-recover").contains(account.getExternalAccountId());
            account.setMonitoring(target);
            if (target) account.setDetectionModels("model-a\nmodel-b");
            accounts.save(account);
        }
        doReturn(true).when(adapter).supportsIsolationReceipts();
        doReturn(ProjectAdapter.IsolationState.ISOLATED).when(adapter).isolationState(anyString(),anyString());
        doAnswer(call -> { adapter.setSchedulable(call.getArgument(0),false); return null; }).when(adapter).isolate(anyString(),anyString(),anyString());
        doAnswer(call -> { adapter.setSchedulable(call.getArgument(0),true); return null; }).when(adapter).restoreIsolation(anyString(),anyString());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test probe was not released");
            String id=call.getArgument(0), model=call.getArgument(1), question=call.getArgument(2);
            if (id.equals("demo-good") && model.equals("model-a") && question.equals(first.getTitle()))
                throw new AdapterException("HTTP 502 timeout");
            return new ProjectAdapter.Answer(model.equals("model-b") && question.equals(second.getTitle()) ? "wrong" : "408",1,"1");
        }).when(adapter).ask(anyString(),anyString(),anyString());
        var scheduler = new SchedulerService(settings,detection);
        due(); scheduler.dispatchDueRun();
        UUID id = detection.latest().id();
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(detection.latest().plannedChecks()).isEqualTo(8);
            assertThat(detection.latest().completedChecks()).isZero();
            long count = runs.count();
            due(); scheduler.dispatchDueRun(); // 即使又到期，任务锁仍阻止叠加。
            assertThat(runs.count()).isEqualTo(count);
        } finally { release.countDown(); }
        var run = finished(id);
        assertThat(run.getCompletedChecks()).isEqualTo(8);
        assertThat(run.getErrorAccounts()).isEqualTo(1);
        assertThat(run.getFailedAccounts()).isEqualTo(1);
        var records=results.findByRunIdOrderByCreatedAtAsc(id);
        assertThat(records).hasSize(8);
        for (String external : List.of("demo-good","demo-recover")) {
            var account=account(external);
            assertThat(records.stream().filter(r -> r.getAccountId().equals(account.getId()))
                .map(r -> r.getModel()+"/"+r.getPolicyId()).toList()).containsExactlyInAnyOrder(
                    "model-a/"+first.getId(),"model-a/"+second.getId(),"model-b/"+first.getId(),"model-b/"+second.getId());
        }
        assertThat(account("demo-good").getRemoteEnabled()).isTrue();
        verify(adapter,never()).isolate(eq("demo-good"),anyString(),anyString());
        assertThat(account("demo-recover").getDisabledByGuard()).isTrue();
        assertThat(account("demo-recover").getRemoteEnabled()).isFalse();
        verify(adapter,never()).ask(eq("demo-manual"),anyString(),anyString());

        // 第二轮仍覆盖自动隔离账号，所有模型通过后恢复。
        doReturn(new ProjectAdapter.Answer("408",1,"2")).when(adapter).ask(anyString(),anyString(),anyString());
        due(); scheduler.dispatchDueRun();
        var recovered=finished(detection.latest().id());
        assertThat(recovered.getCompletedChecks()).isEqualTo(8);
        assertThat(recovered.getPassedAccounts()).isEqualTo(2);
        verify(adapter).restoreIsolation(eq("demo-recover"),anyString());
        assertThat(account("demo-recover").getRemoteEnabled()).isTrue();

        // 控制版本改变时停止该账号，进度保留未完成数量，而其他账号继续。
        doAnswer(call -> new ProjectAdapter.Answer("408",1,call.getArgument(2).equals(first.getTitle()) ? "3" : "4"))
            .when(adapter).ask(eq("demo-good"),anyString(),anyString());
        due(); scheduler.dispatchDueRun();
        var conflict=finished(detection.latest().id());
        assertThat(conflict.getPlannedChecks()).isEqualTo(8);
        assertThat(conflict.getCompletedChecks()).isEqualTo(6);
        assertThat(account("demo-good").getMonitoring()).isFalse();
        assertThat(conflict.getErrorAccounts()).isEqualTo(1);
    }

    private ManagedAccount account(String external) {
        return accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs",external).orElseThrow();
    }
    private void due() {
        settingRows.save(new AppSetting("next_run_at",OffsetDateTime.now().minusMinutes(1).toString(),OffsetDateTime.now()));
    }
    private DetectionRun finished(UUID id) {
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.findById(id).orElseThrow().getStatus()!=RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(10)).ignoreException(org.springframework.web.server.ResponseStatusException.class)
            .until(() -> { try(var lease=lock.acquire()) { lease.assertHeld(); return true; } });
        return runs.findById(id).orElseThrow();
    }
}
