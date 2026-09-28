package com.poolguard.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@Profile("!demo")
public class Sub2ApiAdapter extends AbstractHttpProjectAdapter {
    public Sub2ApiAdapter(ObjectMapper mapper,
            @Value("${poolguard.adapters.sub2api.base-url}") String url,
            @Value("${poolguard.adapters.sub2api.internal-token:}") String key,
            @Value("${poolguard.adapters.sub2api.enabled:true}") boolean enabled,
            @Value("${poolguard.adapters.sub2api.model:gpt-5.2}") String model) {
        super(mapper, url, key, enabled, model);
    }
    public String projectCode() { return "sub2api"; }
    public String capability() { return "暂不支持能力检测：当前 sub2api 部分分支忽略自定义题目，需扩展并验证定向答题接口"; }
    public List<ExternalAccount> listAccounts() {
        var accounts = new ArrayList<ExternalAccount>();
        var seen = new HashSet<String>();
        for (int page = 1; page <= 10000; page++) {
            var data = json(HttpMethod.GET, "/api/v1/admin/accounts?page=" + page
                + "&page_size=200&lite=1&platform=openai&sort_by=id&sort_order=asc", null);
            var items = data.path("items");
            if (!items.isArray() || !data.path("total").canConvertToInt()) throw new AdapterException("sub2api 账号分页响应无效");
            for (var item : items) {
                String id = id(item);
                if (!seen.add(id)) throw new AdapterException("sub2api 分页重复，请重试同步");
                accounts.add(new ExternalAccount(id, masked(item.path("name").asText()), model,
                    item.path("schedulable").asBoolean(false) && "active".equals(item.path("status").asText())));
            }
            if (accounts.size() >= data.path("total").asInt()) return accounts;
            if (items.isEmpty()) break;
        }
        throw new AdapterException("sub2api 账号目录未完整读取");
    }
    public void setSchedulable(String id, boolean enabled) {
        if (!id.matches("[0-9]+")) throw new AdapterException("sub2api 账号 ID 无效");
        var result = json(HttpMethod.POST, "/api/v1/admin/accounts/" + id + "/schedulable", Map.of("schedulable", enabled));
        if (!result.has("schedulable") || result.path("schedulable").asBoolean() != enabled)
            throw new AdapterException("sub2api 未确认调度状态");
    }
}
