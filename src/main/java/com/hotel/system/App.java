package com.hotel.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.system.ai.MockChatModel;
import com.hotel.system.config.AppConfig;
import com.hotel.system.engine.MultiAgentEngine;
import com.hotel.system.io.ConsoleIO;
import com.hotel.system.log.MarkdownLogWriter;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        var cfg = AppConfig.fromEnv();
        var mapper = new ObjectMapper();

        ChatModel chatModel = createChatModel(mapper, cfg);
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        var io = new ConsoleIO(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        var writer = new MarkdownLogWriter(cfg.outputDir, mapper);

        var engine = new MultiAgentEngine(cfg, mapper, chatClient, io, writer);
        engine.run();

        log.info("Done. Outputs: {}", cfg.outputDir.toAbsolutePath());
        log.info(" - {}", cfg.outputDir.resolve("conversation_log.md").toAbsolutePath());
        log.info(" - {}", cfg.outputDir.resolve("architecture_report.md").toAbsolutePath());
    }

    private static ChatModel createChatModel(ObjectMapper mapper, AppConfig cfg) {
        // 特殊处理：如果是 qwen 系列模型（尤其是 qwen3-32b），并且使用了百炼的 API Key，
        // 则直接切换为 Spring AI Alibaba 专属的 DashScopeChatModel，以便支持 enable_thinking 等特有参数。
        if (cfg.openAiApiKey != null && !cfg.openAiApiKey.isBlank() && cfg.openAiModel != null && cfg.openAiModel.toLowerCase().startsWith("qwen")) {
            DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(cfg.openAiApiKey).build();
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .withModel(cfg.openAiModel)
                    .withEnableThinking(false)
                    .build();
            return DashScopeChatModel.builder()
                    .dashScopeApi(dashScopeApi)
                    .defaultOptions(options)
                    .build();
        }

        if (cfg.openAiApiKey != null && !cfg.openAiApiKey.isBlank()) {
            String baseUrl = cfg.openAiBaseUrl != null && !cfg.openAiBaseUrl.isBlank() ? cfg.openAiBaseUrl : "https://api.openai.com";
            var openAiApi = org.springframework.ai.openai.api.OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(cfg.openAiApiKey)
                    .build();
            var options = org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .model(cfg.openAiModel)
                    .build();
            return org.springframework.ai.openai.OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(options)
                    .build();
        }

        String key = System.getenv("AI_DASHSCOPE_API_KEY");
        if (key != null && !key.isBlank()) {
            DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(key).build();
            return DashScopeChatModel.builder().dashScopeApi(dashScopeApi).build();
        }
        return new MockChatModel(mapper);
    }
}
