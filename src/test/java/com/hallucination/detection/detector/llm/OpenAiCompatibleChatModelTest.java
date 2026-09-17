package com.hallucination.detection.detector.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按请求构造的模型客户端的纯逻辑部分。
 *
 * <p>真正发请求的部分不在这里测——那需要网络和真实密钥，属于手工验证的范畴。
 * 这里覆盖的是两件容易出错、又完全确定的事情：端点拼接，以及把 HTTP 状态码
 * 翻译成用户能照着排查的说明。
 */
class OpenAiCompatibleChatModelTest {

    // ---------------------------------------------------------------------
    // 端点拼接
    // ---------------------------------------------------------------------

    @Test
    void appendsCompletionsPath() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleChatModel.normalize("https://api.deepseek.com/v1"));
    }

    /**
     * 各家路径后缀并不统一，不能自作主张地补 /v1 或剥掉它。
     * 智谱用的是 /api/paas/v4，通义是 /compatible-mode/v1。
     */
    @Test
    void keepsWhateverPathTheUserGave() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions",
                OpenAiCompatibleChatModel.normalize("https://open.bigmodel.cn/api/paas/v4"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                OpenAiCompatibleChatModel.normalize("https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    void trimsTrailingSlashes() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleChatModel.normalize("https://api.deepseek.com/v1/"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleChatModel.normalize("https://api.deepseek.com/v1///"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleChatModel.normalize("  https://api.deepseek.com/v1  "));
    }

    /** 调用方可能已经带上了完整路径，别拼成两遍。 */
    @Test
    void doesNotDoubleAppendCompletionsPath() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                OpenAiCompatibleChatModel.normalize("https://api.deepseek.com/v1/chat/completions"));
    }

    @Test
    void handlesLocalEndpoints() {
        assertEquals("http://localhost:11434/v1/chat/completions",
                OpenAiCompatibleChatModel.normalize("http://localhost:11434/v1"));
    }

    // ---------------------------------------------------------------------
    // 状态码翻译
    // ---------------------------------------------------------------------

    @Test
    void explains401AsAnAuthProblem() {
        String message = OpenAiCompatibleChatModel.describeStatus(401, "{\"error\":\"bad key\"}");

        assertTrue(message.contains("鉴权"), message);
        assertTrue(message.contains("401"), message);
        assertTrue(message.contains("bad key"), "应当带上服务端原文：" + message);
    }

    @Test
    void explains403TheSameWayAs401() {
        assertTrue(OpenAiCompatibleChatModel.describeStatus(403, "").contains("鉴权"));
    }

    /** 404 几乎总是路径写错，报错里要直接把各家后缀差异讲出来。 */
    @Test
    void explains404AsAPathProblemWithExamples() {
        String message = OpenAiCompatibleChatModel.describeStatus(404, "");

        assertTrue(message.contains("404"), message);
        assertTrue(message.contains("/v1"), "应当举出常见路径：" + message);
        assertTrue(message.contains("/api/paas/v4"), "应当点明智谱的后缀不同：" + message);
        assertTrue(message.contains("/chat/completions"), "应当提醒别再手写这个后缀：" + message);
    }

    @Test
    void explains429AsRateLimiting() {
        assertTrue(OpenAiCompatibleChatModel.describeStatus(429, "").contains("限流"));
    }

    @Test
    void explains5xxAsServerSide() {
        String message = OpenAiCompatibleChatModel.describeStatus(503, "");
        assertTrue(message.contains("服务端"), message);
        assertTrue(message.contains("503"), message);
    }

    @Test
    void truncatesVeryLongErrorBodies() {
        String huge = "x".repeat(5000);
        String message = OpenAiCompatibleChatModel.describeStatus(400, huge);

        assertTrue(message.length() < 1000, "过长的响应不该原样回给用户，实际长度 " + message.length());
        assertTrue(message.contains("…"), "截断处应当有省略标记");
    }

    @Test
    void handlesEmptyAndNullBodies() {
        assertTrue(OpenAiCompatibleChatModel.describeStatus(400, "").contains("400"));
        assertTrue(OpenAiCompatibleChatModel.describeStatus(400, null).contains("无内容"));
    }

    // ---------------------------------------------------------------------
    // 端点信息回报
    // ---------------------------------------------------------------------

    /** 回报里不能出现密钥。 */
    @Test
    void describeNeverLeaksTheApiKey() {
        OpenAiCompatibleChatModel model =
                new OpenAiCompatibleChatModel("https://api.deepseek.com/v1", "sk-super-secret", "deepseek-v4-pro");

        Map<String, String> info = model.describe();

        assertEquals("https://api.deepseek.com/v1/chat/completions", info.get("endpoint"));
        assertEquals("deepseek-v4-pro", info.get("model"));
        assertEquals("true", info.get("apiKeyConfigured"));
        for (String value : info.values()) {
            assertFalse(value.contains("sk-super-secret"), "回报里泄露了密钥：" + value);
        }
    }

    @Test
    void describesLocalModeAsHavingNoApiKey() {
        OpenAiCompatibleChatModel model =
                new OpenAiCompatibleChatModel("http://localhost:11434/v1", "", "qwen2.5:7b");

        assertEquals("false", model.describe().get("apiKeyConfigured"));
    }

    @Test
    void treatsBlankApiKeyAsAbsent() {
        assertEquals("false", new OpenAiCompatibleChatModel("http://localhost:1/v1", "   ", "m")
                .describe().get("apiKeyConfigured"));
    }
}
