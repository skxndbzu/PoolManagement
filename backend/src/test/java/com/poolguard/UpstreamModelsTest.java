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
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:upstream-models;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class UpstreamModelsTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountService service;
    @Autowired ManagedAccountRepository accounts;
    @Autowired DetectionRunRepository runs;
    @Autowired DetectionResultRepository results;
    @Autowired OperationLock lock;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter adapter;

    @Test void refreshPersistsAccountCatalogAndAllowsRetestWithoutChangingScheduledModels() throws Exception {
        service.sync();
        String path="/api/accounts/"+account().getId();
        mvc.perform(post(path+"/models/sync")).andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"pool-admin\"}")).andReturn();
        String auth="Bearer "+mapper.readTree(login.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(post("/api/accounts/"+UUID.randomUUID()+"/models/sync").header("Authorization",auth)).andExpect(status().isNotFound());
        try(var lease=lock.acquire()) {
            lease.assertHeld();
            mvc.perform(post(path+"/models/sync").header("Authorization",auth)).andExpect(status().isConflict());
            verify(adapter,never()).refreshModels(anyString());
        }
        doReturn(List.of("model-fast","model-reasoning","model-fast")).when(adapter).refreshModels("demo-good");
        mvc.perform(post(path+"/models/sync").header("Authorization",auth)).andExpect(status().isOk())
            .andExpect(jsonPath("$.upstreamModels.length()").value(2))
            .andExpect(jsonPath("$.detectionModels[0]").value("demo-model"))
            .andExpect(jsonPath("$.modelsSyncedAt").isNotEmpty());
        var syncedAt=account().getModelsSyncedAt();
        service.sync();
        assertThat(account().modelsFromUpstream()).containsExactly("model-fast","model-reasoning");
        assertThat(accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs","demo-recover").orElseThrow().modelsFromUpstream()).isEmpty();
        mvc.perform(get("/api/accounts").header("Authorization",auth).param("project","codex-proxy-rs").param("search","demo-good"))
            .andExpect(jsonPath("$.items[0].upstreamModels[1]").value("model-reasoning"));
        doThrow(new AdapterException("HTTP 502")).when(adapter).refreshModels("demo-good");
        mvc.perform(post(path+"/models/sync").header("Authorization",auth)).andExpect(status().isBadGateway());
        doReturn(List.of("valid","bad\nmodel")).when(adapter).refreshModels("demo-good");
        mvc.perform(post(path+"/models/sync").header("Authorization",auth)).andExpect(status().isBadGateway());
        assertThat(account().modelsFromUpstream()).containsExactly("model-fast","model-reasoning");
        assertThat(account().getModelsSyncedAt()).isEqualTo(syncedAt);

        // 目录模型可直接复检；它未加入定时计划，单独通过不能提前解禁。
        doReturn(new ProjectAdapter.Answer("wrong",1)).when(adapter).ask(eq("demo-good"),eq("model-fast"),anyString());
        var failed=retest(auth,"model-fast");
        assertThat(failed.getFailedAccounts()).isEqualTo(1);
        assertThat(account().getRemoteEnabled()).isFalse();
        assertThat(results.findByRunIdOrderByCreatedAtAsc(failed.getId())).extracting(DetectionResult::getModel).containsExactly("model-fast");
        doReturn(new ProjectAdapter.Answer("408",1)).when(adapter).ask(eq("demo-good"),eq("model-fast"),anyString());
        assertThat(retest(auth,"model-fast").getPassedAccounts()).isEqualTo(1);
        assertThat(account().getRemoteEnabled()).isFalse();
        assertThat(account().modelsToDetect()).containsExactly("demo-model");
        mvc.perform(put(path+"/models").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"models\":[\"model-fast\"]}")).andExpect(status().isOk());
        assertThat(retest(auth,"model-fast").getPassedAccounts()).isEqualTo(1);
        assertThat(account().getRemoteEnabled()).isTrue();

        // 成功的空目录清除候选，但不删除用户已保存的检测计划。
        doReturn(List.of()).when(adapter).refreshModels("demo-good");
        mvc.perform(post(path+"/models/sync").header("Authorization",auth)).andExpect(status().isOk())
            .andExpect(jsonPath("$.upstreamModels").isEmpty()).andExpect(jsonPath("$.detectionModels[0]").value("model-fast"));
        mvc.perform(post(path+"/actions").header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"retest\",\"model\":\"model-reasoning\"}")).andExpect(status().isBadRequest());
    }
    private ManagedAccount account() { return accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs","demo-good").orElseThrow(); }
    private DetectionRun retest(String auth,String model) throws Exception {
        var response=mvc.perform(post("/api/accounts/"+account().getId()+"/actions").header("Authorization",auth)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new com.poolguard.dto.AccountDtos.AccountActionRequest("retest",model))))
            .andExpect(status().isOk()).andReturn();
        UUID id=UUID.fromString(mapper.readTree(response.getResponse().getContentAsString()).path("id").asText());
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.findById(id).orElseThrow().getStatus()!=RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(10)).ignoreException(org.springframework.web.server.ResponseStatusException.class)
            .until(() -> { try(var lease=lock.acquire()) { lease.assertHeld(); return true; } });
        return runs.findById(id).orElseThrow();
    }
}
