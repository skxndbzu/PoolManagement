package com.poolguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poolguard.adapter.*;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import com.poolguard.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:multi-model;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class MultiModelDetectionTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountService service;
    @Autowired DetectionService detection;
    @Autowired ManagedAccountRepository accounts;
    @Autowired DetectionRunRepository runs;
    @Autowired DetectionResultRepository results;
    @Autowired AppSettingRepository settings;
    @Autowired OperationLock lock;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter adapter;

    @Test void modelsPersistAcrossSyncAndControlUsesFullCoverage() throws Exception {
        service.sync();
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"pool-admin\"}")).andExpect(status().isOk()).andReturn();
        String auth = "Bearer " + mapper.readTree(login.getResponse().getContentAsString()).path("token").asText();
        String path = "/api/accounts/" + account().getId();
        for (String body : new String[]{"{}", "{\"models\":[]}", "{\"models\":[null]}", "{\"models\":[\"a b\"]}", "{\"models\":[\"a\\nb\"]}"}) {
            mvc.perform(put(path+"/models").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(put(path+"/models").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"models\":[\" model-a \",\"model-b\",\"model-a\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.detectionModels.length()").value(2))
            .andExpect(jsonPath("$.detectionModels[0]").value("model-a"));
        service.sync();
        assertThat(account().modelsToDetect()).containsExactly("model-a", "model-b");
        mvc.perform(post(path+"/actions").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"retest\",\"model\":\"unconfigured\"}"))
            .andExpect(status().isBadRequest());
        doReturn(true).when(adapter).supportsIsolationReceipts();
        doReturn(ProjectAdapter.IsolationState.ISOLATED).when(adapter).isolationState(eq("demo-good"),anyString());
        doAnswer(call -> { adapter.setSchedulable("demo-good",false); return null; })
            .when(adapter).isolate(eq("demo-good"),anyString(),anyString());
        doAnswer(call -> { adapter.setSchedulable("demo-good",true); return null; })
            .when(adapter).restoreIsolation(eq("demo-good"),anyString());

        // 一模型答错、另一模型异常：整轮不能操作账号。
        doAnswer(call -> new ProjectAdapter.Answer("wrong",1,"1")).when(adapter).ask(eq("demo-good"),eq("model-a"),anyString());
        doThrow(new AdapterException("probe timeout")).when(adapter).ask(eq("demo-good"),eq("model-b"),anyString());
        var error = retest(auth, null);
        assertThat(error.getErrorAccounts()).isEqualTo(1);
        assertThat(account().getRemoteEnabled()).isTrue();
        verify(adapter,never()).isolate(anyString(),anyString(),anyString());
        assertThat(results.findByRunIdOrderByCreatedAtAsc(error.getId())).extracting(DetectionResult::getModel)
            .containsExactly("model-a","model-b");

        // 指定模型复检只发送该模型，答错会禁用整个账号。
        clearInvocations(adapter);
        assertThat(retest(auth,"model-a").getFailedAccounts()).isEqualTo(1);
        verify(adapter).ask(eq("demo-good"),eq("model-a"),anyString());
        verify(adapter,never()).ask(eq("demo-good"),eq("model-b"),anyString());
        assertThat(account().getDisabledByGuard()).isTrue();
        assertThat(account().getRemoteEnabled()).isFalse();
        mvc.perform(get("/api/accounts").param("search","model-b").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].enabled").value(false))
            .andExpect(jsonPath("$.items[0].disabledByGuard").value(true));

        doReturn(new ProjectAdapter.Answer("408",1,"2")).when(adapter).ask(eq("demo-good"),anyString(),anyString());
        assertThat(retest(auth,"model-a").getPassedAccounts()).isEqualTo(1);
        assertThat(account().getRemoteEnabled()).isFalse();
        assertThat(account().getPassStreak()).isZero();
        verify(adapter,never()).restoreIsolation(anyString(),anyString());

        // 定时入口沿用保存的两个模型；全部通过才恢复。
        for (var other : accounts.findAll()) {
            if (!other.getId().equals(account().getId())) { other.setMonitoring(false); accounts.save(other); }
        }
        clearInvocations(adapter);
        settings.save(new AppSetting("next_run_at",java.time.OffsetDateTime.now().minusMinutes(1).toString(),java.time.OffsetDateTime.now()));
        var passed = finished(detection.startNow("SCHEDULED").id());
        assertThat(passed.getPassedAccounts()).isEqualTo(1);
        verify(adapter).ask(eq("demo-good"),eq("model-a"),anyString());
        verify(adapter).ask(eq("demo-good"),eq("model-b"),anyString());
        verify(adapter).restoreIsolation(eq("demo-good"),anyString());
        assertThat(account().getRemoteEnabled()).isTrue();
        assertThat(account().getDisabledByGuard()).isFalse();
        assertThat(results.findByRunIdOrderByCreatedAtAsc(passed.getId())).extracting(DetectionResult::getModel)
            .containsExactly("model-a","model-b");
    }

    private ManagedAccount account() {
        return accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs","demo-good").orElseThrow();
    }
    private DetectionRun retest(String auth, String model) throws Exception {
        var response = mvc.perform(post("/api/accounts/"+account().getId()+"/actions").header("Authorization",auth)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new com.poolguard.dto.AccountDtos.AccountActionRequest("retest", model))))
            .andExpect(status().isOk()).andReturn();
        return finished(UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText()));
    }
    private DetectionRun finished(UUID id) {
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.findById(id).orElseThrow().getStatus()!=RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(10)).ignoreException(org.springframework.web.server.ResponseStatusException.class)
            .until(() -> { try (var lease=lock.acquire()) { lease.assertHeld(); return true; } });
        return runs.findById(id).orElseThrow();
    }
}
