package com.hotel.system.state;

public final class Turn {
    private final String ts;
    private final String node;
    private final String input;
    private final String outputJson;

    public Turn(String ts, String node, String input, String outputJson) {
        this.ts = ts;
        this.node = node;
        this.input = input;
        this.outputJson = outputJson;
    }

    public String getTs() {
        return ts;
    }

    public String getNode() {
        return node;
    }

    public String getInput() {
        return input;
    }

    public String getOutputJson() {
        return outputJson;
    }
}
