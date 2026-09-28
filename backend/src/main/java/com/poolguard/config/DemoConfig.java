package com.poolguard.config;

import com.poolguard.adapter.*;
import com.poolguard.model.CheckPolicy;
import com.poolguard.repository.CheckPolicyRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Configuration
@Profile("demo")
public class DemoConfig {
    @Bean ProjectAdapter demoSub2api() { return new DemoProjectAdapter("sub2api"); }
    @Bean ProjectAdapter demoCodexProxy() { return new DemoProjectAdapter("codex-proxy-rs"); }
    @Bean ApplicationRunner seedDemo(CheckPolicyRepository policies) {
        return args -> {
            if (policies.count() == 0) policies.save(CheckPolicy.builder().id(UUID.randomUUID())
                .title("计算 17 × 24。只输出十进制整数，不要解释。")
                .expectedAnswer("408").capabilityTag("算术演示").sortOrder(1).active(true).version(1)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build());
        };
    }
}
