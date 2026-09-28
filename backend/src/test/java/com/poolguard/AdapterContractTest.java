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
            int before=requests.get();
            assertThatThrownBy(()->adapter.ask("acct_1","test-model","question")).isInstanceOf(AdapterException.class).hasMessageContaining("固定题目");
            assertThat(requests.get()).isEqualTo(before);
        } finally { server.stop(0); }
    }
    @Test void unavailableAdaptersNeverPerformNetworkCalls() {
        var adapter=new Sub2ApiAdapter(new ObjectMapper(),"http://127.0.0.1:1","",true,"test-model");
        assertThat(adapter.enabled()).isFalse();
        assertThatThrownBy(adapter::listAccounts).hasMessageContaining("API Key");
        assertThatThrownBy(()->adapter.ask("1","test-model","question")).hasMessageContaining("API Key");
    }
}
