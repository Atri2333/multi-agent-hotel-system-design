package com.hotel.system.state;

import java.util.List;

public final class IterationResult {
    private final int iteration;
    private final String goal;
    private final String design;
    private final String mermaid;
    private final List<String> issues;
    private final List<String> decisionLog;
    private final int revisionsUsed;
    private final String ts;
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;
    private final int humanTurns;
    private final int agentTurns;
    private final long durationMs;

    public IterationResult(int iteration, String goal, String design, String mermaid, List<String> issues, List<String> decisionLog, int revisionsUsed, String ts, int promptTokens, int completionTokens, int totalTokens, int humanTurns, int agentTurns, long durationMs) {
        this.iteration = iteration;
        this.goal = goal;
        this.design = design;
        this.mermaid = mermaid;
        this.issues = issues;
        this.decisionLog = decisionLog;
        this.revisionsUsed = revisionsUsed;
        this.ts = ts;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.humanTurns = humanTurns;
        this.agentTurns = agentTurns;
        this.durationMs = durationMs;
    }

    public int getIteration() {
        return iteration;
    }

    public String getGoal() {
        return goal;
    }

    public String getDesign() {
        return design;
    }

    public String getMermaid() {
        return mermaid;
    }

    public List<String> getIssues() {
        return issues;
    }

    public List<String> getDecisionLog() {
        return decisionLog;
    }

    public int getRevisionsUsed() {
        return revisionsUsed;
    }

    public String getTs() {
        return ts;
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

    public int getHumanTurns() {
        return humanTurns;
    }

    public int getAgentTurns() {
        return agentTurns;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
