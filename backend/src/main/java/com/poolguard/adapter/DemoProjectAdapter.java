package com.poolguard.adapter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 仅由 demo profile 注册，不访问任何真实项目。 */
public class DemoProjectAdapter implements ProjectAdapter {
    private final String code;
    private final Map<String, Boolean> states = new ConcurrentHashMap<>();
    private final Map<String, Integer> checks = new ConcurrentHashMap<>();
    public DemoProjectAdapter(String code) {
        this.code = code;
        states.put("demo-good", true);
        states.put("demo-recover", true);
        states.put("demo-bad", true);
        states.put("demo-manual", false);
    }
    public String projectCode() { return code; }
    public boolean enabled() { return true; }
    public String capability() { return "演示：模拟回答与调度状态，不访问真实账号"; }
    public List<String> refreshModels(String id) {
        if (!states.containsKey(id)) throw new AdapterException("演示账号不存在");
        return List.of("demo-model", "demo-model-fast", "demo-model-reasoning");
    }
    public List<ExternalAccount> listAccounts() {
        return states.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e ->
            new ExternalAccount(e.getKey(), e.getKey() + "@example.test", "demo-model", e.getValue())).toList();
    }
    public Answer ask(String id, String model, String question) {
        if (!states.containsKey(id)) throw new AdapterException("演示账号不存在");
        int attempt = checks.merge(id, 1, Integer::sum);
        if (id.equals("demo-bad") || id.equals("demo-recover") && attempt == 1) return new Answer("错误答案", 15);
        return new Answer(question.contains("17") ? "408" : "演示回答：此题未配置模拟答案", 15);
    }
    public void setSchedulable(String id, boolean enabled) {
        if (!states.containsKey(id)) throw new AdapterException("演示账号不存在");
        states.put(id, enabled);
    }
}
