package com.poolguard.adapter;

import java.util.List;

public interface ProjectAdapter {
    String projectCode();
    boolean enabled();
    String capability();
    List<ExternalAccount> listAccounts();
    Answer ask(String externalId, String model, String question);
    void setSchedulable(String externalId, boolean enabled);
    default boolean supportsIsolationReceipts() { return false; }
    default IsolationState isolationState(String id, String operationId) {
        throw new AdapterException("项目不支持隔离回执");
    }
    default void isolate(String id, String operationId, String controlVersion) { setSchedulable(id, false); }
    default void restoreIsolation(String id, String operationId) { setSchedulable(id, true); }
    enum IsolationState { NONE, ISOLATED, RESTORED, CONFLICT }
    record ExternalAccount(String id, String email, String model, boolean schedulable) {}
    record Answer(String text, int latencyMs, String controlVersion) {
        public Answer(String text, int latencyMs) { this(text, latencyMs, null); }
    }
}
