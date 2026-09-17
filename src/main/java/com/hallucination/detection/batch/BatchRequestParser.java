package com.hallucination.detection.batch;

import com.hallucination.detection.model.GroundTruthRecord;
import com.hallucination.detection.model.ReplyRecord;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 请求解析与校验。
 *
 * <p><b>后端是权威校验方。</b>页面上那套校验只是为了让用户早点看到问题，
 * 任何人都可以直接调接口，所以这里的规则必须独立成立，不能依赖前端拦过一遍。
 *
 * <p>错误一次列全，不是遇到第一个就返回——批量提交时逐个试错很折磨人。
 */
public final class BatchRequestParser {

    private BatchRequestParser() {
    }

    /**
     * @param request  解析成功时的请求对象；有致命错误时为 null
     * @param errors   致命错误，调用方应拒绝该请求
     * @param warnings 不致命但值得提醒的问题
     */
    public record Parsed(BatchDetectionRequest request, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return request != null && errors.isEmpty();
        }
    }

    public static Parsed parse(JsonNode body) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String baseUrl = text(body, "base_url", "baseUrl");
        String apiKey = text(body, "api_key", "apiKey");
        String model = text(body, "model");

        validateBaseUrl(baseUrl, errors, warnings);
        if (isBlank(model)) {
            errors.add("缺少 model：需要与所选厂商匹配的模型名，例如 deepseek-chat / qwen-plus / gpt-4o-mini。");
        }
        validateApiKey(apiKey, baseUrl, errors, warnings);

        List<ReplyRecord> replies = parseReplies(body.path("replies"), errors, warnings);
        List<GroundTruthRecord> groundTruth = parseGroundTruth(
                firstPresent(body, "ground_truth", "groundTruth"), errors, warnings);

        if (!errors.isEmpty()) {
            return new Parsed(null, errors, warnings);
        }
        return new Parsed(new BatchDetectionRequest(
                trimTrailingSlashes(baseUrl), apiKey == null ? "" : apiKey.trim(), model.trim(),
                replies, groundTruth), errors, warnings);
    }

    // ---------------------------------------------------------------------

    private static void validateBaseUrl(String baseUrl, List<String> errors, List<String> warnings) {
        if (isBlank(baseUrl)) {
            errors.add("缺少 base_url：需要 OpenAI 兼容端点的地址，例如 https://api.deepseek.com/v1。");
            return;
        }
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (URISyntaxException e) {
            errors.add("base_url 不是合法的 URL：" + baseUrl);
            return;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            errors.add("base_url 无法识别协议：" + baseUrl
                    + "。地址要带 http:// 或 https:// 前缀，例如 https://api.deepseek.com/v1。");
            return;
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            errors.add("base_url 的协议必须是 http 或 https，当前是：" + scheme);
            return;
        }
        if ("http".equals(scheme) && !isLocalHost(uri.getHost())) {
            warnings.add("base_url 用的是 http 明文，API key 会以明文经过网络。");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        if (path.endsWith("/chat/completions")) {
            warnings.add("base_url 里已经带了 /chat/completions，程序会再拼一次，建议只填到版本号为止。");
        } else if (path.isEmpty()) {
            warnings.add("base_url 没有路径部分。多数厂商需要版本前缀（例如 /v1），"
                    + "缺了会得到 404——各家后缀并不统一，智谱是 /api/paas/v4，通义是 /compatible-mode/v1。");
        }
    }

    private static void validateApiKey(String apiKey, String baseUrl,
                                       List<String> errors, List<String> warnings) {
        if (!isBlank(apiKey)) {
            return;
        }
        boolean local = false;
        try {
            local = isLocalHost(new URI(baseUrl.trim()).getHost());
        } catch (Exception ignored) {
            // base_url 本身有问题时上面已经报过了
        }
        if (local) {
            warnings.add("未填 api_key。本地模型通常不需要密钥，可以继续。");
        } else {
            errors.add("缺少 api_key：云端厂商会返回 401。若连的是本地模型，请确认 base_url 指向 localhost。");
        }
    }

    private static List<ReplyRecord> parseReplies(JsonNode node, List<String> errors, List<String> warnings) {
        if (node.isMissingNode() || node.isNull()) {
            errors.add("缺少 replies：需要 task4_replies.json 那样的 JSON 数组。");
            return List.of();
        }
        if (!node.isArray()) {
            errors.add("replies 必须是数组。");
            return List.of();
        }
        if (node.isEmpty()) {
            errors.add("replies 是空数组，没有可检测的数据。");
            return List.of();
        }

        List<ReplyRecord> replies = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode item : node) {
            // 下标从 0 起，和 JSON 数组本身对齐——从 1 起会让人对着数组数错位置
            String at = "replies[" + index + "]";
            index++;
            if (!item.isObject()) {
                errors.add(at + " 不是对象。");
                continue;
            }
            String id = item.path("id").asString("");
            String reply = item.path("system_reply").asString("");
            String kb = item.path("knowledge_base").asString("");
            String question = item.path("user_question").asString("");

            if (id.isBlank()) {
                errors.add(at + " 缺少 id。");
                continue;
            }
            if (!seenIds.add(id)) {
                warnings.add(at + " 的 id \"" + id + "\" 与前面的重复，报告中会难以区分。");
            }
            if (reply.isBlank()) {
                errors.add(at + "（id=" + id + "）缺少 system_reply，这是要被检测的回复本身。");
                continue;
            }
            if (kb.isBlank()) {
                warnings.add(at + "（id=" + id + "）的 knowledge_base 为空，该条只能判为「知识库无据」。");
            }
            replies.add(new ReplyRecord(id, question, reply, kb));
        }
        return replies;
    }

    private static List<GroundTruthRecord> parseGroundTruth(JsonNode node,
                                                            List<String> errors,
                                                            List<String> warnings) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            errors.add("ground_truth 必须是数组。");
            return List.of();
        }

        List<GroundTruthRecord> truths = new ArrayList<>();
        int index = 0;
        for (JsonNode item : node) {
            String at = "ground_truth[" + index + "]";
            index++;
            if (!item.isObject()) {
                errors.add(at + " 不是对象。");
                continue;
            }
            String id = item.path("id").asString("");
            JsonNode flag = item.path("is_hallucination");
            if (id.isBlank()) {
                errors.add(at + " 缺少 id。");
                continue;
            }
            if (!flag.isBoolean()) {
                errors.add(at + "（id=" + id + "）的 is_hallucination 必须是 true 或 false。");
                continue;
            }
            JsonNode typeNode = item.path("hallucination_type");
            truths.add(new GroundTruthRecord(id, flag.asBoolean(),
                    typeNode.isMissingNode() || typeNode.isNull() ? null : typeNode.asString(),
                    item.path("detail").asString("")));
        }
        return truths;
    }

    // ---------------------------------------------------------------------

    private static boolean isLocalHost(String host) {
        return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("0.0.0.0"));
    }

    private static String trimTrailingSlashes(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String text(JsonNode body, String... names) {
        JsonNode node = firstPresent(body, names);
        return node.isMissingNode() || node.isNull() ? null : node.asString("");
    }

    private static JsonNode firstPresent(JsonNode body, String... names) {
        for (String name : names) {
            JsonNode node = body.path(name);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return body.path("__absent__");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
