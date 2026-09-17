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

    /** 模型连接信息。检测与连通性探测共用，校验规则也共用。 */
    public record ConnectionSettings(String baseUrl, String apiKey, String model) {
    }

    /** 只解析连接信息的结果。 */
    public record ParsedConnection(ConnectionSettings settings, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return settings != null && errors.isEmpty();
        }
    }

    /**
     * 只解析 base_url / api_key / model 三项，不要求带 replies。
     *
     * <p>「测试连接」只需要这三项，没必要为了探一次连通性去构造假数据。
     */
    public static ParsedConnection parseConnection(JsonNode body) {
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

        if (!errors.isEmpty()) {
            return new ParsedConnection(null, errors, warnings);
        }
        return new ParsedConnection(new ConnectionSettings(
                trimTrailingSlashes(baseUrl), apiKey == null ? "" : apiKey.trim(), model.trim()),
                errors, warnings);
    }

    public static Parsed parse(JsonNode body) {
        ParsedConnection connection = parseConnection(body);
        List<String> errors = new ArrayList<>(connection.errors());
        List<String> warnings = new ArrayList<>(connection.warnings());

        List<ReplyRecord> replies = parseReplies(body.path("replies"), errors, warnings);
        List<GroundTruthRecord> groundTruth = parseGroundTruth(
                firstPresent(body, "ground_truth", "groundTruth"), errors, warnings);

        if (!errors.isEmpty()) {
            return new Parsed(null, errors, warnings);
        }
        ConnectionSettings settings = connection.settings();
        return new Parsed(new BatchDetectionRequest(
                settings.baseUrl(), settings.apiKey(), settings.model(), replies, groundTruth),
                errors, warnings);
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
                errors.add(at + " 不是对象（当前是" + typeName(item) + "）。每条回复必须是 {...} 包裹的对象。");
                continue;
            }

            // 四个字段【一次查完】，缺哪些一次列出来。
            // 不能查到一个问题就 continue —— 那样同一个对象里还缺别的字段就不会被告知，
            // 用户得反复提交、逐个试错。
            List<String> missing = new ArrayList<>();
            List<String> wrongType = new ArrayList<>();
            String id = requireText(item, "id", missing, wrongType);
            String question = requireText(item, "user_question", missing, wrongType);
            String reply = requireText(item, "system_reply", missing, wrongType);
            String kb = requireText(item, "knowledge_base", missing, wrongType);

            String problem = describeProblems(missing, wrongType);
            if (problem != null) {
                errors.add(at + "：" + problem);
                continue;
            }

            if (!seenIds.add(id)) {
                warnings.add(at + " 的 id \"" + id + "\" 与前面的重复，报告中会难以区分。");
            }
            replies.add(new ReplyRecord(id, question, reply, kb));
        }
        return replies;
    }

    /**
     * 检查一个必填的字符串字段，把问题记到对应的清单里。
     *
     * <p>不立刻返回也不抛异常——调用方要的是"这个对象一共有几处不合格"，
     * 而不是"第一处是哪儿"。
     *
     * @return 字段值；不合格时返回空串（调用方会因清单非空而跳过这一条）
     */
    private static String requireText(JsonNode item, String field,
                                      List<String> missing, List<String> wrongType) {
        JsonNode value = item.path(field);
        if (value.isMissingNode() || value.isNull()) {
            missing.add(field);
            return "";
        }
        if (!value.isString()) {
            wrongType.add(field + "（当前是" + typeName(value) + "）");
            return "";
        }
        String text = value.asString();
        if (text.isBlank()) {
            missing.add(field + "（空字符串）");
            return "";
        }
        return text;
    }

    /**
     * 把一个对象的字段问题拼成一句话。
     *
     * <p>缺多个字段时合并成"缺少必填字段 a、b、c"，而不是逐条重复"缺少必填字段"——
     * 一个对象缺四个字段时，后者读起来是一团噪音。
     *
     * @return 描述文本；没有问题则返回 null
     */
    private static String describeProblems(List<String> missing, List<String> wrongType) {
        if (missing.isEmpty() && wrongType.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) {
            parts.add("缺少必填字段 " + String.join("、", missing));
        }
        if (!wrongType.isEmpty()) {
            parts.add("这些字段必须是字符串：" + String.join("、", wrongType));
        }
        return String.join("；", parts) + "。";
    }

    /** 把节点类型翻译成中文，报错时比 "VALUE_NUMBER" 之类好懂得多。 */
    private static String typeName(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isArray()) {
            return "数组";
        }
        if (node.isObject()) {
            return "对象";
        }
        if (node.isString()) {
            return "字符串";
        }
        if (node.isNumber()) {
            return "数字";
        }
        if (node.isBoolean()) {
            return "布尔值";
        }
        return node.getNodeType().toString();
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
                errors.add(at + " 不是对象（当前是" + typeName(item) + "）。每条标注必须是 {...} 包裹的对象。");
                continue;
            }

            // 与 replies 一样：必填字段一次查完。
            // hallucination_type 与 detail 是可选的——判为干净的回复没有类型可言。
            List<String> missing = new ArrayList<>();
            List<String> wrongType = new ArrayList<>();
            String id = requireText(item, "id", missing, wrongType);

            JsonNode flag = item.path("is_hallucination");
            if (flag.isMissingNode() || flag.isNull()) {
                missing.add("is_hallucination");
            } else if (!flag.isBoolean()) {
                wrongType.add("is_hallucination（当前是" + typeName(flag) + "，必须是 true 或 false）");
            }

            String problem = describeProblems(missing, wrongType);
            if (problem != null) {
                errors.add(at + "：" + problem);
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
