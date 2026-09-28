package com.poolguard.adapter;

import java.util.List;

public interface ProjectAdapter {
    String projectCode();
    boolean enabled();
    String capability();
    List<ExternalAccount> listAccounts();
    Answer ask(String externalId, String model, String question);
    void setSchedulable(String externalId, boolean enabled);
    record ExternalAccount(String id, String email, String model, boolean schedulable) {}
    record Answer(String text, int latencyMs) {}
}
