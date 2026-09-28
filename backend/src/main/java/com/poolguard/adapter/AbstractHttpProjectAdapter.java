package com.poolguard.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.*;

abstract class AbstractHttpProjectAdapter implements ProjectAdapter {
    protected final RestClient client;
    protected final ObjectMapper mapper;
    protected final String model;
    private final boolean enabled;
    protected AbstractHttpProjectAdapter(ObjectMapper mapper, String url, String key, boolean enabled, String model) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000);
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory)
            .defaultHeader("x-api-key", key).build();
        this.mapper = mapper;
        this.enabled = enabled && !key.isBlank();
        this.model = model;
    }
    protected int successCode() { return 0; }
    public boolean enabled() { return enabled; }
    protected JsonNode json(HttpMethod method, String path, Object body) {
        requireEnabled();
        try {
            var request = client.method(method).uri(path).contentType(MediaType.APPLICATION_JSON);
            if (body != null) request.body(body);
            JsonNode root = request.retrieve().body(JsonNode.class);
            if (root == null || !root.has("data") || root.get("data").isNull()
                    || (root.has("code") && root.get("code").asInt(-1) != successCode())
                    || (root.has("success") && !root.get("success").asBoolean()))
                throw new AdapterException(projectCode() + " 管理接口响应格式无效");
            return root.get("data");
        } catch (RestClientResponseException e) {
            throw new AdapterException(projectCode() + " 管理接口 HTTP " + e.getStatusCode().value());
        } catch (AdapterException e) { throw e;
        } catch (Exception e) { throw new AdapterException(projectCode() + " 连接失败或超时"); }
    }
    protected void requireEnabled() {
        if (!enabled()) throw new AdapterException(projectCode() + " 尚未配置管理 API Key");
    }
    protected String masked(String value) {
        if (value == null || value.isBlank()) return "未提供邮箱";
        int at = value.indexOf('@');
        return at > 0 ? value.substring(0, 1) + "•••" + value.substring(at) : "账号•••";
    }
    protected String id(JsonNode item) {
        String value = item.path("id").asText("");
        if (value.isBlank()) throw new AdapterException("账号目录缺少 ID");
        return value;
    }
    public Answer ask(String id, String model, String question) {
        requireEnabled();
        throw new AdapterException(capability());
    }
}
