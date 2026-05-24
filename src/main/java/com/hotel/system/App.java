package com.hotel.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.system.config.AppConfig;
import com.hotel.system.engine.MultiAgentEngine;
import com.hotel.system.io.ConsoleIO;
import com.hotel.system.llm.LlmClient;
import com.hotel.system.llm.MockLlmClient;
import com.hotel.system.llm.OpenAiCompatibleChatClient;
import com.hotel.system.log.MarkdownLogWriter;
import com.hotel.system.state.OverallState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        var cfg = AppConfig.fromEnv();
        var mapper = new ObjectMapper();

        LlmClient llmClient = cfg.openAiBaseUrl == null || cfg.openAiBaseUrl.isBlank()
                ? new MockLlmClient(mapper)
                : new OpenAiCompatibleChatClient(mapper, cfg.openAiBaseUrl, cfg.openAiApiKey, cfg.openAiModel);

        var io = new ConsoleIO(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        var writer = new MarkdownLogWriter(cfg.outputDir, mapper);

        var state = OverallState.bootstrap(cfg, writer);

        var engine = new MultiAgentEngine(cfg, mapper, llmClient, io, writer);
        engine.run(state);

        log.info("Done. Outputs: {}", cfg.outputDir.toAbsolutePath());
        log.info(" - {}", cfg.outputDir.resolve("conversation_log.md").toAbsolutePath());
        log.info(" - {}", cfg.outputDir.resolve("architecture_report.md").toAbsolutePath());
    }
}
