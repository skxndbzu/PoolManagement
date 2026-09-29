package com.poolguard;

import com.poolguard.adapter.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AdapterContractTest {
    @Test void refreshModelsUsesAccountScopedAdminEndpoint() throws Exception {
        var mapper = new ObjectMapper();
        var response = new java.util.concurrent.atomic.AtomicReference<String>("{\"models\":[{\"id\":\"model-a\",\"label\":\"Display name\"},{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}");
        var bodies = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var methods = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var keys = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/admin/accounts/models/refresh", exchange -> {
            methods.add(exchange.getRequestMethod()); keys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes=("{\"code\":200,\"data\":"+response.get()+"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            var adapter = new CodexProxyAdapter(mapper,"http://127.0.0.1:"+server.getAddress().getPort(),"test-admin-key",true,"default-model");
            assertThat(adapter.refreshModels("account/one")).containsExactly("model-a","model-b");
            assertThat(bodies.get(0)).isEqualTo(mapper.readTree("{\"accountId\":\"account/one\"}"));
            response.set("{\"models\":[]}");
            assertThat(adapter.refreshModels("second")).isEmpty();
            for (String broken : java.util.List.of("{}","{\"models\":{}}","{\"models\":[{\"label\":\"no ID\"}]}","{\"models\":[{\"id\":123}]}")) {
                response.set(broken);
                assertThatThrownBy(() -> adapter.refreshModels("first")).isInstanceOf(AdapterException.class);
            }
            assertThat(methods).containsOnly("POST"); assertThat(keys).containsOnly("test-admin-key");
        } finally { server.stop(0); }
    }
    @Test void codexEnvelopeUses200AndPartialUpdateDoesNotOverwriteSettings() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new AtomicInteger();
        var bodies=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var headers=new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/api/admin/accounts", exchange->{
            requests.incrementAndGet();headers.add(exchange.getRequestHeaders().getFirst("x-api-key"));
            String path=exchange.getRequestURI().getPath();
            String data;
            if(path.endsWith("batch-update")) {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                data="{\"accountIds\":[\"acct_1\"],\"configRevision\":2}";
            } else if(path.endsWith("detail")) data="{\"account\":{\"enabled\":false}}";
            else data="{\"items\":[{\"id\":\"acct_1\",\"email\":\"alice@example.test\",\"enabled\":true}],\"page\":{\"totalPages\":1}}";
            byte[] body=("{\"code\":200,\"data\":"+data+"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var adapter=new CodexProxyAdapter(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"test-key",true,"test-model");
            var items=adapter.listAccounts();
            assertThat(items).hasSize(1);assertThat(items.get(0).email()).isEqualTo("a•••@example.test");
            assertThat(items.get(0).model()).isEqualTo("test-model");
            adapter.setSchedulable("acct_1",false);
            assertThat(new ObjectMapper().readTree(bodies.get(0)).size()).isEqualTo(2);
            assertThat(headers).containsOnly("test-key");
        } finally { server.stop(0); }
    }
    @Test void cprProbeRequiresCompleteAnswerAndControlReceipt() throws Exception {
        var mapper=new ObjectMapper();
        var response=new java.util.concurrent.atomic.AtomicReference<String>("{\"accountId\":\"acct_1\",\"model\":\"test-model\",\"text\":\"408\",\"completed\":true,\"controlVersion\":\"12\"}");
        var status=new AtomicInteger(200);
        var bodies=new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/admin/accounts/poolguard/", exchange->{
            bodies.add(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes=("{\"code\":"+status.get()+",\"data\":"+response.get()+"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status.get(),bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var adapter=new CodexProxyAdapter(mapper,"http://127.0.0.1:"+server.getAddress().getPort(),"test-key",true,"test-model");
            assertThat(adapter.ask("acct_1","test-model","17 × 24，仅返回数字").text()).isEqualTo("408");
            assertThat(bodies.get(0).size()).isEqualTo(3);
            assertThat(bodies.get(0).path("prompt").asText()).isEqualTo("17 × 24，仅返回数字");
            // 半截回答即使文本正确，也不能进入判分；CPR 拒绝异常终态时返回 502。
            response.set("{\"accountId\":\"acct_1\",\"model\":\"test-model\",\"text\":\"408\",\"completed\":false,\"controlVersion\":\"12\"}");
            assertThatThrownBy(()->adapter.ask("acct_1","test-model","question")).isInstanceOf(AdapterException.class);
            status.set(502);
            assertThatThrownBy(()->adapter.ask("acct_1","test-model","question")).hasMessageContaining("HTTP 502");
            status.set(200);
            for(String field:java.util.List.of("accountId","model","completed","text","controlVersion")) {
                var broken=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree("{\"accountId\":\"acct_1\",\"model\":\"test-model\",\"text\":\"408\",\"completed\":true,\"controlVersion\":\"12\"}");
                broken.remove(field);response.set(broken.toString());
                assertThatThrownBy(()->adapter.ask("acct_1","test-model","question")).isInstanceOf(AdapterException.class);
            }
            String operation=java.util.UUID.randomUUID().toString();
            response.set("{\"accountId\":\"acct_1\",\"operationId\":\""+operation+"\",\"state\":\"isolated\",\"enabled\":false,\"controlVersion\":\"13\"}");
            adapter.isolate("acct_1",operation,"12");
            assertThat(bodies.get(bodies.size()-1).path("expectedControlVersion").asText()).isEqualTo("12");
            response.set("{\"accountId\":\"acct_1\",\"operationId\":\""+operation+"\",\"state\":\"restored\",\"enabled\":true,\"controlVersion\":\"14\"}");
            adapter.restoreIsolation("acct_1",operation);
            String validReceipt=response.get();
            for(String field:java.util.List.of("enabled","controlVersion")) {
                var broken=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(validReceipt);
                broken.remove(field);response.set(broken.toString());
                assertThatThrownBy(()->adapter.isolationState("acct_1",operation)).isInstanceOf(AdapterException.class);
            }
            var contradictory=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(validReceipt);
            contradictory.put("enabled",false);response.set(contradictory.toString());
            assertThatThrownBy(()->adapter.restoreIsolation("acct_1",operation)).hasMessageContaining("矛盾");
            status.set(409);
            assertThatThrownBy(()->adapter.restoreIsolation("acct_1",operation)).isInstanceOf(ControlConflictException.class);
            status.set(404);
            assertThatThrownBy(()->adapter.ask("acct_1","test-model","question")).hasMessageContaining("HTTP 404");
        } finally { server.stop(0); }
    }
    @Test void unavailableAdaptersNeverPerformNetworkCalls() {
        var adapter=new Sub2ApiAdapter(new ObjectMapper(),"http://127.0.0.1:1","",true,"test-model");
        assertThat(adapter.enabled()).isFalse();
        assertThatThrownBy(adapter::listAccounts).hasMessageContaining("API Key");
        assertThatThrownBy(()->adapter.ask("1","test-model","question")).hasMessageContaining("API Key");
    }
}
