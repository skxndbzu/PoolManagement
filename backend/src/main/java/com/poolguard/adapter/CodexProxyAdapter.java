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
    public String capability() { return "支持自定义题目、停用账号复检及隔离回执；需部署 CPR PoolGuard 扩展"; }
    @Override public List<String> refreshModels(String id) {
        var data = json(HttpMethod.POST, "/api/admin/accounts/models/refresh", Map.of("accountId", id));
        var items = data.path("models");
        if (!items.isArray() || items.size() > 500)
            throw new AdapterException("CPR 模型目录格式无效或超过 500 个模型");
        var models = new LinkedHashSet<String>();
        for (var item : items) {
            if (!item.path("id").isTextual()) throw new AdapterException("CPR 模型目录缺少模型 ID");
            models.add(item.path("id").asText());
        }
        return List.copyOf(models);
    }
    @Override public Answer ask(String id, String model, String question) {
        long started = System.nanoTime();
        var data = json(HttpMethod.POST, "/api/admin/accounts/poolguard/probe",
            Map.of("accountId", id, "model", model, "prompt", question));
        String text = data.path("text").asText("");
        String version = data.path("controlVersion").asText("");
        if (!id.equals(data.path("accountId").asText()) || !model.equals(data.path("model").asText())
                || !data.path("completed").asBoolean(false) || text.isBlank() || !version.matches("[0-9]+"))
            throw new AdapterException("CPR 答题未完整完成或账号、模型、控制版本不匹配");
        return new Answer(text, (int) Math.min(Integer.MAX_VALUE, (System.nanoTime()-started)/1_000_000), version);
    }
    @Override public boolean supportsIsolationReceipts() { return true; }
    @Override public IsolationState isolationState(String id, String operationId) {
        return control(id, operationId, "inspect", null);
    }
    @Override public void isolate(String id, String operationId, String controlVersion) {
        if (controlVersion == null) throw new AdapterException("CPR 答题缺少控制版本，不能自动禁用");
        if (control(id, operationId, "disable", controlVersion) != IsolationState.ISOLATED)
            throw new ControlConflictException("CPR 未确认本次自动隔离的归属");
    }
    @Override public void restoreIsolation(String id, String operationId) {
        if (control(id, operationId, "restore", null) != IsolationState.RESTORED)
            throw new ControlConflictException("CPR 未确认本次隔离已恢复");
    }
    private IsolationState control(String id, String operationId, String action, String version) {
        var body = new HashMap<String, Object>();
        body.put("accountId", id); body.put("operationId", operationId); body.put("action", action);
        if (version != null) body.put("expectedControlVersion", version);
        var data = json(HttpMethod.POST, "/api/admin/accounts/poolguard/control", body);
        if (!id.equals(data.path("accountId").asText()) || !operationId.equals(data.path("operationId").asText()))
            throw new AdapterException("CPR 隔离回执与请求不匹配");
        IsolationState state;
        try { state = IsolationState.valueOf(data.path("state").asText().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new AdapterException("CPR 隔离回执状态无效"); }
        if (!data.path("enabled").isBoolean() || !data.path("controlVersion").asText("").matches("[0-9]+"))
            throw new AdapterException("CPR 隔离回执缺少启停状态或控制版本");
        boolean enabled = data.path("enabled").asBoolean();
        if ((state == IsolationState.ISOLATED && enabled)
                || ((state == IsolationState.RESTORED || state == IsolationState.NONE) && !enabled))
            throw new AdapterException("CPR 隔离回执与实际启停状态矛盾");
        return state;
    }
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
