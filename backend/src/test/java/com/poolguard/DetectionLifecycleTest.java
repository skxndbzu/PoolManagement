package com.poolguard;

import com.poolguard.adapter.*;
import com.poolguard.dto.*;
import com.poolguard.model.*;
import com.poolguard.repository.*;
import com.poolguard.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("demo")
class DetectionLifecycleTest {
    @Autowired DetectionService detection;
    @Autowired AccountService service;
    @Autowired SettingsService settings;
    @Autowired ManagedAccountRepository accounts;
    @Autowired DetectionRunRepository runs;
    @Autowired DetectionResultRepository results;
    @Autowired CheckPolicyRepository policies;
    @Autowired OperationLock lock;
    @MockitoSpyBean(name="demoSub2api") ProjectAdapter sub;
    @MockitoSpyBean(name="demoCodexProxy") ProjectAdapter codex;

    @Test void fullLifecycleAndFailuresDoNotEnableManualAccounts() {
        settings.update(new SettingsDtos.SettingsRequest(5,"minutes",2));
        service.sync();
        assertThat(accounts.count()).isEqualTo(8);
        var manual=find("demo-manual");
        assertThat(manual.getMonitoring()).isFalse();
        run();
        var recovering=find("demo-recover");
        assertThat(recovering.getStatus()).isEqualTo(AccountStatus.DEGRADED);
        assertThat(recovering.getDisabledByGuard()).isTrue();
        run();
        assertThat(find("demo-recover").getStatus()).isEqualTo(AccountStatus.RECOVERING);
        assertThat(find("demo-recover").getPassStreak()).isEqualTo(1);
        run();
        assertThat(find("demo-recover").getStatus()).isEqualTo(AccountStatus.HEALTHY);
        assertThat(find("demo-recover").getDisabledByGuard()).isFalse();
        assertThat(find("demo-manual").getCheckCount()).isZero();
        verify(sub,never()).setSchedulable("demo-manual",true);
        assertThat(find("demo-bad").getStatus()).isEqualTo(AccountStatus.DEGRADED);

        int before=find("demo-good").getCheckCount();
        var one=detection.startOne(find("demo-good").getId()); waitRun(one.id());
        assertThat(find("demo-good").getCheckCount()).isEqualTo(before+1);
        assertThat(runs.findById(one.id()).orElseThrow().getTotalAccounts()).isEqualTo(1);

        doThrow(new AdapterException("HTTP 429")).when(sub).ask(eq("demo-good"),anyString(),anyString());
        var error=detection.startOne(find("demo-good").getId()); waitRun(error.id());
        assertThat(find("demo-good").getStatus()).isEqualTo(AccountStatus.HEALTHY);
        assertThat(runs.findById(error.id()).orElseThrow().getErrorAccounts()).isEqualTo(1);
        assertThat(results.findByRunIdOrderByCreatedAtAsc(error.id()).get(0).getOutcome()).isEqualTo("ERROR");
        verify(sub,never()).setSchedulable("demo-good",false);
        doCallRealMethod().when(sub).ask(eq("demo-good"),anyString(),anyString());

        doThrow(new AdapterException("HTTP 503")).when(sub).setSchedulable("demo-manual",true);
        assertThatThrownBy(()->service.action(manual.getId(),new AccountDtos.AccountActionRequest("restore"))).isInstanceOf(AdapterException.class);
        assertThat(find("demo-manual").getMonitoring()).isFalse();

        service.action(find("demo-good").getId(),new AccountDtos.AccountActionRequest("disable"));
        assertThatThrownBy(()->detection.startOne(find("demo-good").getId())).hasMessageContaining("手工停用");
        run();
        assertThat(find("demo-good").getStatus()).isEqualTo(AccountStatus.DISABLED);

        try(var lease=lock.acquire()) {
            assertThatThrownBy(()->detection.startNow("MANUAL")).hasMessageContaining("正在执行");
            assertThatThrownBy(()->service.action(manual.getId(),new AccountDtos.AccountActionRequest("restore"))).hasMessageContaining("正在执行");
        }
        var policy=policies.findAll().get(0);
        policy.setActive(false);policies.save(policy);
        assertThatThrownBy(()->detection.startNow("MANUAL")).hasMessageContaining("至少一道");
        // 检测器传给上游的只是题目，不能附带标准答案。
        verify(sub,atLeastOnce()).ask(anyString(),anyString(),eq(policy.getTitle()));
    }
    private ManagedAccount find(String id){return accounts.findByProjectCodeAndExternalAccountId("sub2api",id).orElseThrow();}
    private void run(){waitRun(detection.startNow("MANUAL").id());}
    private void waitRun(UUID id){await().atMost(Duration.ofSeconds(10)).until(()->runs.findById(id).orElseThrow().getStatus()!=RunStatus.RUNNING);}
}
