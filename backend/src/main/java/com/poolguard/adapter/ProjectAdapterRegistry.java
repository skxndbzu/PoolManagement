package com.poolguard.adapter;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProjectAdapterRegistry {
    private final List<ProjectAdapter> adapters;

    public ProjectAdapterRegistry(List<ProjectAdapter> adapters) {
        this.adapters = adapters;
    }

    public ProjectAdapter forProject(String projectCode) {
        return adapters.stream()
            .filter(adapter -> adapter.projectCode().equalsIgnoreCase(projectCode))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("未配置项目适配器: " + projectCode));
    }

    public List<ProjectAdapter> all() { return adapters; }

    public List<ProjectAdapter> enabledAdapters() {
        return adapters.stream().filter(ProjectAdapter::enabled).toList();
    }
}
