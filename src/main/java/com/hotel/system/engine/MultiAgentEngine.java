package com.hotel.system.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hotel.system.config.AppConfig;
import com.hotel.system.engine.model.ArchitectOutput;
import com.hotel.system.engine.model.CriticOutput;
import com.hotel.system.io.ConsoleIO;
import com.hotel.system.llm.LlmClient;
import com.hotel.system.log.MarkdownLogWriter;
import com.hotel.system.state.IterationResult;
import com.hotel.system.state.OverallState;
import com.hotel.system.state.Turn;
import com.hotel.system.util.TimeUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MultiAgentEngine {
    private final AppConfig cfg;
    private final ObjectMapper mapper;
    private final LlmClient llm;
    private final ConsoleIO io;
    private final MarkdownLogWriter writer;

    public MultiAgentEngine(AppConfig cfg, ObjectMapper mapper, LlmClient llm, ConsoleIO io, MarkdownLogWriter writer) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.llm = llm;
        this.io = io;
        this.writer = writer;
    }

    public void run(OverallState state) throws Exception {
        for (int i = 1; i <= cfg.iterations; i++) {
            state.setIteration(i);

            while (true) {
                orchestrator(state);
                if (humanCheckpoint(state, "Human Checkpoint #1 (before iteration)")) break;
            }

            var architectOut = architect(state, null, 0);
            var criticOut = critic(state, architectOut, 0);

            int revisions = 0;
            while (!criticOut.pass && revisions < cfg.maxRevisions) {
                revisions++;
                architectOut = architect(state, criticOut, revisions);
                criticOut = critic(state, architectOut, revisions);
            }

            scribe(state, architectOut, criticOut, revisions);
            contextCompactor(state);

            while (true) {
                if (humanCheckpoint(state, "Human Checkpoint (after iteration)")) break;
                writer.appendConversationNote("Checkpoint retry: re-using the same iteration artifacts; no content edits allowed.");
            }
        }
    }

    private void orchestrator(OverallState state) throws Exception {
        String system = """
                You are Orchestrator. Decide the next iteration goal for an ADD-style hotel system design.
                Output JSON with fields:
                - iteration_goal (string)
                - routing (string)
                - decision_log (array of strings)
                """.trim();

        String suggested = defaultIterationGoal(state.getIteration());
        String user = """
                Context:
                - prior_knowledge: %s
                - compacted_history: %s
                Task:
                - iteration: %d
                - propose iteration goal (one focus only)
                Guidance:
                - prefer: %s
                """.formatted(state.getPriorKnowledge(), safe(state.getCompactedHistory()), state.getIteration(), suggested).trim();

        JsonNode out = llm.generateJson(system, user);
        String goal = textOrFallback(out, "iteration_goal", suggested);

        state.setIterationGoal(goal);
        recordTurn(state, "Orchestrator", user, out);
        writer.appendConversationTurn(lastTurn(state));
    }

    private boolean humanCheckpoint(OverallState state, String title) throws IOException {
        String prompt = """
                %s
                Iteration %d goal:
                %s

                Enter approve or retry:
                """.formatted(title, state.getIteration(), state.getIterationGoal());

        String v = io.readKeyword(prompt, "approve", "retry");
        recordTurn(state, "HumanCheckpoint", prompt, mapper.createObjectNode().put("human_input", v));
        writer.appendConversationTurn(lastTurn(state));
        return "approve".equalsIgnoreCase(v);
    }

    private ArchitectOutput architect(OverallState state, CriticOutput critic, int revision) throws Exception {
        String system = """
                You are Architect. Produce ADD Step 2-5 style design only.
                Output JSON with fields:
                - design (string)
                - diagram_code (string, Mermaid only)
                - decision_log (array of strings)
                """.trim();

        String criticBlock = critic == null ? "" : """
                Critic feedback to address (revision %d):
                %s
                """.formatted(revision, String.join("\n", critic.issues)).trim();

        String user = """
                Context:
                - prior_knowledge: %s
                - compacted_history: %s
                Iteration:
                - iteration: %d
                - goal: %s
                Constraints:
                - do not include QA checks; only design
                - keep to one main focus
                %s
                """.formatted(state.getPriorKnowledge(), safe(state.getCompactedHistory()), state.getIteration(), state.getIterationGoal(), criticBlock).trim();

        JsonNode out = llm.generateJson(system, user);

        String design = textOrFallback(out, "design", "TODO: design placeholder (no LLM configured).");
        String mermaid = textOrFallback(out, "diagram_code", defaultMermaid(state.getIteration()));
        List<String> decisionLog = arrayOfStrings(out.get("decision_log"));

        recordTurn(state, "Architect", user, out);
        writer.appendConversationTurn(lastTurn(state));

        return new ArchitectOutput(design, mermaid, decisionLog, out);
    }

    private CriticOutput critic(OverallState state, ArchitectOutput architect, int revision) throws Exception {
        String system = """
                You are Critic. Verify QA/constraints coverage. Request revision if needed.
                Output JSON with fields:
                - pass (boolean)
                - issues (array of strings)
                - decision_log (array of strings)
                """.trim();

        String user = """
                Context:
                - iteration: %d
                - goal: %s
                Review target:
                - design: %s
                - diagram_code: %s
                Checks:
                - QA coverage (requirements, constraints, interfaces)
                - ADD consistency
                - avoid missing key components for a hotel system
                Revision control:
                - if issues exist, set pass=false and list actionable issues
                """.formatted(state.getIteration(), state.getIterationGoal(), architect.design, architect.mermaid).trim();

        JsonNode out = llm.generateJson(system, user);

        boolean pass = boolOrFallback(out, "pass", true);
        List<String> issues = arrayOfStrings(out.get("issues"));
        List<String> decisionLog = arrayOfStrings(out.get("decision_log"));

        if (issues.isEmpty()) pass = true;

        recordTurn(state, "Critic", user, out);
        writer.appendConversationTurn(lastTurn(state));

        return new CriticOutput(pass, issues, decisionLog, out);
    }

    private void scribe(OverallState state, ArchitectOutput architect, CriticOutput critic, int revisionsUsed) throws IOException {
        String ts = TimeUtil.nowIso();
        List<String> mergedDecisions = new ArrayList<>();
        mergedDecisions.addAll(architect.decisionLog);
        mergedDecisions.addAll(critic.decisionLog);

        var result = new IterationResult(
                state.getIteration(),
                state.getIterationGoal(),
                architect.design,
                normalizeMermaid(architect.mermaid),
                critic.issues,
                mergedDecisions,
                revisionsUsed,
                ts
        );

        state.getResults().add(result);

        ObjectNode out = mapper.createObjectNode();
        out.put("iteration", state.getIteration());
        out.put("ts", ts);
        out.put("revisions_used", revisionsUsed);
        out.put("finalized", true);
        out.put("issues_count", critic.issues.size());

        recordTurn(state, "Scribe", "Finalize iteration result and write logs.", out);
        writer.appendConversationTurn(lastTurn(state));

        writer.appendArchitectureIteration(result);
    }

    private void contextCompactor(OverallState state) throws Exception {
        String system = """
                You are Context Compactor. Summarize prior iterations into compact context.
                Output JSON with fields:
                - compacted_history (string)
                """.trim();

        String user = """
                Summarize the following iteration results into <= 1200 chars.
                Keep: key components, interfaces, constraints, unresolved issues.
                Results:
                %s
                """.formatted(renderResultsForCompaction(state.getResults())).trim();

        JsonNode out = llm.generateJson(system, user);
        String compacted = textOrFallback(out, "compacted_history", simpleCompact(state.getResults()));

        state.setCompactedHistory(compacted);

        recordTurn(state, "ContextCompactor", user, out);
        writer.appendConversationTurn(lastTurn(state));
    }

    private String renderResultsForCompaction(List<IterationResult> results) {
        StringBuilder sb = new StringBuilder();
        for (IterationResult r : results) {
            sb.append("Iteration ").append(r.getIteration()).append(": ").append(r.getGoal()).append("\n");
            sb.append("Design: ").append(trimTo(r.getDesign(), 500)).append("\n");
            if (!r.getIssues().isEmpty()) sb.append("Issues: ").append(String.join("; ", r.getIssues())).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private String simpleCompact(List<IterationResult> results) {
        StringBuilder sb = new StringBuilder();
        for (IterationResult r : results) {
            sb.append("I").append(r.getIteration()).append(": ").append(trimTo(r.getGoal(), 120)).append(" | ");
        }
        String s = sb.toString().trim();
        return trimTo(s, 900);
    }

    private void recordTurn(OverallState state, String node, String input, JsonNode output) throws JsonProcessingException {
        String ts = TimeUtil.nowIso();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        state.getConversation().add(new Turn(ts, node, input, json));
    }

    private Turn lastTurn(OverallState state) {
        var list = state.getConversation();
        return list.get(list.size() - 1);
    }

    private String defaultIterationGoal(int iteration) {
        return switch (iteration) {
            case 1 -> "Define scope, stakeholders, quality attributes, and key scenarios for a hotel system (ADD inputs).";
            case 2 -> "Design core domain components and their responsibilities (booking, inventory, pricing, customer, payment).";
            case 3 -> "Design service interfaces/APIs, integration boundaries, and interaction flows for key scenarios.";
            case 4 -> "Design deployment view, data/storage choices, observability, and evolution strategy.";
            default -> "Continue refining the architecture using ADD.";
        };
    }

    private String defaultMermaid(int iteration) {
        String title = "Iteration " + iteration + " - High-level components";
        return """
                flowchart TB
                  subgraph %s
                    UI[Web/App UI]
                    API[API Gateway]
                    RES[Reservation Service]
                    INV[Inventory Service]
                    PAY[Payment Service]
                    CUS[Customer Service]
                    DB[(Database)]
                    MQ[(Message Bus)]
                    UI --> API
                    API --> RES
                    API --> INV
                    API --> CUS
                    RES --> DB
                    INV --> DB
                    RES --> MQ
                    PAY --> MQ
                  end
                """.formatted(title).trim();
    }

    private String normalizeMermaid(String diagramCode) {
        String s = diagramCode.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("(?s)^```\\w*\\s*", "");
            s = s.replaceFirst("(?s)```\\s*$", "");
        }
        return s.trim();
    }

    private String textOrFallback(JsonNode n, String field, String fallback) {
        if (n == null) return fallback;
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return fallback;
        String s = v.asText();
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private boolean boolOrFallback(JsonNode n, String field, boolean fallback) {
        if (n == null) return fallback;
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return fallback;
        return v.asBoolean(fallback);
    }

    private List<String> arrayOfStrings(JsonNode n) {
        if (n == null || n.isNull()) return List.of();
        if (!n.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode x : n) {
            if (x != null && !x.isNull()) {
                String s = x.asText();
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.trim();
    }

    private String trimTo(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }
}
