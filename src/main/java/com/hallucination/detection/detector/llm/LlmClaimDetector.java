package com.hallucination.detection.detector.llm;

import com.hallucination.detection.detector.Detector;
import com.hallucination.detection.detector.TaxonomyRules;
import com.hallucination.detection.model.Claim;
import com.hallucination.detection.model.ClaimVerdict;
import com.hallucination.detection.model.DetectionResult;
import com.hallucination.detection.model.HallucinationType;
import com.hallucination.detection.model.ReplyRecord;
import com.hallucination.detection.model.Severity;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 主检测器的 LLM 阶段：把回复拆成断言，逐条对知识库核对，再聚合成整条回复的结论。
 *
 * <p>拆到断言粒度是为了抓住"部分正确"的回复：一条回复可能同时说对了 A、说错了 B，
 * 整体判定会给出"基本正确"的结论从而漏检，逐条核对则不会。
 *
 * <p>解析失败时重试一次。模型偶尔会在 JSON 外面裹一层解释性文字或代码块标记，
 * 重试前先把这些剥掉——这是对付大模型输出格式漂移的常规手段。
 *
 * <p><b>两次都失败时抛 {@link LlmDetectionException}，不降级成"判为干净"。</b>
 * 见该异常类的说明：静默降级会产出看起来正常、实际全错的报告。
 */
public class LlmClaimDetector implements Detector {

    public static final String NAME = "llm-claim";

    private static final int MAX_ATTEMPTS = 2;

    private final ChatModel chatModel;
    private final TaxonomyRules taxonomy;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClaimDetector(ChatModel chatModel, TaxonomyRules taxonomy) {
        this.chatModel = chatModel;
        this.taxonomy = taxonomy;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DetectionResult detect(ReplyRecord reply) {
        List<Claim> claims = verifyClaims(reply);

        List<Claim> hallucinated = claims.stream().filter(c -> c.verdict().isHallucinatory()).toList();
        if (hallucinated.isEmpty()) {
            return DetectionResult.clean(reply.id(), NAME,
                    "共核对 " + claims.size() + " 条断言，均与知识库一致", claims);
        }

        String evidenceText = hallucinated.stream().map(Claim::text).collect(Collectors.joining("；"));
        HallucinationType type = taxonomy.classifyByKeywords(reply.knowledgeBase(), evidenceText);
        // 定级看的是【被判为幻觉的那几条断言】在说什么，而不是整条回复。
        // 一条回复里可能同时有正常内容和幻觉内容，按整条定级会被无关内容带偏。
        // 同时并上回复原文只是兜底：模型复述断言时可能改写过措辞。
        Severity severity = taxonomy.severityOf(type, evidenceText + " " + reply.systemReply());

        String reason = "共核对 " + claims.size() + " 条断言，其中 " + hallucinated.size()
                + " 条无知识库依据或与之冲突：" + evidenceText;

        return DetectionResult.hallucinated(reply.id(), NAME, type, severity, reason, claims);
    }

    // ---------------------------------------------------------------------

    /** 调用模型并解析断言。失败即抛出，绝不返回"没有幻觉"。 */
    private List<Claim> verifyClaims(ReplyRecord reply) {
        String prompt = Prompts.claimVerification(reply);

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ChatResponse response = chatModel.call(new Prompt(prompt));
                return parseClaims(response.getResult().getOutput().getText());
            } catch (RuntimeException e) {
                lastFailure = e;
            }
        }

        throw new LlmDetectionException(
                "案例 " + reply.id() + " 的模型调用失败，已重试 " + MAX_ATTEMPTS + " 次。"
                        + "检测结论不可信，因此中止运行，而不是把它当成「没有幻觉」。"
                        + "原因：" + lastFailure.getMessage(), lastFailure);
    }

    private List<Claim> parseClaims(String raw) {
        JsonNode array = mapper.readTree(stripCodeFence(raw));
        if (!array.isArray()) {
            throw new IllegalStateException("模型未返回 JSON 数组：" + abbreviate(raw));
        }

        List<Claim> claims = new ArrayList<>();
        for (JsonNode node : array) {
            String text = node.path("claim").asString("");
            String verdictText = node.path("verdict").asString("");
            String evidence = node.path("evidence").asString("");
            if (text.isBlank() || verdictText.isBlank()) {
                continue;
            }
            claims.add(Claim.of(text, parseVerdict(verdictText), evidence));
        }
        if (claims.isEmpty()) {
            throw new IllegalStateException("模型返回的断言数组为空：" + abbreviate(raw));
        }
        return claims;
    }

    private static ClaimVerdict parseVerdict(String text) {
        try {
            return ClaimVerdict.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("无法识别的 verdict：" + text);
        }
    }

    /** 剥掉模型可能加上的 ```json 代码块围栏。 */
    static String stripCodeFence(String raw) {
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return text;
        }
        return text.substring(firstNewline + 1, lastFence).trim();
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
