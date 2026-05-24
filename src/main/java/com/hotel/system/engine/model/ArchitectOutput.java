package com.hotel.system.engine.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class ArchitectOutput {
    public final String design;
    public final String mermaid;
    public final List<String> decisionLog;
    public final JsonNode raw;

    public ArchitectOutput(String design, String mermaid, List<String> decisionLog, JsonNode raw) {
        this.design = design;
        this.mermaid = mermaid;
        this.decisionLog = decisionLog;
        this.raw = raw;
    }
}
