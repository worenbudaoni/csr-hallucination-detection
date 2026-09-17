package com.hallucination.detection.web;

import com.hallucination.detection.batch.BatchRequestParser;
import com.hallucination.detection.detector.llm.OpenAiCompatibleChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连通性探测：拿调用方给的地址与密钥发一个最小请求，验证配置是否可用。
 *
 * <p>存在的意义是<b>把失败提前</b>。填错地址或密钥时，如果不探测，
 * 用户要等到点「开始检测」、跑完一批（每条都要等模型响应）之后才会看到失败——
 * 而失败原因往往只是 base URL 少了个路径前缀。
 *
 * <p>探测走的客户端与正式检测完全一致，因此它报出来的错就是检测时会遇到的错，
 * 包括各家路径后缀的差异、401 的成因、限流等等。
 */
@RestController
public class LlmProbeController {

    /** 探测用的最小提示词：只要模型能回话就说明链路是通的。 */
    private static final String PROBE_PROMPT = "只回复两个字：可以";

    private static final int MAX_REPLY_PREVIEW = 80;

    @PostMapping(value = "/api/llm/ping",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> ping(@RequestBody JsonNode body) {
        BatchRequestParser.ParsedConnection parsed = BatchRequestParser.parseConnection(body);

        if (!parsed.ok()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "invalid_request");
            error.put("message", "连接参数有问题，共 " + parsed.errors().size() + " 处。");
            error.put("errors", parsed.errors());
            error.put("warnings", parsed.warnings());
            return ResponseEntity.badRequest().body(error);
        }

        BatchRequestParser.ConnectionSettings settings = parsed.settings();
        ChatModel model = new OpenAiCompatibleChatModel(
                settings.baseUrl(), settings.apiKey(), settings.model());

        // 调用失败会抛 LlmDetectionException，由 ApiExceptionHandler 转成 503 + 排查提示
        String reply = model.call(new Prompt(PROBE_PROMPT)).getResult().getOutput().getText();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("model", settings.model());
        response.put("endpoint", describeEndpoint(settings));
        response.put("reply", preview(reply));
        response.put("warnings", parsed.warnings());
        return ResponseEntity.ok(response);
    }

    /** 回报实际会请求到哪个地址——路径拼错时，这一条比什么都直观。 */
    private static String describeEndpoint(BatchRequestParser.ConnectionSettings settings) {
        return new OpenAiCompatibleChatModel(settings.baseUrl(), "", settings.model())
                .describe().get("endpoint");
    }

    private static String preview(String reply) {
        if (reply == null) {
            return "";
        }
        String flat = reply.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_REPLY_PREVIEW
                ? flat
                : flat.substring(0, MAX_REPLY_PREVIEW) + "…";
    }
}
