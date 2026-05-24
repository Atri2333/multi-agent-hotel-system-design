package com.hotel.system.llm;

import com.fasterxml.jackson.databind.JsonNode;

public interface LlmClient {
    JsonNode generateJson(String systemPrompt, String userPrompt) throws Exception;
}
