package com.hallucination.detection.batch;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量检测请求的解析与校验。
 *
 * <p>这是接口的门面：任何人都能直接调它，所以这里的规则必须独立成立——
 * 页面上的校验只是让用户早点看到问题，不能当作已经拦过一遍。
 */
class BatchRequestParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static BatchRequestParser.Parsed parse(String json) {
        JsonNode node = MAPPER.readTree(json);
        return BatchRequestParser.parse(node);
    }

    private static final String VALID_REPLY =
            """
            {"id":"a","user_question":"问","system_reply":"答","knowledge_base":"库"}
            """;

    private static String bodyWith(String overrides) {
        return """
                {
                  "base_url": "https://api.deepseek.com/v1",
                  "api_key": "sk-test",
                  "model": "deepseek-chat",
                  "replies": [%s]
                  %s
                }
                """.formatted(VALID_REPLY, overrides);
    }

    // ---------------------------------------------------------------------
    // 正常路径
    // ---------------------------------------------------------------------

    @Test
    void parsesAValidRequest() {
        BatchRequestParser.Parsed parsed = parse(bodyWith(""));

        assertTrue(parsed.ok(), String.join("; ", parsed.errors()));
        BatchDetectionRequest request = parsed.request();
        assertEquals("https://api.deepseek.com/v1", request.baseUrl());
        assertEquals("deepseek-chat", request.model());
        assertEquals(1, request.replies().size());
        assertEquals("a", request.replies().get(0).id());
        assertFalse(request.hasGroundTruth(), "没给标注时应当为空");
    }

    @Test
    void parsesGroundTruthWhenPresent() {
        BatchRequestParser.Parsed parsed = parse(bodyWith(
                ", \"ground_truth\": [{\"id\":\"a\",\"is_hallucination\":true,"
                        + "\"hallucination_type\":\"政策编造\",\"detail\":\"编造\"}]"));

        assertTrue(parsed.ok(), String.join("; ", parsed.errors()));
        assertTrue(parsed.request().hasGroundTruth());
        assertEquals("政策编造", parsed.request().groundTruth().get(0).hallucinationType());
    }

    @Test
    void acceptsCamelCaseFieldNames() {
        BatchRequestParser.Parsed parsed = parse("""
                {"baseUrl":"https://api.deepseek.com/v1","apiKey":"sk","model":"m",
                 "replies":[%s]}
                """.formatted(VALID_REPLY));

        assertTrue(parsed.ok(), String.join("; ", parsed.errors()));
    }

    @Test
    void trimsTrailingSlashesFromBaseUrl() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://api.deepseek.com/v1///","api_key":"sk","model":"m",
                 "replies":[%s]}
                """.formatted(VALID_REPLY));

        assertEquals("https://api.deepseek.com/v1", parsed.request().baseUrl());
    }

    // ---------------------------------------------------------------------
    // 必填项
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingReplies() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m"}
                """);

        assertFalse(parsed.ok());
        assertNull(parsed.request());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("replies")));
    }

    @Test
    void rejectsEmptyReplies() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m","replies":[]}
                """);

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("空数组")));
    }

    @Test
    void rejectsNonArrayReplies() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m","replies":{"a":1}}
                """);

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("必须是数组")));
    }

    @Test
    void rejectsMissingModel() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","replies":[%s]}
                """.formatted(VALID_REPLY));

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("model")));
    }

    // ---------------------------------------------------------------------
    // base_url
    // ---------------------------------------------------------------------

    /** 缺协议的地址是最常见的写法错误，报错要说清楚该怎么改，而不是打印 "null"。 */
    @Test
    void explainsMissingSchemeClearly() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"api.deepseek.com/v1","api_key":"sk","model":"m","replies":[%s]}
                """.formatted(VALID_REPLY));

        assertFalse(parsed.ok());
        String error = parsed.errors().stream().filter(e -> e.contains("base_url")).findFirst().orElse("");
        assertTrue(error.contains("http://"), "报错里应当给出正确写法：" + error);
        assertFalse(error.contains("null"), "不该出现 null 这种没有信息量的字样：" + error);
    }

    @Test
    void rejectsNonHttpScheme() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"ftp://x.com/v1","api_key":"sk","model":"m","replies":[%s]}
                """.formatted(VALID_REPLY));

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("协议")));
    }

    /** 缺版本前缀会直接 404，这是最容易踩的坑之一，必须给出警告。 */
    @Test
    void warnsWhenBaseUrlHasNoPath() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://api.openai.com","api_key":"sk","model":"m","replies":[%s]}
                """.formatted(VALID_REPLY));

        assertTrue(parsed.ok(), String.join("; ", parsed.errors()));
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("路径")),
                String.join("; ", parsed.warnings()));
    }

    @Test
    void warnsWhenBaseUrlAlreadyContainsCompletionsPath() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://api.deepseek.com/v1/chat/completions","api_key":"sk",
                 "model":"m","replies":[%s]}
                """.formatted(VALID_REPLY));

        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("chat/completions")),
                String.join("; ", parsed.warnings()));
    }

    @Test
    void warnsAboutPlainHttpForRemoteHosts() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"http://api.example.com/v1","api_key":"sk","model":"m","replies":[%s]}
                """.formatted(VALID_REPLY));

        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("明文")));
    }

    // ---------------------------------------------------------------------
    // api_key
    // ---------------------------------------------------------------------

    @Test
    void rejectsMissingApiKeyForRemoteEndpoints() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://api.deepseek.com/v1","model":"m","replies":[%s]}
                """.formatted(VALID_REPLY));

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("api_key")));
    }

    /** 本地模型不需要密钥，不该因此被拦下。 */
    @Test
    void allowsMissingApiKeyForLocalEndpoints() {
        for (String host : new String[]{"http://localhost:11434/v1", "http://127.0.0.1:8000/v1"}) {
            BatchRequestParser.Parsed parsed = parse("""
                    {"base_url":"%s","model":"m","replies":[%s]}
                    """.formatted(host, VALID_REPLY));

            assertTrue(parsed.ok(), host + " 应当允许空 key：" + String.join("; ", parsed.errors()));
            assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("本地模型")),
                    host + " 应当提示本地模型可留空");
        }
    }

    // ---------------------------------------------------------------------
    // replies 内部
    // ---------------------------------------------------------------------

    @Test
    void rejectsReplyWithoutId() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m",
                 "replies":[{"system_reply":"答","knowledge_base":"库"}]}
                """);

        assertFalse(parsed.ok());
        // 下标从 0 起，和 JSON 数组对齐
        assertTrue(parsed.errors().stream().anyMatch(e -> e.startsWith("replies[0]")),
                String.join("; ", parsed.errors()));
    }

    @Test
    void rejectsReplyWithoutSystemReply() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m",
                 "replies":[{"id":"a","knowledge_base":"库"}]}
                """);

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("system_reply")));
    }

    /** 四个字段一个都不能少。没有知识库就无从核对，这条数据没有检测价值。 */
    @Test
    void rejectsRepliesMissingAnyRequiredField() {
        for (String missing : new String[]{"id", "user_question", "system_reply", "knowledge_base"}) {
            StringBuilder json = new StringBuilder("{\"base_url\":\"https://x.com/v1\","
                    + "\"api_key\":\"sk\",\"model\":\"m\",\"replies\":[{");
            boolean first = true;
            for (String field : new String[]{"id", "user_question", "system_reply", "knowledge_base"}) {
                if (field.equals(missing)) {
                    continue;
                }
                if (!first) {
                    json.append(",");
                }
                json.append("\"").append(field).append("\":\"值\"");
                first = false;
            }
            json.append("}]}");

            BatchRequestParser.Parsed parsed = parse(json.toString());
            assertFalse(parsed.ok(), "缺 " + missing + " 应当被拒");
            assertTrue(parsed.errors().stream().anyMatch(e -> e.contains(missing)),
                    "报错里应当指明缺的是 " + missing + "：" + String.join("; ", parsed.errors()));
        }
    }

    /**
     * 同一个对象里缺多个字段时，要一次全报出来。
     *
     * <p>早先的实现查到一个问题就 continue，用户得反复提交、逐个试错——
     * 这条测试就是守住那个坑不再回来。
     */
    @Test
    void reportsEveryMissingFieldOfTheSameObjectAtOnce() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m",
                 "replies":[{"id":"a"}]}
                """);

        assertFalse(parsed.ok());
        assertEquals(1, parsed.errors().size(),
                "一个对象的问题应当合并成一条错误，实际：" + String.join(" | ", parsed.errors()));

        String error = parsed.errors().get(0);
        assertTrue(error.startsWith("replies[0]"), error);
        assertTrue(error.contains("user_question"), error);
        assertTrue(error.contains("system_reply"), error);
        assertTrue(error.contains("knowledge_base"), error);
        assertFalse(error.contains("缺少必填字段 id"), "id 是有值的，不该被报缺：" + error);
    }

    /** 字段在但类型不对，等同于不合法——不能读成空串放过去。 */
    @Test
    void rejectsFieldsOfTheWrongType() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m",
                 "replies":[{"id":123,"user_question":"问","system_reply":"答","knowledge_base":"库"}]}
                """);

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("id") && e.contains("字符串")),
                String.join("; ", parsed.errors()));
    }

    /** 空字符串不算填了。 */
    @Test
    void rejectsBlankFieldValues() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m",
                 "replies":[{"id":"a","user_question":"   ","system_reply":"答","knowledge_base":"库"}]}
                """);

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("user_question")),
                String.join("; ", parsed.errors()));
    }

    @Test
    void warnsAboutDuplicateIds() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m",
                 "replies":[%s,
                            {"id":"a","user_question":"问2","system_reply":"答2","knowledge_base":"库"}]}
                """.formatted(VALID_REPLY));

        assertTrue(parsed.ok(), "重复 id 不该导致整体被拒：" + String.join("; ", parsed.errors()));
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("重复")));
    }

    @Test
    void skipsNonObjectReplyItemsAndReportsThem() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":"https://x.com/v1","api_key":"sk","model":"m",
                 "replies":["字符串", %s]}
                """.formatted(VALID_REPLY));

        assertFalse(parsed.ok(), "数组里有非对象元素应被视为错误");
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("不是对象")));
    }

    // ---------------------------------------------------------------------
    // ground_truth 内部
    // ---------------------------------------------------------------------

    @Test
    void rejectsNonBooleanHallucinationFlag() {
        BatchRequestParser.Parsed parsed = parse(bodyWith(
                ", \"ground_truth\": [{\"id\":\"a\",\"is_hallucination\":\"yes\"}]"));

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("is_hallucination")),
                String.join("; ", parsed.errors()));
    }

    @Test
    void rejectsGroundTruthWithoutId() {
        BatchRequestParser.Parsed parsed = parse(bodyWith(
                ", \"ground_truth\": [{\"is_hallucination\":true}]"));

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("ground_truth[0]")));
    }

    @Test
    void rejectsGroundTruthThatIsNotAnArray() {
        BatchRequestParser.Parsed parsed = parse(bodyWith(", \"ground_truth\": {\"a\":1}"));

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.contains("ground_truth")));
    }

    /** 全干净的标注也是合法输入——null 类型不能被当成错误。 */
    @Test
    void acceptsGroundTruthWithNullType() {
        BatchRequestParser.Parsed parsed = parse(bodyWith(
                ", \"ground_truth\": [{\"id\":\"a\",\"is_hallucination\":false,"
                        + "\"hallucination_type\":null}]"));

        assertTrue(parsed.ok(), String.join("; ", parsed.errors()));
        assertNull(parsed.request().groundTruth().get(0).hallucinationType());
    }

    // ---------------------------------------------------------------------

    /** 错误要一次列全，不能让调用方一个一个试。 */
    @Test
    void reportsEveryProblemAtOnce() {
        BatchRequestParser.Parsed parsed = parse("""
                {"base_url":":::","model":"",
                 "replies":[{"id":"","system_reply":"答"},{"id":"b"}],
                 "ground_truth":[{"id":"b","is_hallucination":"maybe"}]}
                """);

        assertFalse(parsed.ok());
        assertTrue(parsed.errors().size() >= 5,
                "应当一次列出所有问题，实际只有 " + parsed.errors().size() + " 条："
                        + String.join("; ", parsed.errors()));
    }

    @Test
    void returnsNullRequestWhenThereAreErrors() {
        assertNull(parse("{}").request());
        assertNotNull(parse(bodyWith("")).request());
    }

    // ---------------------------------------------------------------------
    // 只解析连接信息（「测试连接」用）
    // ---------------------------------------------------------------------

    /** 探测连通性只需要三项，不该因为缺 replies 而被拒。 */
    @Test
    void connectionParsingDoesNotRequireReplies() {
        BatchRequestParser.ParsedConnection parsed = BatchRequestParser.parseConnection(
                MAPPER.readTree("""
                        {"base_url":"https://api.deepseek.com/v1","api_key":"sk","model":"deepseek-chat"}
                        """));

        assertTrue(parsed.ok(), String.join("; ", parsed.errors()));
        assertEquals("https://api.deepseek.com/v1", parsed.settings().baseUrl());
        assertEquals("deepseek-chat", parsed.settings().model());
    }

    /** 连接信息的校验规则与批量检测完全一致——两处各写一套迟早会分叉。 */
    @Test
    void connectionParsingAppliesTheSameRules() {
        assertFalse(BatchRequestParser.parseConnection(MAPPER.readTree("""
                {"base_url":"https://x.com/v1","model":"m"}
                """)).ok(), "云端端点缺 key 应当被拒");

        assertTrue(BatchRequestParser.parseConnection(MAPPER.readTree("""
                {"base_url":"http://localhost:11434/v1","model":"m"}
                """)).ok(), "本地端点应允许空 key");

        assertFalse(BatchRequestParser.parseConnection(MAPPER.readTree("""
                {"base_url":"https://x.com/v1","api_key":"sk"}
                """)).ok(), "缺模型名应当被拒");

        assertFalse(BatchRequestParser.parseConnection(MAPPER.readTree("""
                {"base_url":"api.deepseek.com/v1","api_key":"sk","model":"m"}
                """)).ok(), "缺协议前缀应当被拒");
    }

    @Test
    void connectionParsingKeepsWarnings() {
        BatchRequestParser.ParsedConnection parsed = BatchRequestParser.parseConnection(
                MAPPER.readTree("""
                        {"base_url":"https://api.openai.com","api_key":"sk","model":"gpt-4o-mini"}
                        """));

        assertTrue(parsed.ok(), String.join("; ", parsed.errors()));
        assertTrue(parsed.warnings().stream().anyMatch(w -> w.contains("路径")),
                "缺版本前缀的警告应当保留下来：" + String.join("; ", parsed.warnings()));
    }

    /** 连接信息也要规整尾部斜杠，否则探测用的地址会和检测用的不一致。 */
    @Test
    void connectionParsingTrimsTrailingSlashes() {
        BatchRequestParser.ParsedConnection parsed = BatchRequestParser.parseConnection(
                MAPPER.readTree("""
                        {"base_url":"https://api.deepseek.com/v1/","api_key":"sk","model":"m"}
                        """));

        assertEquals("https://api.deepseek.com/v1", parsed.settings().baseUrl());
    }
}
