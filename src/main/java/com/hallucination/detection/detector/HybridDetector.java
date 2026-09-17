package com.hallucination.detection.detector;

import com.hallucination.detection.model.DetectionResult;
import com.hallucination.detection.model.ReplyRecord;

/**
 * 主检测器：规则前置 + LLM 复核。
 *
 * <p>先用确定性规则吃掉"知识库明示没有这个能力/没有这条信息"的案例，命中了就直接返回；
 * 没命中才调 LLM 做断言级核对。这样安排的理由有三条：
 *
 * <ol>
 *   <li><b>规则更稳。</b>"知识库写着未接入物流查询接口、回复却说'我帮您查了'"这种信号，
 *       规则判定零抖动，而 LLM 在温度 0 下仍可能偶发漂移。</li>
 *   <li><b>规则更省。</b>20 条里有一半不用调 API。</li>
 *   <li><b>规则本身就是对照组。</b>规则路径对"知识库写了正确值、回复改成了别的值"
 *       这类冲突型幻觉必然漏检，LLM 路径能救回来——两者的差集恰好回答了"为什么需要模型"。</li>
 * </ol>
 */
public class HybridDetector implements Detector {

    public static final String NAME = "hybrid";

    private final RuleBasedDetector ruleDetector;
    private final Detector llmDetector;

    public HybridDetector(RuleBasedDetector ruleDetector, Detector llmDetector) {
        this.ruleDetector = ruleDetector;
        this.llmDetector = llmDetector;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public DetectionResult detect(ReplyRecord reply) {
        DetectionResult ruleResult = ruleDetector.detect(reply);
        if (ruleResult.hallucination()) {
            return ruleResult;
        }
        return llmDetector.detect(reply);
    }
}
