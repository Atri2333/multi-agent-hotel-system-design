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

    public IterationResult(int iteration, String goal, String design, String mermaid, List<String> issues, List<String> decisionLog, int revisionsUsed, String ts) {
        this.iteration = iteration;
        this.goal = goal;
        this.design = design;
        this.mermaid = mermaid;
        this.issues = issues;
        this.decisionLog = decisionLog;
        this.revisionsUsed = revisionsUsed;
        this.ts = ts;
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
}
