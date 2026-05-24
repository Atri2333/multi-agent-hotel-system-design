package com.hotel.system.state;

import com.hotel.system.config.AppConfig;
import com.hotel.system.log.MarkdownLogWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class OverallState {
    private final AppConfig config;
    private final MarkdownLogWriter writer;

    private String priorKnowledge;
    private String compactedHistory;

    private int iteration;
    private String iterationGoal;

    private final List<Turn> conversation = new ArrayList<>();
    private final List<IterationResult> results = new ArrayList<>();

    private OverallState(AppConfig config, MarkdownLogWriter writer) {
        this.config = config;
        this.writer = writer;
    }

    public static OverallState bootstrap(AppConfig cfg, MarkdownLogWriter writer) throws IOException {
        var s = new OverallState(cfg, writer);
        s.priorKnowledge = defaultPriorKnowledge();
        s.compactedHistory = "";
        Files.createDirectories(cfg.outputDir);
        writer.initFiles();
        writer.appendConversationHeader(s.priorKnowledge);
        writer.appendArchitectureHeader();
        return s;
    }

    public AppConfig getConfig() {
        return config;
    }

    public MarkdownLogWriter getWriter() {
        return writer;
    }

    public String getPriorKnowledge() {
        return priorKnowledge;
    }

    public String getCompactedHistory() {
        return compactedHistory;
    }

    public void setCompactedHistory(String compactedHistory) {
        this.compactedHistory = compactedHistory;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int iteration) {
        this.iteration = iteration;
    }

    public String getIterationGoal() {
        return iterationGoal;
    }

    public void setIterationGoal(String iterationGoal) {
        this.iterationGoal = iterationGoal;
    }

    public List<Turn> getConversation() {
        return conversation;
    }

    public List<IterationResult> getResults() {
        return results;
    }

    public static String defaultPriorKnowledge() {
        return """
                Project: Multi-Agent Hotel System Design (ADD-style)
                Agents:
                - Orchestrator: plan iteration goals, route nodes
                - Architect: produce design + Mermaid
                - Critic: QA/constraints check, request revision (max 2)
                - Scribe: consolidate final, log decisions and rationale
                - Context Compactor: summarize history to avoid context overflow

                Rules:
                - Human checkpoint input only: approve / retry
                - Structured JSON output from agents: design, diagram_code, issues, decision_log fields (as applicable)
                - Run 4 iterations, sequential, with critic-architect revision loop up to 2
                """.trim();
    }
}
