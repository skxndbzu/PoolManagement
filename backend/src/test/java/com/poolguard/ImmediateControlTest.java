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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:immediate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
class ImmediateControlTest {
    @Autowired DetectionService detection;
    @Autowired AccountService service;
    @Autowired SettingsService settings;
    @Autowired AppSettingRepository settingRows;
    @Autowired ManagedAccountRepository accounts;
    @Autowired DetectionRunRepository runs;
    @Autowired DetectionResultRepository results;
    @Autowired CheckPolicyRepository policies;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter adapter;

    @Test void scheduledChecksDisableOnWrongAnswerKeepStateOnErrorsAndRestoreOnPass() {
        service.sync();
        assertThat(settings.get().restorePasses()).isEqualTo(1);
        for (var a : accounts.findAll()) {
            a.setMonitoring(a.getId().equals(account().getId())); accounts.save(a);
        }
        var first = policies.findAll().get(0);
        var second = policies.save(CheckPolicy.builder().id(UUID.randomUUID()).title("第二题").expectedAnswer("408")
            .capabilityTag("test").sortOrder(2).active(true).version(1).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        doReturn(true).when(adapter).supportsIsolationReceipts();
        doReturn(ProjectAdapter.IsolationState.ISOLATED).when(adapter).isolationState(eq("demo-good"),anyString());
        doAnswer(call -> { adapter.setSchedulable("demo-good",false); return null; })
            .when(adapter).isolate(eq("demo-good"),anyString(),anyString());
        doAnswer(call -> { adapter.setSchedulable("demo-good",true); return null; })
            .when(adapter).restoreIsolation(eq("demo-good"),anyString());
        doReturn(new ProjectAdapter.Answer("答错了",1,"1")).when(adapter).ask(eq("demo-good"),anyString(),eq(first.getTitle()));
        doAnswer(call -> { Thread.sleep(30); throw new AdapterException("HTTP 502"); })
            .when(adapter).ask(eq("demo-good"),anyString(),eq(second.getTitle()));
        var mixed = check();
        assertThat(mixed.getErrorAccounts()).isEqualTo(1);
        var error = results.findByRunIdOrderByCreatedAtAsc(mixed.getId()).stream().filter(r -> r.getOutcome().equals("ERROR")).findFirst().orElseThrow();
        assertThat(error.getLatencyMs()).isGreaterThanOrEqualTo(20);
        assertThat(account().getDisabledByGuard()).isFalse();
        verify(adapter,never()).isolate(eq("demo-good"),anyString(),anyString());
        doReturn(new ProjectAdapter.Answer("408",1,"1")).when(adapter).ask(eq("demo-good"),anyString(),eq(second.getTitle()));
        assertThat(check().getFailedAccounts()).isEqualTo(1);
        assertThat(account().getDisabledByGuard()).isTrue();
        assertThat(account().getMonitoring()).isTrue();
        verify(adapter).isolate(eq("demo-good"),anyString(),eq("1"));
        doThrow(new AdapterException("HTTP 502")).when(adapter).ask(eq("demo-good"),anyString(),eq(first.getTitle()));
        assertThat(check().getErrorAccounts()).isEqualTo(1);
        assertThat(account().getDisabledByGuard()).isTrue();
        verify(adapter,never()).restoreIsolation(eq("demo-good"),anyString());
        doReturn(new ProjectAdapter.Answer("答案是 **408**，以下是证明。",1,"2"))
            .when(adapter).ask(eq("demo-good"),anyString(),anyString());
        assertThat(check().getPassedAccounts()).isEqualTo(1);
        assertThat(account().getDisabledByGuard()).isFalse();
        assertThat(account().getStatus()).isEqualTo(AccountStatus.HEALTHY);
        assertThat(account().getGuardOperationId()).isNull();
        verify(adapter).restoreIsolation(eq("demo-good"),anyString());
        verify(adapter,never()).setSchedulable("demo-manual",true);
    }
    private ManagedAccount account() {
        return accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs","demo-good").orElseThrow();
    }
    private DetectionRun check() {
        settingRows.save(new AppSetting("next_run_at",OffsetDateTime.now().minusMinutes(1).toString(),OffsetDateTime.now()));
        var run = detection.startNow("SCHEDULED");
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.findById(run.id()).orElseThrow().getStatus()!=RunStatus.RUNNING);
        return runs.findById(run.id()).orElseThrow();
    }
}
