package com.poolguard;

import com.poolguard.adapter.*;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import com.poolguard.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:cpr-lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "poolguard.detection.disable-failures=2"})
@ActiveProfiles("demo")
@ExtendWith(OutputCaptureExtension.class)
class CprDetectionLifecycleTest {
    @Autowired DetectionService detection;
    @Autowired AccountService service;
    @Autowired ManagedAccountRepository accounts;
    @Autowired DetectionRunRepository runs;
    @Autowired CheckPolicyRepository policies;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter adapter;

    @Test void receiptsFailuresRecoveryAndManualOverride(CapturedOutput output) {
        service.sync();
        doReturn(true).when(adapter).supportsIsolationReceipts();
        doReturn(new ProjectAdapter.Answer("wrong",1,"12")).when(adapter).ask(eq("demo-good"),anyString(),anyString());
        doNothing().when(adapter).isolate(eq("demo-good"),anyString(),eq("12"));
        doNothing().when(adapter).restoreIsolation(eq("demo-good"),anyString());
        check();
        assertThat(account().getDisabledByGuard()).isFalse();
        assertThat(account().getFailStreak()).isEqualTo(1);
        verify(adapter,never()).isolate(anyString(),anyString(),anyString());
        check();
        assertThat(account().getDisabledByGuard()).isTrue();
        String operation=account().getGuardOperationId();
        assertThat(operation).isNotBlank();
        assertThat(output).contains("[题目未通过/疑似降智]", "[自动禁用成功]", "codex-proxy-rs/demo-good");
        doReturn(ProjectAdapter.IsolationState.ISOLATED).when(adapter).isolationState("demo-good",operation);
        doReturn(new ProjectAdapter.Answer("408",1,"13")).when(adapter).ask(eq("demo-good"),anyString(),anyString());
        check();
        assertThat(account().getStatus()).isEqualTo(AccountStatus.RECOVERING);
        assertThat(output).contains("[恢复观察]");
        // 远端恢复后响应丢失，不能打印成功或丢弃操作回执。
        doThrow(new AdapterException("连接超时")).when(adapter).restoreIsolation("demo-good",operation);
        check();
        assertThat(account().getDisabledByGuard()).isTrue();
        assertThat(output).doesNotContain("[能力恢复/自动启用成功]");
        doReturn(ProjectAdapter.IsolationState.RESTORED).when(adapter).isolationState("demo-good",operation);
        check();
        assertThat(account().getStatus()).isEqualTo(AccountStatus.HEALTHY);
        assertThat(account().getGuardOperationId()).isNull();
        assertThat(output).contains("[恢复对账确认]");

        doReturn(new ProjectAdapter.Answer("wrong",1,"14")).when(adapter).ask(eq("demo-good"),anyString(),anyString());
        doNothing().when(adapter).isolate(eq("demo-good"),anyString(),eq("14"));
        check();check();
        operation=account().getGuardOperationId();
        doReturn(ProjectAdapter.IsolationState.CONFLICT).when(adapter).isolationState("demo-good",operation);
        check();
        assertThat(account().getMonitoring()).isFalse();
        assertThat(account().getDisabledByGuard()).isFalse();
        verify(adapter,never()).restoreIsolation("demo-good",operation);
        assertThat(output).contains("[自动管控暂停]");

        // 禁用响应丢失后同步到停用状态，仍需保留监测并使用已持久化的操作 ID 对账。
        service.action(account().getId(),new com.poolguard.dto.AccountDtos.AccountActionRequest("restore"));
        doThrow(new AdapterException("禁用响应丢失")).when(adapter).isolate(eq("demo-good"),anyString(),eq("14"));
        int logStart=output.getAll().length();
        check();check();
        operation=account().getGuardOperationId();
        assertThat(operation).isNotBlank();
        assertThat(account().getDisabledByGuard()).isFalse();
        assertThat(output.getAll().substring(logStart)).doesNotContain("[自动禁用成功]");
        adapter.setSchedulable("demo-good",false);
        doReturn(ProjectAdapter.IsolationState.ISOLATED).when(adapter).isolationState("demo-good",operation);
        doThrow(new AdapterException("HTTP 429")).when(adapter).ask(eq("demo-good"),anyString(),anyString());
        check();
        assertThat(account().getMonitoring()).isTrue();
        assertThat(account().getDisabledByGuard()).isTrue();
        assertThat(account().getStatus()).isEqualTo(AccountStatus.DEGRADED);
        assertThat(account().getPassStreak()).isZero();
        assertThat(output).contains("[隔离对账确认]");
        doReturn(new ProjectAdapter.Answer("408",1,"15")).when(adapter).ask(eq("demo-good"),anyString(),anyString());
        check();check();
        assertThat(account().getDisabledByGuard()).isFalse();
        assertThat(account().getGuardOperationId()).isNull();
        verify(adapter).restoreIsolation("demo-good",operation);
        assertThat(output).contains("[能力恢复/自动启用成功]");
    }
    private ManagedAccount account() { return accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs","demo-good").orElseThrow(); }
    private void check() {
        UUID id=detection.startOne(account().getId()).id();
        await().atMost(Duration.ofSeconds(10)).until(()->runs.findById(id).orElseThrow().getStatus()!=RunStatus.RUNNING);
    }
}
