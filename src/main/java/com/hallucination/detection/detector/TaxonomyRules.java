package com.hallucination.detection.detector;

import com.hallucination.detection.config.RuleProperties;
import com.hallucination.detection.model.HallucinationType;
import com.hallucination.detection.model.Severity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 幻觉类型与严重度的判定规则。
 *
 * <p>规则路径和 LLM 路径共用这里的判据，避免两条路各判各的、结论对不上。
 *
 * <p>严重度的设计是"类型给基线档次，规则上调，只升不降"：
 * 模型对严重度的判断不稳定，容易把高危案例说成"轻微"，所以不允许它下调。
 *
 * <p>判据词表全部来自 {@link RuleProperties}，不含任何写死的领域词汇。
 */
@Component
public class TaxonomyRules {

    private final RuleProperties properties;

    public TaxonomyRules(RuleProperties properties) {
        this.properties = properties;
    }

    /** 按关键词把一条幻觉断言归到某个类型。 */
    public HallucinationType classifyByKeywords(String knowledgeBase, String claimText) {
        String context = knowledgeBase + " " + claimText;
        if (containsAny(context, properties.getPolicyKeywords())) {
            return HallucinationType.POLICY;
        }
        if (containsAny(context, properties.getFactKeywords())) {
            return HallucinationType.FACT;
        }
        return HallucinationType.FACT;
    }

    /**
     * 在类型基线严重度上做上调——只升不降。
     *
     * @param type      已判定的类型，提供基线档次
     * @param claimText 幻觉断言原文，用于匹配高危关键词
     */
    public Severity severityOf(HallucinationType type, String claimText) {
        Severity baseline = type.baselineSeverity();
        if (baseline == Severity.NONE) {
            return Severity.NONE;
        }
        if (containsAny(claimText, properties.getSeverityEscalationKeywords())) {
            return baseline.worse(Severity.S1);
        }
        return baseline;
    }

    static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
