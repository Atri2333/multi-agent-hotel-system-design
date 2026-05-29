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
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private final IterationStats stats = new IterationStats();

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

        PromptTemplates prompts = PromptTemplates.load();
        String priorKnowledge = prompts.priorKnowledge;
        writer.appendConversationHeader(priorKnowledge);
        writer.appendArchitectureHeader();

        CompiledGraph graph = buildGraph(prompts).compile();
        graph.setMaxIterations(recommendedMaxIterations());
        RunnableConfig config = RunnableConfig.builder().threadId("multi-agent-hotel-design").build();
        graph.invoke(Map.of(), config);
    }

    private StateGraph buildGraph(PromptTemplates prompts) throws GraphStateException {
        String priorKnowledge = prompts.priorKnowledge;
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

            String system = prompts.orchestratorSystem;

            String suggested = defaultIterationGoal(iteration);
            String user = prompts.render(prompts.orchestratorUser, Map.of(
                    "prior_knowledge", priorKnowledge,
                    "compacted_history", safe(compactedHistory),
                    "iteration", iteration,
                    "prefer", suggested
            ));

            CallResult call = callJson(system, user);
            JsonNode out = call.json();
            String goal = textOrFallback(out, "iteration_goal", suggested);

            Turn turn = toTurn("Orchestrator", user, out, call.usage());
            stats.addAgentTurn(iteration, turn);
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
            String prompt = prompts.render(prompts.humanCheckpointBefore, Map.of(
                    "iteration", iteration,
                    "goal", goal
            ));
            String v = io.readKeyword(prompt, "approve", "retry");
            ObjectNode out = mapper.createObjectNode().put("human_input", v);
            Turn turn = toTurn("HumanCheckpointBefore", prompt, out, emptyUsage());
            stats.addHumanTurn(iteration, turn);
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
            String criticBlock = criticIssues.isEmpty() ? "" : prompts.render(prompts.architectCriticBlock, Map.of(
                    "revision", revision,
                    "issues", String.join("\n", criticIssues)
            ));

            String system = prompts.architectSystem;

            String user = prompts.render(prompts.architectUser, Map.of(
                    "prior_knowledge", priorKnowledge,
                    "compacted_history", safe(compactedHistory),
                    "iteration", iteration,
                    "goal", goal,
                    "critic_block", criticBlock
            ));

            CallResult call = callJson(system, user);
            JsonNode out = call.json();
            String design = textOrFallback(out, "design", "TODO: design placeholder (no LLM configured).");
            String mermaid = textOrFallback(out, "diagram_code", defaultMermaid(iteration));
            List<String> decisionLog = arrayOfStrings(out.get("decision_log"));

            Turn turn = toTurn("Architect", user, out, call.usage());
            stats.addAgentTurn(iteration, turn);
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
            String compactedHistory = stringValue(state, "compacted_history", "");

            String system = prompts.criticSystem;

            String user = prompts.render(prompts.criticUser, Map.of(
                    "prior_knowledge", priorKnowledge,
                    "compacted_history", safe(compactedHistory),
                    "iteration", iteration,
                    "goal", goal,
                    "design", design,
                    "diagram_code", mermaid
            ));

            CallResult call = callJson(system, user);
            JsonNode out = call.json();

            boolean pass = boolOrFallback(out, "pass", true);
            List<String> issues = arrayOfStrings(out.get("issues"));
            List<String> decisionLog = arrayOfStrings(out.get("decision_log"));
            if (issues.isEmpty()) pass = true;

            int nextRevision = revision;
            if (!pass && revision < cfg.maxRevisions) nextRevision = revision + 1;

            Turn turn = toTurn("Critic", user, out, call.usage());
            stats.addAgentTurn(iteration, turn);
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
                    ts,
                    stats.getPromptTokensForIteration(iteration),
                    stats.getCompletionTokensForIteration(iteration),
                    stats.getTotalTokensForIteration(iteration),
                    stats.getHumanTurnsForIteration(iteration),
                    stats.getAgentTurnsForIteration(iteration),
                    stats.getDurationMsForIteration(iteration)
            );

            writer.appendArchitectureIteration(result);

            ObjectNode out = mapper.createObjectNode();
            out.put("iteration", iteration);
            out.put("ts", ts);
            out.put("revisions_used", revisionsUsed);
            out.put("finalized", true);
            out.put("issues_count", criticIssues.size());

            Turn turn = toTurn("Scribe", "Finalize iteration result and write logs.", out, emptyUsage());
            stats.addAgentTurn(iteration, turn);
            writer.appendConversationTurn(turn);
            return Map.of(
                    "results", result,
                    "conversation", turn
            );
        });

        var compactor = node_async(state -> {
            int iteration = intValue(state, "iteration", 1);
            String system = prompts.contextCompactorSystem;

            @SuppressWarnings("unchecked")
            List<IterationResult> results = (List<IterationResult>) state.value("results").orElse(List.of());

            String user = prompts.render(prompts.contextCompactorUser, Map.of(
                    "results", renderResultsForCompaction(results)
            ));

            CallResult call = callJson(system, user);
            JsonNode out = call.json();
            String compacted = textOrFallback(out, "compacted_history", simpleCompact(results));

            Turn turn = toTurn("ContextCompactor", user, out, call.usage());
            stats.addAgentTurn(iteration, turn);
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
            String prompt = prompts.render(prompts.humanCheckpointAfter, Map.of(
                    "iteration", iteration,
                    "goal", goal
            ));
            String v = io.readKeyword(prompt, "approve", "retry");
            ObjectNode out = mapper.createObjectNode().put("human_input", v);
            Turn turn = toTurn("HumanCheckpointAfter", prompt, out, emptyUsage());
            stats.addHumanTurn(iteration, turn);
            writer.appendConversationTurn(turn);
            if ("retry".equalsIgnoreCase(v)) {
                writer.appendConversationNote("Checkpoint retry: rerun Architect and Critic for the same iteration goal.");
            }
            if ("retry".equalsIgnoreCase(v)) {
                return Map.of(
                        "human_after", v,
                        "revision_count", 0,
                        "conversation", turn
                );
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
                        "retry", "architect",
                        "approve", "next_iteration"
                ))
                .addConditionalEdges("next_iteration", continueDecision, Map.of(
                        "continue", "orchestrator",
                        "end", END
                ));
    }

    private CallResult callJson(String system, String user) {
        ChatResponse response = chatClient.prompt().system(system).user(user).call().chatResponse();
        Usage usage = emptyUsage();
        String content = "";
        if (response != null) {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                usage = response.getMetadata().getUsage();
            }
            if (response.getResult() != null && response.getResult().getOutput() != null
                    && response.getResult().getOutput().getText() != null) {
                content = response.getResult().getOutput().getText();
            }
        }
        if (content == null) content = "";
        String stripped = stripCodeFences(content.trim());
        try {
            JsonNode n = mapper.readTree(stripped);
            if (n != null && n.isObject()) return new CallResult(n, usage);
            ObjectNode wrapped = mapper.createObjectNode();
            wrapped.set("value", n);
            return new CallResult(wrapped, usage);
        } catch (Exception e) {
            ObjectNode wrapped = mapper.createObjectNode();
            wrapped.put("raw_text", content);
            return new CallResult(wrapped, usage);
        }
    }

    private Turn toTurn(String node, String input, JsonNode output, Usage usage) throws IOException {
        String ts = TimeUtil.nowIso();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        int promptTokens = tokenOrZero(usage == null ? null : usage.getPromptTokens());
        int completionTokens = tokenOrZero(usage == null ? null : usage.getCompletionTokens());
        int totalTokens = tokenOrZero(usage == null ? null : usage.getTotalTokens());
        return new Turn(ts, node, input, json, promptTokens, completionTokens, totalTokens);
    }

    private int tokenOrZero(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private Usage emptyUsage() {
        return new EmptyUsage();
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
            case 1 -> "Establishing an Overall System Structure";
            case 2 -> "Identifying Structures to Support Primary Functionality";
            case 3 -> "Addressing Reliability and Availability Quality Attributes";
            case 4 -> "Addressing Development and Operations";
            default -> "Continue refining the architecture using ADD.";
        };
    }

    private String defaultMermaid(int iteration) {
        String title = "Iteration " + iteration + " - High-level components";
        return """
                flowchart TB
                  subgraph %s
                    UI[Web App UI]
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
        // Convert Mermaid round-node syntax `ID(Label)` to square-node syntax `ID[Label]`
        // so labels remain valid even when models mix styles.
        s = s.replaceAll("([A-Za-z][A-Za-z0-9_]*)\\(([^\\)\\n]+)\\)", "$1[$2]");
        // Fix malformed dotted edge label suffix like `|REST API|. B` -> `|REST API| B`.
        s = s.replaceAll("\\|\\s*\\.\\s*([A-Za-z][A-Za-z0-9_]*)", "| $1");
        // Split accidentally concatenated statements like `... ]C -.-> ...`.
        s = s.replaceAll("\\]([A-Za-z][A-Za-z0-9_]*)\\s*(-\\.|--|==)", "]\n$1 $2");
        String[] lines = s.split("\\R");
        List<String> normalized = new ArrayList<>();
        for (String line : lines) {
            String t = line;
            // Drop style/class directives because LLMs often emit invalid Mermaid style syntax.
            String lower = t.trim().toLowerCase();
            if (lower.startsWith("classdef ") || lower.startsWith("class ") || lower.startsWith("style ")) {
                continue;
            }
            t = t.replaceAll("\\s{2,}", " ").trim();
            if (!t.isBlank()) normalized.add(t);
        }
        return String.join("\n", normalized).trim();
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

    private int recommendedMaxIterations() {
        int perIterationBase = 8;
        int perIterationRevisionWorst = 4 * Math.max(0, cfg.maxRevisions);
        int perIterationBudget = perIterationBase + perIterationRevisionWorst;
        int planned = Math.max(1, cfg.iterations) * perIterationBudget;
        return Math.max(100, planned + 20);
    }

    private static final class CallResult {
        private final JsonNode json;
        private final Usage usage;

        private CallResult(JsonNode json, Usage usage) {
            this.json = json;
            this.usage = usage;
        }

        private JsonNode json() {
            return json;
        }

        private Usage usage() {
            return usage;
        }
    }

    private static final class IterationStats {
        private final List<PerIteration> rows = new ArrayList<>();

        private void addAgentTurn(int iteration, Turn turn) {
            PerIteration row = row(iteration);
            row.agentTurns++;
            addTokensAndTiming(row, turn);
        }

        private void addHumanTurn(int iteration, Turn turn) {
            PerIteration row = row(iteration);
            row.humanTurns++;
            addTokensAndTiming(row, turn);
        }

        private int getPromptTokensForIteration(int iteration) {
            return row(iteration).promptTokens;
        }

        private int getCompletionTokensForIteration(int iteration) {
            return row(iteration).completionTokens;
        }

        private int getTotalTokensForIteration(int iteration) {
            return row(iteration).totalTokens;
        }

        private int getHumanTurnsForIteration(int iteration) {
            return row(iteration).humanTurns;
        }

        private int getAgentTurnsForIteration(int iteration) {
            return row(iteration).agentTurns;
        }

        private long getDurationMsForIteration(int iteration) {
            PerIteration row = row(iteration);
            if (row.firstMs <= 0 || row.lastMs < row.firstMs) return 0L;
            return row.lastMs - row.firstMs;
        }

        private void addTokensAndTiming(PerIteration row, Turn turn) {
            long now = System.currentTimeMillis();
            if (row.firstMs == 0L) row.firstMs = now;
            row.lastMs = now;
            row.promptTokens += Math.max(0, turn.getPromptTokens());
            row.completionTokens += Math.max(0, turn.getCompletionTokens());
            row.totalTokens += Math.max(0, turn.getTotalTokens());
        }

        private PerIteration row(int iteration) {
            int idx = Math.max(1, iteration) - 1;
            while (rows.size() <= idx) {
                rows.add(new PerIteration());
            }
            return rows.get(idx);
        }

        private static final class PerIteration {
            private int promptTokens;
            private int completionTokens;
            private int totalTokens;
            private int humanTurns;
            private int agentTurns;
            private long firstMs;
            private long lastMs;
        }
    }

    private static final class PromptTemplates {
        private final String priorKnowledge;

        private final String orchestratorSystem;
        private final String orchestratorUser;

        private final String humanCheckpointBefore;
        private final String humanCheckpointAfter;

        private final String architectSystem;
        private final String architectUser;
        private final String architectCriticBlock;

        private final String criticSystem;
        private final String criticUser;

        private final String contextCompactorSystem;
        private final String contextCompactorUser;

        private PromptTemplates(
                String priorKnowledge,
                String orchestratorSystem,
                String orchestratorUser,
                String humanCheckpointBefore,
                String humanCheckpointAfter,
                String architectSystem,
                String architectUser,
                String architectCriticBlock,
                String criticSystem,
                String criticUser,
                String contextCompactorSystem,
                String contextCompactorUser
        ) {
            this.priorKnowledge = priorKnowledge;
            this.orchestratorSystem = orchestratorSystem;
            this.orchestratorUser = orchestratorUser;
            this.humanCheckpointBefore = humanCheckpointBefore;
            this.humanCheckpointAfter = humanCheckpointAfter;
            this.architectSystem = architectSystem;
            this.architectUser = architectUser;
            this.architectCriticBlock = architectCriticBlock;
            this.criticSystem = criticSystem;
            this.criticUser = criticUser;
            this.contextCompactorSystem = contextCompactorSystem;
            this.contextCompactorUser = contextCompactorUser;
        }

        private String render(String template, Map<String, ?> vars) {
            String out = template;
            for (Map.Entry<String, ?> e : vars.entrySet()) {
                String k = "{{" + e.getKey() + "}}";
                Object v = e.getValue();
                out = out.replace(k, v == null ? "" : v.toString());
            }
            return out.trim();
        }

        private static PromptTemplates load() throws IOException {
            String base = "prompts/";
            return new PromptTemplates(
                    readResource(base + "prior_knowledge.md"),
                    readResource(base + "orchestrator_system.md"),
                    readResource(base + "orchestrator_user.md"),
                    readResource(base + "human_checkpoint_before.md"),
                    readResource(base + "human_checkpoint_after.md"),
                    readResource(base + "architect_system.md"),
                    readResource(base + "architect_user.md"),
                    readResource(base + "architect_critic_block.md"),
                    readResource(base + "critic_system.md"),
                    readResource(base + "critic_user.md"),
                    readResource(base + "context_compactor_system.md"),
                    readResource(base + "context_compactor_user.md")
            );
        }

        private static String readResource(String classpathLocation) throws IOException {
            try (InputStream in = MultiAgentEngine.class.getClassLoader().getResourceAsStream(classpathLocation)) {
                if (in == null) throw new IOException("Missing classpath resource: " + classpathLocation);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        }
    }
}


