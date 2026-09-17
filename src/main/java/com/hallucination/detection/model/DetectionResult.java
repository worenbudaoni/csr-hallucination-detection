package com.hallucination.detection.model;

import java.util.List;

/**
 * 单个案例的检测结论。
 *
 * @param caseId       案例编号
 * @param detector     产出该结论的检测器名称（hybrid / rule / llm-claim / 各基线）
 * @param hallucination 是否判定为幻觉
 * @param type         幻觉类型，非幻觉时为 {@link HallucinationType#NONE}
 * @param severity     严重度，非幻觉时为 {@link Severity#NONE}
 * @param reason       判定理由，用于人工复核
 * @param claims       断言级证据链。空列表表示该检测器没有做断言拆分（如纯规则路径）
 */
public record DetectionResult(
        String caseId,
        String detector,
        boolean hallucination,
        HallucinationType type,
        Severity severity,
        String reason,
        List<Claim> claims) {

    public static DetectionResult clean(String caseId, String detector, String reason) {
        return clean(caseId, detector, reason, List.of());
    }

    /**
     * 判为干净，但保留断言级证据链。
     *
     * <p>证据链不是只在判出幻觉时才有价值——"这条回复被拆成几条断言、各自核对结论如何"
     * 是人工复核判定是否可靠的依据。丢掉它，干净的结论就变成了一个无法追溯的黑盒。
     */
    public static DetectionResult clean(String caseId, String detector, String reason,
                                        List<Claim> claims) {
        return new DetectionResult(caseId, detector, false,
                HallucinationType.NONE, Severity.NONE, reason, claims);
    }

    public static DetectionResult hallucinated(String caseId, String detector,
                                               HallucinationType type, Severity severity,
                                               String reason, List<Claim> claims) {
        return new DetectionResult(caseId, detector, true, type, severity, reason, claims);
    }

    /** 被判为幻觉的那些断言，用于展示证据链。 */
    public List<Claim> hallucinatedClaims() {
        return claims.stream().filter(c -> c.verdict().isHallucinatory()).toList();
    }
}
