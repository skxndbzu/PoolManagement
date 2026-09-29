package com.poolguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import com.poolguard.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.time.OffsetDateTime;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
class DetectionHistoryTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AccountService accountService;
    @Autowired ManagedAccountRepository accounts;
    @Autowired DetectionRunRepository runs;
    @Autowired DetectionResultRepository results;

    @Test void historyFiltersWholeDatasetWithoutDuplicatingBatches() throws Exception {
        accountService.sync();
        var cpr = accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs", "demo-good").orElseThrow();
        var sub = accounts.findByProjectCodeAndExternalAccountId("sub2api", "demo-good").orElseThrow();
        var start = OffsetDateTime.parse("2026-09-29T10:00:00+08:00");
        var ids = new ArrayList<UUID>();
        for (int i=0; i<25; i++) {
            var status = i == 23 ? RunStatus.RUNNING : i == 24 ? RunStatus.FAILED : RunStatus.SUCCEEDED;
            var run = runs.save(DetectionRun.builder().id(UUID.randomUUID()).triggerType(i%2==0?"MANUAL":"SCHEDULED")
                .status(status).totalAccounts(1).passedAccounts(1).failedAccounts(0).errorAccounts(0)
                .startedAt(start.plusMinutes(i)).finishedAt(status==RunStatus.RUNNING?null:start.plusMinutes(i).plusNanos(43_423_000_000L)).build());
            ids.add(run.getId());
            result(run, cpr.getId(), "PASS", 43423);
        }
        var last = runs.findById(ids.get(24)).orElseThrow();
        result(last, cpr.getId(), "PASS", 123);
        result(last, sub.getId(), "ERROR", null);
        mvc.perform(get("/api/detection-runs/search")).andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"pool-admin\"}")).andReturn();
        String auth = "Bearer " + mapper.readTree(login.getResponse().getContentAsString()).path("token").asText();
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(25)).andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items.length()").value(20)).andExpect(jsonPath("$.items[0].id").value(ids.get(24).toString()))
            .andExpect(jsonPath("$.items[0].durationMs").value(43423));
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("page","2").param("pageSize","10"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(10))
            .andExpect(jsonPath("$.items[0].id").value(ids.get(14).toString()));
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth)
            .param("project","codex-proxy-rs").param("account","DEMO-GOOD").param("outcome","PASS")
            .param("triggerType","MANUAL").param("status","SUCCEEDED")
            .param("from","2026-09-29T02:04:00Z").param("to","2026-09-29T10:08:00+08:00"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
            .andExpect(jsonPath("$.items[0].id").value(ids.get(8).toString()));
        // 多个匹配的题目不会让批次重复；另一个账号的异常不应与 CPR 条件拼接命中。
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("project","codex-proxy-rs").param("outcome","PASS"))
            .andExpect(jsonPath("$.total").value(25));
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("project","codex-proxy-rs").param("outcome","ERROR"))
            .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("account",sub.getId().toString()).param("outcome","ERROR"))
            .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("account","%"))
            .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("status","RUNNING"))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].durationMs").isNumber());
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("page","4"))
            .andExpect(jsonPath("$.items.length()").value(0));
        for (String[] invalid : List.of(new String[]{"page","0"},new String[]{"pageSize","101"},new String[]{"status","UNKNOWN"},
                new String[]{"triggerType","UNKNOWN"},new String[]{"outcome","UNKNOWN"},new String[]{"from","bad-date"})) {
            mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param(invalid[0],invalid[1]))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/detection-runs/search").header("Authorization",auth).param("from",start.plusDays(1).toString()).param("to",start.toString()))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/detection-runs/"+last.getId()+"/results").header("Authorization",auth))
            .andExpect(jsonPath("$[0].latencyMs").value(43423));
        mvc.perform(get("/api/detection-runs").header("Authorization",auth)).andExpect(jsonPath("$.length()").value(20));
    }
    private void result(DetectionRun run, UUID account, String outcome, Integer latency) {
        results.save(DetectionResult.builder().id(UUID.randomUUID()).runId(run.getId()).accountId(account)
            .passed(outcome.equals("PASS")).score(outcome.equals("PASS")?100:0).outcome(outcome)
            .latencyMs(latency).createdAt(run.getStartedAt().plusNanos(results.count()*1_000_000)).build());
    }
}
