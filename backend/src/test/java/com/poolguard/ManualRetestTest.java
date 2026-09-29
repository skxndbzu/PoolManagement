package com.poolguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poolguard.adapter.*;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import com.poolguard.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:manual-retest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "poolguard.detection.disable-failures=3"})
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class ManualRetestTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountService service;
    @Autowired ManagedAccountRepository accounts;
    @Autowired CheckPolicyRepository policies;
    @Autowired DetectionRunRepository runs;
    @Autowired com.poolguard.service.OperationLock lock;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter adapter;

    @Test void retestDisablesOnFirstWrongRoundButNeverOnProbeErrors() throws Exception {
        service.sync();
        var first = policies.findAll().get(0);
        var second = policies.save(CheckPolicy.builder().id(UUID.randomUUID()).title("第二题").expectedAnswer("408")
            .capabilityTag("test").sortOrder(2).active(true).version(1).createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        doReturn(true).when(adapter).supportsIsolationReceipts();
        doReturn(ProjectAdapter.IsolationState.ISOLATED).when(adapter).isolationState(eq("demo-good"),anyString());
        doAnswer(call -> {
            assertThat(account().getGuardOperationId()).isEqualTo(call.getArgument(1, String.class));
            adapter.setSchedulable("demo-good", false); return null;
        }).when(adapter).isolate(eq("demo-good"),anyString(),eq("1"));
        doAnswer(call -> { adapter.setSchedulable("demo-good", true); return null; })
            .when(adapter).restoreIsolation(eq("demo-good"),anyString());
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"pool-admin\"}")).andExpect(status().isOk()).andReturn();
        String auth = "Bearer " + mapper.readTree(login.getResponse().getContentAsString()).path("token").asText();

        // 同一轮即使已有答错，只要出现异常，仍保持启用。
        doReturn(new ProjectAdapter.Answer("错误答案",1,"1")).when(adapter).ask(eq("demo-good"),anyString(),eq(first.getTitle()));
        doThrow(new AdapterException("HTTP 502")).when(adapter).ask(eq("demo-good"),anyString(),eq(second.getTitle()));
        assertThat(retest(auth).getErrorAccounts()).isEqualTo(1);
        assertThat(account().getDisabledByGuard()).isFalse();
        verify(adapter,never()).isolate(eq("demo-good"),anyString(),anyString());

        // 全局阈值为三次，手动复检答错一次就实际调用远端隔离。
        doReturn(new ProjectAdapter.Answer("408",1,"1")).when(adapter).ask(eq("demo-good"),anyString(),eq(second.getTitle()));
        assertThat(retest(auth).getFailedAccounts()).isEqualTo(1);
        assertThat(account().getFailStreak()).isEqualTo(1);
        assertThat(account().getDisabledByGuard()).isTrue();
        assertThat(account().getMonitoring()).isTrue();
        assertThat(account().getStatus()).isEqualTo(AccountStatus.DEGRADED);
        verify(adapter).isolate(eq("demo-good"),anyString(),eq("1"));
        verify(adapter).setSchedulable("demo-good", false);
        assertThat(retest(auth).getFailedAccounts()).isEqualTo(1);
        verify(adapter,times(1)).isolate(eq("demo-good"),anyString(),anyString());

        doThrow(new AdapterException("HTTP 502")).when(adapter).ask(eq("demo-good"),anyString(),eq(first.getTitle()));
        assertThat(retest(auth).getErrorAccounts()).isEqualTo(1);
        assertThat(account().getDisabledByGuard()).isTrue();
        verify(adapter,never()).restoreIsolation(eq("demo-good"),anyString());
        doReturn(new ProjectAdapter.Answer("答案为408。",1,"2")).when(adapter).ask(eq("demo-good"),anyString(),anyString());
        assertThat(retest(auth).getPassedAccounts()).isEqualTo(1);
        assertThat(account().getDisabledByGuard()).isFalse();
        verify(adapter).restoreIsolation(eq("demo-good"),anyString());
        verify(adapter,never()).setSchedulable("demo-manual",true);
    }
    private ManagedAccount account() {
        return accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs","demo-good").orElseThrow();
    }
    private DetectionRun retest(String auth) throws Exception {
        var response = mvc.perform(post("/api/accounts/"+account().getId()+"/actions").header("Authorization",auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"retest\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.triggerType").value("RETEST")).andReturn();
        UUID id = UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText());
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.findById(id).orElseThrow().getStatus()!=RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(10)).ignoreException(org.springframework.web.server.ResponseStatusException.class)
            .until(() -> { try (var lease = lock.acquire()) { lease.assertHeld(); return true; } });
        var result = runs.findById(id).orElseThrow();
        assertThat(result.getTotalAccounts()).isEqualTo(1);
        return result;
    }
}
