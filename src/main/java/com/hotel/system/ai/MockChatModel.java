package com.hotel.system.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Locale;

public final class MockChatModel implements ChatModel {
    private final ObjectMapper mapper;

    public MockChatModel(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String system = prompt.getSystemMessage().getText();
        String user = prompt.getUserMessage().getText();

        ObjectNode out = mapper.createObjectNode();
        String sys = system == null ? "" : system.toLowerCase(Locale.ROOT);

        if (sys.contains("orchestrator")) {
            out.put("iteration_goal", pickFromUser(user, "prefer:", "Define scope"));
            out.put("routing", "HumanCheckpoint -> Architect -> Critic(loop<=2) -> Scribe -> ContextCompactor");
            ArrayNode d = out.putArray("decision_log");
            d.add("Single-focus goal for this iteration.");
            d.add("Sequential execution with bounded revision loop.");
            return toResponse(out);
        }

        if (sys.contains("architect")) {
            out.put("design", """
                    Components:
                    - API Gateway: auth, request routing
                    - Reservation Service: booking lifecycle (hold/confirm/cancel)
                    - Inventory Service: room availability + allocation
                    - Pricing Service: rate plans, promotions
                    - Payment Service: payment intents, refunds (async confirmation)
                    - Customer Service: profile, loyalty
                    Interfaces:
                    - REST for synchronous queries/commands; events for state changes
                    ADD notes:
                    - Prioritize modifiability (pricing rules), availability (inventory), and auditability (payments/reservations)
                    """.trim());
            out.put("diagram_code", """
                    flowchart TB
                      UI[Web/App] --> API[API Gateway]
                      API --> RES[Reservation Service]
                      API --> INV[Inventory Service]
                      API --> PRI[Pricing Service]
                      API --> PAY[Payment Service]
                      API --> CUS[Customer Service]
                      RES --> EVT[(Event Bus)]
                      INV --> EVT
                      PAY --> EVT
                      RES --> DB[(Hotel DB)]
                      INV --> DB
                      PRI --> DB
                      CUS --> DB
                    """.trim());
            ArrayNode d = out.putArray("decision_log");
            d.add("Split by domain capabilities to isolate change.");
            d.add("Use events for cross-service consistency.");
            return toResponse(out);
        }

        if (sys.contains("critic")) {
            out.put("pass", true);
            ArrayNode issues = out.putArray("issues");
            if (containsAll(user, List.of("payment", "async"))) {
                issues.add("Ensure idempotency keys for booking and payment commands.");
                out.put("pass", false);
            }
            ArrayNode d = out.putArray("decision_log");
            d.add("Check key constraints and missing responsibilities.");
            return toResponse(out);
        }

        if (sys.contains("context compactor")) {
            out.put("compacted_history", "Summary: core services (reservation/inventory/pricing/payment/customer), REST+events, focus on modifiability/availability/audit.");
            return toResponse(out);
        }

        out.put("ok", true);
        return toResponse(out);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    private ChatResponse toResponse(JsonNode node) {
        String content;
        try {
            content = mapper.writeValueAsString(node);
        } catch (Exception e) {
            content = "{\"error\":\"mock_serialization_failed\"}";
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private boolean containsAll(String s, List<String> needles) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        for (String n : needles) {
            if (!t.contains(n.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private String pickFromUser(String user, String key, String fallback) {
        if (user == null) return fallback;
        int idx = user.toLowerCase(Locale.ROOT).indexOf(key);
        if (idx < 0) return fallback;
        String s = user.substring(idx + key.length()).trim();
        return s.isBlank() ? fallback : s;
    }
}
