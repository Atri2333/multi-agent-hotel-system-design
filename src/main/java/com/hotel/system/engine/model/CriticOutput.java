package com.hotel.system.engine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class CriticOutput {
    public final boolean pass;
    public final List<String> issues;
    public final List<String> decisionLog;
    public final JsonNode raw;

    public CriticOutput(boolean pass, List<String> issues, List<String> decisionLog, JsonNode raw) {
        this.pass = pass;
        this.issues = issues;
        this.decisionLog = decisionLog;
        this.raw = raw;
    }
}
