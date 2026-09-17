package com.hallucination.detection.detector.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按请求现构的对话模型：直连调用方指定的 OpenAI 兼容端点。
 *
 * <p><b>为什么不用 Spring AI 自带的 {@code OpenAiChatModel}。</b>
 * 它的客户端是在容器启动时按 {@code spring.ai.openai.*} 配置一次性建好的，
 * 而这里的需求是"用户在页面上现填地址和密钥"——每个请求一套凭据。
 * Spring AI 提供了一个公开的 {@code OpenAiSetup.setupSyncClient(...)}，
 * 但它是 15 个参数的静态方法，还得传一堆 SDK 内部类型和 null，为一个临时端点去调它
 * 又难读又容易随版本失效。所以这里直接说 HTTP：协议是公开的，行为完全可控，
 * 错误信息也能按需要组织。
 *
 * <p>它实现的是 Spring AI 的 {@link ChatModel} 接口，所以现有的
 * {@link LlmClaimDetector} 可以原样复用，不需要为这条路径再写一套检测逻辑。
 *
 * <p><b>凭据不落地</b>：地址、密钥、模型都只存在于这一个实例里，用完即弃，
 * 不写日志、不写文件、不进任何缓存。
 */
public class OpenAiCompatibleChatModel implements ChatModel {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param baseUrl 形如 {@code https://api.deepseek.com/v1}，必须带各家的路径前缀
     * @param apiKey  密钥；留空表示不发送 Authorization 头（本地模型常见）
     * @param model   模型名，必须与所选厂商匹配
     */
    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model) {
        this.endpoint = normalize(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 把 base-url 规整成 chat completions 端点，容忍用户多写或少写尾部斜杠。 */
    static String normalize(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // 用户可能已经带上了 /chat/completions，避免拼成两遍
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed + "/chat/completions";
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String body = buildRequestBody(prompt.getContents());
        HttpResponse<String> response = send(body);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new LlmDetectionException(describeStatus(response.statusCode(), response.body()), null);
        }

        String text = extractContent(response.body());
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 检测是一次性拿到完整 JSON 再解析，用不到流式
        return Flux.just(call(prompt));
    }

    // ---------------------------------------------------------------------

    private String buildRequestBody(String userContent) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        // 判定类任务不要随机性，否则同一份数据两次跑出的结果会漂移
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");
        ObjectNode message = messages.addObject();
        message.put("role", "user");
        message.put("content", userContent);
        return mapper.writeValueAsString(root);
    }

    private HttpResponse<String> send(String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmDetectionException(
                    "连不上 " + endpoint + "（" + reasonOf(e)
                            + "）。检查地址是否正确、服务是否可达、本机是否需要代理。", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmDetectionException("请求被中断", e);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asString("").isBlank()) {
                throw new LlmDetectionException(
                        "模型返回里没有 choices[0].message.content。原始响应片段："
                                + abbreviate(responseBody), null);
            }
            return content.asString();
        } catch (LlmDetectionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmDetectionException("无法解析模型响应：" + abbreviate(responseBody), e);
        }
    }

    /** 把 HTTP 状态码翻译成能照着排查的说明。 */
    static String describeStatus(int status, String body) {
        String detail = abbreviate(body);
        return switch (status) {
            case 401, 403 -> "鉴权失败（HTTP " + status + "）：密钥不正确、已过期，或没有该模型的权限。"
                    + "也可能 key 与 base URL 不是同一家厂商。服务端返回：" + detail;
            case 404 -> "端点不存在（HTTP 404）：base URL 的路径写错了。"
                    + "各家路径后缀并不统一——OpenAI / DeepSeek / Kimi 用 /v1，智谱用 /api/paas/v4，"
                    + "通义用 /compatible-mode/v1。也不要自己带上 /chat/completions。返回：" + detail;
            case 429 -> "触发限流（HTTP 429）：请求太频繁，稍后重试或降低数据量。返回：" + detail;
            default -> status >= 500
                    ? "服务端错误（HTTP " + status + "）：厂商侧的问题，稍后重试。返回：" + detail
                    : "请求失败（HTTP " + status + "）。返回：" + detail;
        };
    }

    /**
     * 取一段人能看懂的错误原因。
     *
     * <p>连接类异常（如 {@code ConnectException}）常常没有 message，
     * 直接拼 {@code getMessage()} 会得到"连不上 xxx：null"这种毫无信息量的输出。
     * 所以退回到异常类型名，再兜一层 cause。
     */
    private static String reasonOf(Throwable e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        return e.getClass().getSimpleName();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "（无内容）";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    /** 供接口层回报"这次用的是哪个端点"，不含密钥。 */
    public Map<String, String> describe() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("endpoint", endpoint);
        info.put("model", model);
        info.put("apiKeyConfigured", String.valueOf(!apiKey.isEmpty()));
        return info;
    }
}
