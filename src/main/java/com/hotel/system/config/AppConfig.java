package com.hotel.system.config;

import java.nio.file.Path;

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
        int iterations = parseIntOrDefault(System.getenv("MA_ITERATIONS"), 4);
        int maxRevisions = parseIntOrDefault(System.getenv("MA_MAX_REVISIONS"), 2);

        String out = System.getenv("MA_OUTPUT_DIR");
        Path outputDir = out == null || out.isBlank() ? Path.of("").toAbsolutePath() : Path.of(out).toAbsolutePath();

        String baseUrl = System.getenv("OPENAI_BASE_URL");
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv("OPENAI_MODEL");
        if (model == null || model.isBlank()) model = "qwen3-32b";

        return new AppConfig(iterations, maxRevisions, outputDir, baseUrl, apiKey, model);
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
