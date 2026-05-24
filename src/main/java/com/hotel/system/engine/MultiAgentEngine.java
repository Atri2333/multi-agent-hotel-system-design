package com.hotel.system.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateFactory;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hotel.system.config.AppConfig;
import com.hotel.system.io.ConsoleIO;
import com.hotel.system.log.MarkdownLogWriter;
import com.hotel.system.state.IterationResult;
import com.hotel.system.state.Turn;
import com.hotel.system.util.TimeUtil;
import org.springframework.ai.chat.client.ChatClient;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

public final class MultiAgentEngine {
    private final AppConfig cfg;
    private final ObjectMapper mapper;
    private final ChatClient chatClient;
    private final ConsoleIO io;
    private final MarkdownLogWriter writer;

    public MultiAgentEngine(AppConfig cfg, ObjectMapper mapper, ChatClient chatClient, ConsoleIO io, MarkdownLogWriter writer) {
        this.cfg = cfg;
        this.mapper = mapper;
        this.chatClient = chatClient;
        this.io = io;
        this.writer = writer;
    }

    public void run() throws Exception {
        Files.createDirectories(cfg.outputDir);
        writer.initFiles();

        String priorKnowledge = defaultPriorKnowledge();
        writer.appendConversationHeader(priorKnowledge);
        writer.appendArchitectureHeader();

        CompiledGraph graph = buildGraph(priorKnowledge).compile();
        graph.setMaxIterations(recommendedMaxIterations());
        RunnableConfig config = RunnableConfig.builder().threadId("multi-agent-hotel-design").build();
        graph.invoke(Map.of(), config);
    }

    private StateGraph buildGraph(String priorKnowledge) throws GraphStateException {
        OverAllStateFactory stateFactory = () -> {
            OverAllState state = new OverAllState();
            state.registerKeyAndStrategy("prior_knowledge", new ReplaceStrategy());
            state.registerKeyAndStrategy("compacted_history", new ReplaceStrategy());
            state.registerKeyAndStrategy("iteration", new ReplaceStrategy());
            state.registerKeyAndStrategy("iteration_goal", new ReplaceStrategy());
            state.registerKeyAndStrategy("revision_count", new ReplaceStrategy());
            state.registerKeyAndStrategy("architect_design", new ReplaceStrategy());
            state.registerKeyAndStrategy("architect_mermaid", new ReplaceStrategy());
            state.registerKeyAndStrategy("architect_decision_log", new ReplaceStrategy());
            state.registerKeyAndStrategy("critic_pass", new ReplaceStrategy());
            state.registerKeyAndStrategy("critic_issues", new ReplaceStrategy());
            state.registerKeyAndStrategy("critic_decision_log", new ReplaceStrategy());
            state.registerKeyAndStrategy("human_before", new ReplaceStrategy());
            state.registerKeyAndStrategy("human_after", new ReplaceStrategy());
            state.registerKeyAndStrategy("conversation", new AppendStrategy());
            state.registerKeyAndStrategy("results", new AppendStrategy());
            state.registerKeyAndStrategy("compactor_output", new ReplaceStrategy());

            return state.input(Map.of(
                    "prior_knowledge", priorKnowledge,
                    "compacted_history", "",
                    "iteration", 1,
                    "iteration_goal", "",
                    "revision_count", 0,
                    "critic_pass", true,
                    "critic_issues", List.of(),
                    "critic_decision_log", List.of(),
                    "architect_decision_log", List.of()
            ));
        };

        var orchestrator = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            String compactedHistory = stringValue(state, "compacted_history", "");

            String system = """
                    You are Orchestrator. Decide the next iteration goal for an ADD-style hotel system design.
                    Output JSON with fields:
                    - iteration_goal (string)
                    - routing (string)
                    - decision_log (array of strings)
                    """.trim();

            String suggested = defaultIterationGoal(iteration);
            String user = """
                    Context:
                    - prior_knowledge: %s
                    - compacted_history: %s
                    Task:
                    - iteration: %d
                    - propose iteration goal (one focus only)
                    Guidance:
                    - prefer: %s
                    """.formatted(priorKnowledge, safe(compactedHistory), iteration, suggested).trim();

            JsonNode out = callJson(system, user);
            String goal = textOrFallback(out, "iteration_goal", suggested);

            Turn turn = toTurn("Orchestrator", user, out);
            writer.appendConversationTurn(turn);
            return Map.of(
                    "iteration_goal", goal,
                    "human_before", "",
                    "revision_count", 0,
                    "conversation", turn
            );
        });

        var humanBefore = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            String goal = stringValue(state, "iteration_goal", "");
            String prompt = """
                    Human Checkpoint #1 (before iteration)
                    Iteration %d goal:
                    %s

                    Enter approve or retry:
                    """.formatted(iteration, goal);
            String v = io.readKeyword(prompt, "approve", "retry");
            ObjectNode out = mapper.createObjectNode().put("human_input", v);
            Turn turn = toTurn("HumanCheckpointBefore", prompt, out);
            writer.appendConversationTurn(turn);
            return Map.of(
                    "human_before", v,
                    "conversation", turn,
                    "revision_count", 0
            );
        });

        var architect = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            int revision = intValue(state, "revision_count", 0);
            String goal = stringValue(state, "iteration_goal", "");
            String compactedHistory = stringValue(state, "compacted_history", "");

            List<String> criticIssues = listOfStrings(state.value("critic_issues").orElse(List.of()));
            String criticBlock = criticIssues.isEmpty() ? "" : """
                    Critic feedback to address (revision %d):
                    %s
                    """.formatted(revision, String.join("\n", criticIssues)).trim();

            String system = """
                    You are Architect. Produce ADD Step 2-5 style design only.
                    Output JSON with fields:
                    - design (string)
                    - diagram_code (string, Mermaid only)
                    - decision_log (array of strings)
                    """.trim();

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
                    """.formatted(priorKnowledge, safe(compactedHistory), iteration, goal, criticBlock).trim();

            JsonNode out = callJson(system, user);
            String design = textOrFallback(out, "design", "TODO: design placeholder (no LLM configured).");
            String mermaid = textOrFallback(out, "diagram_code", defaultMermaid(iteration));
            List<String> decisionLog = arrayOfStrings(out.get("decision_log"));

            Turn turn = toTurn("Architect", user, out);
            writer.appendConversationTurn(turn);
            return Map.of(
                    "architect_design", design,
                    "architect_mermaid", mermaid,
                    "architect_decision_log", decisionLog,
                    "conversation", turn
            );
        });

        var critic = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            int revision = intValue(state, "revision_count", 0);
            String goal = stringValue(state, "iteration_goal", "");
            String design = stringValue(state, "architect_design", "");
            String mermaid = stringValue(state, "architect_mermaid", "");

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
                    """.formatted(iteration, goal, design, mermaid).trim();

            JsonNode out = callJson(system, user);

            boolean pass = boolOrFallback(out, "pass", true);
            List<String> issues = arrayOfStrings(out.get("issues"));
            List<String> decisionLog = arrayOfStrings(out.get("decision_log"));
            if (issues.isEmpty()) pass = true;

            int nextRevision = revision;
            if (!pass && revision < cfg.maxRevisions) nextRevision = revision + 1;

            Turn turn = toTurn("Critic", user, out);
            writer.appendConversationTurn(turn);
            return Map.of(
                    "critic_pass", pass,
                    "critic_issues", issues,
                    "critic_decision_log", decisionLog,
                    "revision_count", nextRevision,
                    "conversation", turn
            );
        });

        var scribe = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            int revisionsUsed = intValue(state, "revision_count", 0);
            String goal = stringValue(state, "iteration_goal", "");

            String design = stringValue(state, "architect_design", "");
            String mermaid = stringValue(state, "architect_mermaid", "");
            List<String> criticIssues = listOfStrings(state.value("critic_issues").orElse(List.of()));

            List<String> mergedDecisions = new ArrayList<>();
            mergedDecisions.addAll(listOfStrings(state.value("architect_decision_log").orElse(List.of())));
            mergedDecisions.addAll(listOfStrings(state.value("critic_decision_log").orElse(List.of())));

            String ts = TimeUtil.nowIso();
            IterationResult result = new IterationResult(
                    iteration,
                    goal,
                    design,
                    normalizeMermaid(mermaid),
                    criticIssues,
                    mergedDecisions,
                    revisionsUsed,
                    ts
            );

            writer.appendArchitectureIteration(result);

            ObjectNode out = mapper.createObjectNode();
            out.put("iteration", iteration);
            out.put("ts", ts);
            out.put("revisions_used", revisionsUsed);
            out.put("finalized", true);
            out.put("issues_count", criticIssues.size());

            Turn turn = toTurn("Scribe", "Finalize iteration result and write logs.", out);
            writer.appendConversationTurn(turn);
            return Map.of(
                    "results", result,
                    "conversation", turn
            );
        });

        var compactor = node_async(state -> {
            String system = """
                    You are Context Compactor. Summarize prior iterations into compact context.
                    Output JSON with fields:
                    - compacted_history (string)
                    """.trim();

            @SuppressWarnings("unchecked")
            List<IterationResult> results = (List<IterationResult>) state.value("results").orElse(List.of());

            String user = """
                    Summarize the following iteration results into <= 1200 chars.
                    Keep: key components, interfaces, constraints, unresolved issues.
                    Results:
                    %s
                    """.formatted(renderResultsForCompaction(results)).trim();

            JsonNode out = callJson(system, user);
            String compacted = textOrFallback(out, "compacted_history", simpleCompact(results));

            Turn turn = toTurn("ContextCompactor", user, out);
            writer.appendConversationTurn(turn);
            return Map.of(
                    "compacted_history", compacted,
                    "compactor_output", compacted,
                    "conversation", turn
            );
        });

        var humanAfter = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            String goal = stringValue(state, "iteration_goal", "");
            String prompt = """
                    Human Checkpoint (after iteration)
                    Iteration %d goal:
                    %s

                    Enter approve or retry:
                    """.formatted(iteration, goal);
            String v = io.readKeyword(prompt, "approve", "retry");
            ObjectNode out = mapper.createObjectNode().put("human_input", v);
            Turn turn = toTurn("HumanCheckpointAfter", prompt, out);
            writer.appendConversationTurn(turn);
            if ("retry".equalsIgnoreCase(v)) {
                writer.appendConversationNote("Checkpoint retry: re-using the same iteration artifacts; no content edits allowed.");
            }
            return Map.of(
                    "human_after", v,
                    "conversation", turn
            );
        });

        var nextIteration = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            int next = iteration + 1;
            return Map.of(
                    "iteration", next,
                    "iteration_goal", "",
                    "revision_count", 0,
                    "critic_pass", true,
                    "critic_issues", List.of(),
                    "critic_decision_log", List.of(),
                    "architect_decision_log", List.of(),
                    "human_before", "",
                    "human_after", ""
            );
        });

        var checkpointBeforeDecision = edge_async(state -> {
            String v = stringValue(state, "human_before", "retry");
            return "approve".equalsIgnoreCase(v) ? "approve" : "retry";
        });

        var criticDecision = edge_async(state -> {
            boolean pass = boolValue(state, "critic_pass", true);
            int revision = intValue(state, "revision_count", 0);
            if (pass) return "pass";
            if (revision >= cfg.maxRevisions) return "maxed";
            return "revise";
        });

        var checkpointAfterDecision = edge_async(state -> {
            String v = stringValue(state, "human_after", "retry");
            return "approve".equalsIgnoreCase(v) ? "approve" : "retry";
        });

        var continueDecision = edge_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            return iteration <= cfg.iterations ? "continue" : "end";
        });

        return new StateGraph("Multi-Agent Hotel System Design", stateFactory)
                .addNode("orchestrator", orchestrator)
                .addNode("human_checkpoint_before", humanBefore)
                .addNode("architect", architect)
                .addNode("critic", critic)
                .addNode("scribe", scribe)
                .addNode("context_compactor", compactor)
                .addNode("human_checkpoint_after", humanAfter)
                .addNode("next_iteration", nextIteration)
                .addEdge(START, "orchestrator")
                .addEdge("orchestrator", "human_checkpoint_before")
                .addConditionalEdges("human_checkpoint_before", checkpointBeforeDecision, Map.of(
                        "approve", "architect",
                        "retry", "orchestrator"
                ))
                .addEdge("architect", "critic")
                .addConditionalEdges("critic", criticDecision, Map.of(
                        "pass", "scribe",
                        "maxed", "scribe",
                        "revise", "architect"
                ))
                .addEdge("scribe", "context_compactor")
                .addEdge("context_compactor", "human_checkpoint_after")
                .addConditionalEdges("human_checkpoint_after", checkpointAfterDecision, Map.of(
                        "retry", "human_checkpoint_after",
                        "approve", "next_iteration"
                ))
                .addConditionalEdges("next_iteration", continueDecision, Map.of(
                        "continue", "orchestrator",
                        "end", END
                ));
    }

    private JsonNode callJson(String system, String user) {
        String content = chatClient.prompt().system(system).user(user).call().content();
        if (content == null) content = "";
        String stripped = stripCodeFences(content.trim());
        try {
            JsonNode n = mapper.readTree(stripped);
            if (n != null && n.isObject()) return n;
            ObjectNode wrapped = mapper.createObjectNode();
            wrapped.set("value", n);
            return wrapped;
        } catch (Exception e) {
            ObjectNode wrapped = mapper.createObjectNode();
            wrapped.put("raw_text", content);
            return wrapped;
        }
    }

    private Turn toTurn(String node, String input, JsonNode output) throws IOException {
        String ts = TimeUtil.nowIso();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        return new Turn(ts, node, input, json);
    }

    private String stripCodeFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("(?s)^```\\w*\\s*", "");
            t = t.replaceFirst("(?s)```\\s*$", "");
        }
        return t.trim();
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

    private List<String> listOfStrings(Object maybeList) {
        if (maybeList == null) return List.of();
        if (maybeList instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null) continue;
                String s = o.toString();
                if (!s.isBlank()) out.add(s.trim());
            }
            return out;
        }
        return List.of(maybeList.toString());
    }

    private boolean boolValue(OverAllState state, String key, boolean d) {
        return state.value(key).map(v -> (Boolean) v).orElse(d);
    }

    private int intValue(OverAllState state, String key, int d) {
        return state.value(key).map(v -> ((Number) v).intValue()).orElse(d);
    }

    private String stringValue(OverAllState state, String key, String d) {
        return state.value(key).map(Object::toString).orElse(d);
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

    private int recommendedMaxIterations() {
        int perIterationBase = 8;
        int perIterationRevisionWorst = 4 * Math.max(0, cfg.maxRevisions);
        int perIterationBudget = perIterationBase + perIterationRevisionWorst;
        int planned = Math.max(1, cfg.iterations) * perIterationBudget;
        return Math.max(100, planned + 20);
    }
}
