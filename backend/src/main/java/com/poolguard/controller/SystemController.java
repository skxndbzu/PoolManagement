package com.poolguard.controller;

import com.poolguard.adapter.ProjectAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final ProjectAdapterRegistry adapters;
    private final boolean demo;
    public SystemController(ProjectAdapterRegistry adapters,@Value("${poolguard.demo:false}") boolean demo) { this.adapters=adapters;this.demo=demo; }
    @GetMapping public Map<String,Object> info() {
        return Map.of("mode",demo?"DEMO":"LIVE","projects",adapters.all().stream().map(a->
            Map.of("code",a.projectCode(),"enabled",a.enabled(),"capability",a.capability())).toList());
    }
}
