package com.hallucination.detection.detector;

import com.hallucination.detection.config.RuleProperties;
import com.hallucination.detection.model.Claim;
import com.hallucination.detection.model.ClaimVerdict;
import com.hallucination.detection.model.DetectionResult;
import com.hallucination.detection.model.HallucinationType;
import com.hallucination.detection.model.ReplyRecord;
import com.hallucination.detection.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则检测器：吃"知识库明示没有这个能力 / 没有这条信息"的案例。
 *
 * <p>这类案例有一个共同结构：<b>知识库给出了一个否定声明，而回复表现得像那个东西存在</b>。
 * 这个结构用确定性规则判比调 LLM 更稳、更便宜，也不会因模型抖动而漂移。
 *
 * <p><b>关键设计：判极性，不判关键词。</b>
 * 知识库写着"不支持货到付款"、回复也说"不支持货到付款"时，关键词命中但这是**正确**回复。
 * 所以任何一条规则都不能只看"回复里出现了 X 这个词"，必须同时确认
 * <b>回复没有一并否认</b>、并且<b>回复确实做出了肯定断言</b>。
 *
 * <p>本检测器有意不做语义推断，因此对"知识库写了正确值、回复写成别的值"这类冲突型幻觉
 * 无能为力——那正是 LLM 路径存在的理由。
 *
 * <p>所有判据词表来自 {@link RuleProperties}，可随业务话术调整而不改代码。
 */
@Component
public class RuleBasedDetector implements Detector {

    public static final String NAME = "rule";

    private final RuleProperties rules;
    private final TaxonomyRules taxonomy;

    public RuleBasedDetector(RuleProperties rules, TaxonomyRules taxonomy) {
        this.rules = rules;
        this.taxonomy = taxonomy;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DetectionResult detect(ReplyRecord reply) {
        String kb = nullToEmpty(reply.knowledgeBase());
        String answer = nullToEmpty(reply.systemReply());

        // 规则 1：知识库声明没有该能力，回复却声称执行了该动作 —— 能力越界
        DetectionResult capability = checkCapabilityOverreach(reply, kb, answer);
        if (capability != null) {
            return capability;
        }

        // 规则 2：知识库禁止披露某类信息，回复却直接给出了——不可逆的物流风险，S1
        DetectionResult prohibited = checkProhibitedDisclosure(reply, kb, answer);
        if (prohibited != null) {
            return prohibited;
        }

        // 规则 3：知识库声明"没有这条信息"，回复却做出了肯定断言
        DetectionResult absence = checkAbsenceAffirmed(reply, kb, answer);
        if (absence != null) {
            return absence;
        }

        // 三条规则都没命中。注意必须显式返回"干净"，不能把 null 透传出去
        return DetectionResult.clean(reply.id(), NAME,
                "未命中任何规则：知识库未声明该项能力或信息缺失");
    }

    // ---------------------------------------------------------------------

    private DetectionResult checkCapabilityOverreach(ReplyRecord reply, String kb, String answer) {
        String marker = firstPresent(kb, rules.getCapabilityAbsenceMarkers());
        if (marker == null) {
            return null;
        }
        Matcher action = rules.actionAssertion().matcher(answer);
        if (!action.find()) {
            // 知识库说没有这个能力，但回复也没声称做了——不构成能力越界
            return null;
        }
        String evidence = sentenceContaining(kb, marker);
        return DetectionResult.hallucinated(reply.id(), NAME,
                HallucinationType.CAPABILITY, Severity.S2,
                "知识库声明「" + marker + "」，回复却声称已执行该动作：" + action.group().trim(),
                List.of(Claim.of(action.group().trim(), ClaimVerdict.UNSUPPORTED, evidence)));
    }

    private DetectionResult checkProhibitedDisclosure(ReplyRecord reply, String kb, String answer) {
        if (!rules.prohibition().matcher(kb).find()) {
            return null;
        }
        String subject = rules.getProhibitionSubject();
        if (subject != null && !subject.isBlank() && !kb.contains(subject)) {
            // 禁止的是别的事，与本次披露无关
            return null;
        }
        boolean disclosed = rules.addressLike().matcher(answer).find()
                || rules.postcodeLike().matcher(answer).find();
        if (!disclosed) {
            return null;
        }
        return DetectionResult.hallucinated(reply.id(), NAME,
                HallucinationType.POLICY, Severity.S1,
                "知识库明令禁止口头披露该类信息，回复却直接给出了具体内容",
                List.of(Claim.of(firstMatch(answer), ClaimVerdict.CONTRADICTED,
                        sentenceContaining(kb, rules.getProhibitionPattern()))));
    }

    private DetectionResult checkAbsenceAffirmed(ReplyRecord reply, String kb, String answer) {
        String infoMarker = firstPresent(kb, rules.getInfoAbsenceMarkers());
        boolean existenceNegation = hasExistenceNegation(kb);
        if (infoMarker == null && !existenceNegation) {
            return null;
        }

        Matcher affirmative = rules.affirmativeAssertion().matcher(answer);
        if (!affirmative.find()) {
            // 知识库说没有，回复也没正面肯定——没有冲突
            return null;
        }

        HallucinationType type = infoMarker != null
                ? HallucinationType.FACT
                : taxonomy.classifyByKeywords(kb, answer);
        String evidence = infoMarker != null ? sentenceContaining(kb, infoMarker) : kb.trim();

        return DetectionResult.hallucinated(reply.id(), NAME, type, type.baselineSeverity(),
                "知识库声明该项信息/能力不存在，回复却以「" + affirmative.group() + "」作出肯定断言",
                List.of(Claim.of(affirmative.group() + "…", ClaimVerdict.UNSUPPORTED, evidence)));
    }

    // ---------------------------------------------------------------------

    /**
     * 知识库中是否存在"无 X"形式的存在否定。
     *
     * <p>必须排除构词用法，否则"7天无理由退货"这类知识库会被误读成"本店没有退货政策"。
     * "无"后紧跟左括号的写法（如"无（未接入…）"）交给能力规则处理，这里不算。
     */
    boolean hasExistenceNegation(String kb) {
        int index = kb.indexOf('无');
        while (index >= 0) {
            String window = kb.substring(index, Math.min(kb.length(), index + 6));
            boolean stoplisted = false;
            for (String stop : rules.getExistenceNegationStoplist()) {
                if (window.startsWith(stop)) {
                    stoplisted = true;
                    break;
                }
            }
            if (!stoplisted && !window.startsWith("无（") && !window.startsWith("无(")) {
                return true;
            }
            index = kb.indexOf('无', index + 1);
        }
        return false;
    }

    private static String firstPresent(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    /** 取出包含指定片段的那句话，作为判定证据。 */
    private static String sentenceContaining(String text, String fragment) {
        for (String sentence : text.split("[。；;\\n]")) {
            if (sentence.contains(fragment)) {
                return sentence.trim();
            }
        }
        return text.trim();
    }

    private String firstMatch(String answer) {
        Matcher address = rules.addressLike().matcher(answer);
        if (address.find()) {
            return address.group();
        }
        Matcher postcode = rules.postcodeLike().matcher(answer);
        return postcode.find() ? postcode.group() : answer;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
