package com.hotel.system.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

public final class MockLlmClient implements LlmClient {
    private final ObjectMapper mapper;

    public MockLlmClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public JsonNode generateJson(String systemPrompt, String userPrompt) {
        String sys = systemPrompt.toLowerCase(Locale.ROOT);
        ObjectNode o = mapper.createObjectNode();

        if (sys.contains("orchestrator")) {
            o.put("iteration_goal", pickFromUser(userPrompt, "prefer:", "Define scope"));
            o.put("routing", "HumanCheckpoint -> Architect -> Critic(loop<=2) -> Scribe -> ContextCompactor");
            ArrayNode d = o.putArray("decision_log");
            d.add("Single-focus goal for this iteration.");
            d.add("Sequential execution with bounded revision loop.");
            return o;
        }

        if (sys.contains("architect")) {
            o.put("design", """
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
            o.put("diagram_code", """
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
            ArrayNode d = o.putArray("decision_log");
            d.add("Split by domain capabilities to isolate change.");
            d.add("Use events for cross-service consistency.");
            return o;
        }

        if (sys.contains("critic")) {
            o.put("pass", true);
            ArrayNode issues = o.putArray("issues");
            if (userPrompt.toLowerCase(Locale.ROOT).contains("payment") && userPrompt.toLowerCase(Locale.ROOT).contains("async")) {
                issues.add("Ensure idempotency keys for booking and payment commands.");
                o.put("pass", false);
            }
            ArrayNode d = o.putArray("decision_log");
            d.add("Check key constraints and missing responsibilities.");
            return o;
        }

        if (sys.contains("context compactor")) {
            o.put("compacted_history", "Summary: core services (reservation/inventory/pricing/payment/customer), REST+events, focus on modifiability/availability/audit.");
            return o;
        }

        o.put("ok", true);
        return o;
    }

    private String pickFromUser(String user, String key, String fallback) {
        int idx = user.toLowerCase(Locale.ROOT).indexOf(key);
        if (idx < 0) return fallback;
        String s = user.substring(idx + key.length()).trim();
        if (s.isBlank()) return fallback;
        return s;
    }
}
