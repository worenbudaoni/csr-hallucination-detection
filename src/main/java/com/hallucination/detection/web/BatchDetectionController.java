package com.hallucination.detection.web;

import com.hallucination.detection.batch.BatchDetectionService;
import com.hallucination.detection.batch.BatchRequestParser;
import com.hallucination.detection.batch.BatchResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 批量检测接口：调用方现给数据和模型凭据，后端拿这些去请求大模型。
 *
 * <p>与 {@code /api/detect}（单条、用服务端配置好的模型）的区别在于，
 * 这里的地址、密钥、模型都来自请求本身——用一次就丢，不落盘、不缓存。
 * 这是"打开页面填上就能用"那条路径的后端。
 *
 * <p>请求体：
 * <pre>
 * {
 *   "base_url": "https://api.deepseek.com/v1",
 *   "api_key":  "sk-...",
 *   "model":    "deepseek-chat",
 *   "replies":      [ { "id", "user_question", "system_reply", "knowledge_base" } ],
 *   "ground_truth": [ { "id", "is_hallucination", "hallucination_type", "detail" } ]   // 可选
 * }
 * </pre>
 */
@RestController
public class BatchDetectionController {

    private final BatchDetectionService detectionService;

    public BatchDetectionController(BatchDetectionService detectionService) {
        this.detectionService = detectionService;
    }

    @PostMapping(value = "/api/detect/batch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> detectBatch(@RequestBody JsonNode body) {
        BatchRequestParser.Parsed parsed = BatchRequestParser.parse(body);

        if (!parsed.ok()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "invalid_request");
            error.put("message", "请求参数有问题，共 " + parsed.errors().size() + " 处。");
            error.put("errors", parsed.errors());
            error.put("warnings", parsed.warnings());
            return ResponseEntity.badRequest().body(error);
        }

        BatchResult result = detectionService.detect(parsed.request());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("warnings", parsed.warnings());
        response.put("data", result);
        return ResponseEntity.ok(response);
    }
}
