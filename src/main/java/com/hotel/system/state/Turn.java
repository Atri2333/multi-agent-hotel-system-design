package com.hotel.system.state;

public final class Turn {
    private final String ts;
    private final String node;
    private final String input;
    private final String outputJson;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    public Turn(String ts, String node, String input, String outputJson, int promptTokens, int completionTokens, int totalTokens) {
        this.ts = ts;
        this.node = node;
        this.input = input;
        this.outputJson = outputJson;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
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

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }
}
