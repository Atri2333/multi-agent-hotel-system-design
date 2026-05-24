package com.hotel.system.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class OpenAiCompatibleChatClient implements LlmClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleChatClient(ObjectMapper mapper, String baseUrl, String apiKey, String model) {
        this.mapper = mapper;
        this.client = HttpClient.newHttpClient();
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public JsonNode generateJson(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);

        ArrayNode messages = req.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);

        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", userPrompt);

        req.put("temperature", 0.2);

        String body = mapper.writeValueAsString(req);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (apiKey != null && !apiKey.isBlank()) {
            b.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", "HTTP " + resp.statusCode());
            err.put("body", resp.body());
            return err;
        }

        JsonNode root = mapper.readTree(resp.body());
        String content = root.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            ObjectNode err = mapper.createObjectNode();
            err.put("error", "Empty content");
            err.set("raw", root);
            return err;
        }

        JsonNode parsed = tryParseJsonObject(content);
        if (parsed != null) return parsed;

        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("raw_text", content);
        return wrapped;
    }

    private JsonNode tryParseJsonObject(String content) {
        String s = content.trim();
        s = stripCodeFences(s);
        try {
            JsonNode n = mapper.readTree(s);
            if (n != null && n.isObject()) return n;
            if (n != null) {
                ObjectNode w = mapper.createObjectNode();
                w.set("value", n);
                return w;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String stripCodeFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("(?s)^```\\w*\\s*", "");
            t = t.replaceFirst("(?s)```\\s*$", "");
        }
        return t.trim();
    }

    private String stripTrailingSlash(String s) {
        if (s == null) return null;
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
