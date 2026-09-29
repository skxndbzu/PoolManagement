package com.poolguard;

import com.poolguard.adapter.ProjectAdapter;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import com.poolguard.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:matching;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "poolguard.detection.disable-failures=2"})
@ActiveProfiles("demo")
class DetectionMatchingTest {
    @Autowired AccountService accountsService;
    @Autowired DetectionService detection;
    @Autowired ManagedAccountRepository accounts;
    @Autowired CheckPolicyRepository policies;
    @Autowired DetectionRunRepository runs;
    @Autowired DetectionResultRepository results;
    @Autowired OperationLock lock;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter adapter;

    @Test void modeControlsScoringAndResetsStreaksWithoutRewritingHistory() {
        accountsService.sync();
        // 用批量检测验证配置为两次的失败计数，手动单账号复检会立即隔离。
        for (var item : accounts.findAll()) {
            item.setMonitoring(item.getId().equals(account().getId())); accounts.save(item);
        }
        var policy = policies.findAll().get(0);
        policy.setExpectedAnswer("21");
        policy.setMatchMode(MatchMode.EXACT);
        policies.save(policy);
        doReturn(new ProjectAdapter.Answer("最少需要取出 **21 颗**。以下是证明……", 1, "1"))
            .when(adapter).ask(eq("demo-good"), anyString(), anyString());
        var exact = check();
        assertThat(exact.getOutcome()).isEqualTo("FAIL");
        assertThat(account().getFailStreak()).isEqualTo(1);
        // 不修改版本号，验证匹配方式本身也参与连续次数的指纹。
        policy.setMatchMode(MatchMode.FUZZY);
        policies.save(policy);
        var fuzzy = check();
        assertThat(fuzzy.getOutcome()).isEqualTo("PASS");
        assertThat(fuzzy.getMatchMode()).isEqualTo(MatchMode.FUZZY);
        assertThat(account().getFailStreak()).isZero();
        assertThat(results.findById(exact.getId()).orElseThrow().getMatchMode()).isEqualTo(MatchMode.EXACT);
        String fingerprint = account().getEvaluationFingerprint();
        doReturn(new ProjectAdapter.Answer("答案是121。", 1, "1"))
            .when(adapter).ask(eq("demo-good"), anyString(), anyString());
        assertThat(check().getOutcome()).isEqualTo("FAIL");
        policy.setMatchMode(MatchMode.EXACT);
        policies.save(policy);
        assertThat(check().getOutcome()).isEqualTo("FAIL");
        assertThat(account().getEvaluationFingerprint()).isNotEqualTo(fingerprint);
        assertThat(account().getFailStreak()).isEqualTo(1);
        verify(adapter, never()).setSchedulable("demo-good", false);
    }
    private ManagedAccount account() {
        return accounts.findByProjectCodeAndExternalAccountId("codex-proxy-rs", "demo-good").orElseThrow();
    }
    private DetectionResult check() {
        var run = detection.startNow("MANUAL");
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.findById(run.id()).orElseThrow().getStatus() != RunStatus.RUNNING);
        await().atMost(Duration.ofSeconds(10)).ignoreException(org.springframework.web.server.ResponseStatusException.class)
            .until(() -> { try (var lease = lock.acquire()) { lease.assertHeld(); return true; } });
        return results.findByRunIdOrderByCreatedAtAsc(run.id()).get(0);
    }
}
