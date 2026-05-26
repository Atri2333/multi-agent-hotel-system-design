package com.hotel.system.config;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

public final class AppConfig {
    public final int iterations;
    public final int maxRevisions;
    public final Path outputDir;

    public final String openAiBaseUrl;
    public final String openAiApiKey;
    public final String openAiModel;

    private AppConfig(int iterations, int maxRevisions, Path outputDir, String openAiBaseUrl, String openAiApiKey, String openAiModel) {
        this.iterations = iterations;
        this.maxRevisions = maxRevisions;
        this.outputDir = outputDir;
        this.openAiBaseUrl = openAiBaseUrl;
        this.openAiApiKey = openAiApiKey;
        this.openAiModel = openAiModel;
    }

    public static AppConfig fromEnv() {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (Exception ignored) {
        }

        int iterations = parseIntOrDefault(getPropOrEnv(props, "ma.iterations", "MA_ITERATIONS"), 4);
        int maxRevisions = parseIntOrDefault(getPropOrEnv(props, "ma.max-revisions", "MA_MAX_REVISIONS"), 2);

        String out = getPropOrEnv(props, "ma.output-dir", "MA_OUTPUT_DIR");
        Path outputDir = out == null || out.isBlank() ? Path.of("").toAbsolutePath() : Path.of(out).toAbsolutePath();

        String baseUrl = getPropOrEnv(props, "spring.ai.openai.base-url", "APP_OPENAI_BASE_URL");
        String apiKey = getPropOrEnv(props, "spring.ai.openai.api-key", "APP_OPENAI_API_KEY");
        String model = getPropOrEnv(props, "spring.ai.openai.chat.options.model", "APP_OPENAI_MODEL");
        if (model == null || model.isBlank()) model = "qwen3-32b";

        return new AppConfig(iterations, maxRevisions, outputDir, baseUrl, apiKey, model);
    }

    private static String getPropOrEnv(Properties props, String propKey, String envKey) {
        // Priority 1: application.properties
        String propVal = props.getProperty(propKey);
        if (propVal != null && !propVal.isBlank()) {
            return propVal.trim();
        }
        // Priority 2: Environment variables
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }
        return null;
    }

    private static int parseIntOrDefault(String v, int d) {
        if (v == null || v.isBlank()) return d;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception ignored) {
            return d;
        }
    }
}
