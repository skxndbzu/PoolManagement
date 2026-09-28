package com.poolguard.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenService {
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final boolean demo;
    private final Map<String,Session> local=new ConcurrentHashMap<>();
    private record Session(String user, Instant expires) {}
    public TokenService(StringRedisTemplate redis,@Value("${poolguard.auth.token-ttl:PT8H}") Duration ttl,
                        @Value("${poolguard.demo:false}") boolean demo) {
        this.redis=redis;this.ttl=ttl;this.demo=demo;
    }
    public String issue(String username) {
        String token=UUID.randomUUID().toString();
        if (demo) {
            local.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));
            local.put(token,new Session(username,Instant.now().plus(ttl)));
        } else redis.opsForValue().set("poolguard:session:"+token,username,ttl);
        return token;
    }
    public Optional<String> findUser(String token) {
        if(token==null||token.isBlank())return Optional.empty();
        if(!demo)return Optional.ofNullable(redis.opsForValue().get("poolguard:session:"+token));
        var session=local.get(token);
        return session!=null&&session.expires().isAfter(Instant.now())?Optional.of(session.user()):Optional.empty();
    }
    public void revoke(String token) { if(demo)local.remove(token);else redis.delete("poolguard:session:"+token); }
    public long expiresInSeconds(){return ttl.toSeconds();}
}
