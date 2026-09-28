package com.poolguard.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@Profile("!demo")
public class CodexProxyAdapter extends AbstractHttpProjectAdapter {
    public CodexProxyAdapter(ObjectMapper mapper,
            @Value("${poolguard.adapters.codex-proxy-rs.base-url}") String url,
            @Value("${poolguard.adapters.codex-proxy-rs.internal-token:}") String key,
            @Value("${poolguard.adapters.codex-proxy-rs.enabled:true}") boolean enabled,
            @Value("${poolguard.adapters.codex-proxy-rs.model:gpt-5.2}") String model) {
        super(mapper, url, key, enabled, model);
    }
    @Override protected int successCode() { return 200; }
    public String projectCode() { return "codex-proxy-rs"; }
    public String capability() { return "暂不支持能力检测：当前 codex-proxy-rs 仅支持固定题目连通测试，需扩展定向答题接口"; }
    public List<ExternalAccount> listAccounts() {
        var accounts = new ArrayList<ExternalAccount>();
        var seen = new HashSet<String>();
        for (int page = 1; page <= 10000; page++) {
            var data = json(HttpMethod.GET, "/api/admin/accounts?page=" + page + "&pageSize=200&provider=openai", null);
            var items = data.path("items");
            if (!items.isArray() || !data.path("page").path("totalPages").canConvertToInt())
                throw new AdapterException("codex-proxy-rs 账号分页响应无效");
            for (var item : items) {
                String id = id(item);
                if (!seen.add(id)) throw new AdapterException("codex-proxy-rs 分页重复，请重试同步");
                accounts.add(new ExternalAccount(id, masked(item.path("email").asText()), model,
                    item.path("enabled").asBoolean(false)));
            }
            if (page >= data.path("page").path("totalPages").asInt()) return accounts;
            if (items.isEmpty()) break;
        }
        throw new AdapterException("codex-proxy-rs 账号目录未完整读取");
    }
    public void setSchedulable(String id, boolean enabled) {
        // 使用部分更新，避免覆盖并发、权重和分组设置。
        json(HttpMethod.POST, "/api/admin/accounts/batch-update", Map.of("accountIds", List.of(id), "enabled", enabled));
        var detail = json(HttpMethod.GET, "/api/admin/accounts/detail?accountId="
            + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8), null).path("account");
        if (!detail.has("enabled") || detail.path("enabled").asBoolean() != enabled)
            throw new AdapterException("codex-proxy-rs 未确认调度状态");
    }
}
