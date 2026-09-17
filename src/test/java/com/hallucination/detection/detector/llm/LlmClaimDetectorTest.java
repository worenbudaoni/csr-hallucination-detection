package com.hallucination.detection.detector.llm;

import com.hallucination.detection.config.RuleProperties;
import com.hallucination.detection.detector.TaxonomyRules;
import com.hallucination.detection.model.Claim;
import com.hallucination.detection.model.ClaimVerdict;
import com.hallucination.detection.model.DetectionResult;
import com.hallucination.detection.model.HallucinationType;
import com.hallucination.detection.model.ReplyRecord;
import com.hallucination.detection.model.Severity;
import com.hallucination.detection.support.StubChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 检测阶段的逻辑验证，用替身模型驱动，不联网。
 *
 * <p>验证的是"拿到模型输出之后怎么处理"：解析、聚合、分类、定级，以及
 * <b>模型不可用时怎么处理</b>。至于模型判得准不准，那不是单元测试能回答的问题。
 */
class LlmClaimDetectorTest {

    private static final RuleProperties RULES = rules();

    private static RuleProperties rules() {
        RuleProperties p = new RuleProperties();
        p.setCapabilityAbsenceMarkers(List.of("未开通"));
        p.setInfoAbsenceMarkers(List.of("未收录"));
        p.setExistenceNegationStoplist(List.of("无添加"));
        p.setActionAssertionPattern("已经为您办妥");
        p.setAffirmativeAssertionPattern("可以的");
        p.setProhibitionPattern("禁止外传");
        p.setProhibitionSubject("编号");
        p.setAddressLikePattern("[^，。；]{1,8}省[^，。；]{1,8}市");
        p.setPostcodeLikePattern("邮\\s*编\\s*\\d{6}");
        p.setFactKeywords(List.of("规格"));
        p.setPolicyKeywords(List.of("资费"));
        p.setSeverityEscalationKeywords(List.of("孕期"));
        return p;
    }

    private static LlmClaimDetector detectorWith(StubChatModel model) {
        return new LlmClaimDetector(model, new TaxonomyRules(RULES));
    }

    private static ReplyRecord reply() {
        return new ReplyRecord("case", "问", "答", "知识库内容");
    }

    // ---------------------------------------------------------------------

    /** 模型的断言清单原样落到证据链上，一条不丢。 */
    @Test
    void carriesEveryClaimIntoTheEvidenceChain() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> """
                [
                  {"claim":"断言甲","verdict":"SUPPORTED","evidence":"知识库甲"},
                  {"claim":"断言乙","verdict":"CONSISTENT_HEDGED","evidence":"知识库乙"},
                  {"claim":"断言丙","verdict":"CONTRADICTED","evidence":"知识库丙"}
                ]
                """));

        DetectionResult result = detector.detect(reply());

        assertEquals(3, result.claims().size(), "三条断言都应保留");
        assertEquals(1, result.hallucinatedClaims().size(), "只有 CONTRADICTED 那条构成幻觉证据");
        assertTrue(result.hallucination());
    }

    /** 全部断言有据时判为干净，且证据链仍完整保留。 */
    @Test
    void cleanWhenEveryClaimIsSupported() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> """
                [
                  {"claim":"断言甲","verdict":"SUPPORTED","evidence":""},
                  {"claim":"断言乙","verdict":"CONSISTENT_HEDGED","evidence":""}
                ]
                """));

        DetectionResult result = detector.detect(reply());

        assertFalse(result.hallucination());
        assertEquals(HallucinationType.NONE, result.type());
        assertEquals(Severity.NONE, result.severity());
        assertEquals(2, result.claims().size());
    }

    /** UNSUPPORTED 与 CONTRADICTED 一样构成幻觉。 */
    @Test
    void unsupportedIsAlsoHallucinatory() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> """
                [{"claim":"断言甲","verdict":"UNSUPPORTED","evidence":""}]
                """));

        assertTrue(detector.detect(reply()).hallucination());
    }

    /** 类型与严重度由命中的断言文本决定，高危词把严重度抬到 S1。 */
    @Test
    void escalatesSeverityWhenAHallucinatedClaimHitsEscalationKeywords() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> """
                [{"claim":"关于孕期的说明","verdict":"CONTRADICTED","evidence":""}]
                """));

        DetectionResult result = detector.detect(reply());

        assertTrue(result.hallucination());
        assertEquals(Severity.S1, result.severity(), "命中高危词应抬到 S1");
    }

    // ---------------------------------------------------------------------
    // 模型不可用时的行为——这是本类最重要的一组断言
    // ---------------------------------------------------------------------

    /**
     * 模型调用失败时<b>必须抛出</b>，绝不能返回"没有幻觉"。
     *
     * <p>如果这里返回干净，模型一挂整批数据都会被判成干净，
     * 产出一份格式完整、指标漂亮、但完全错误的报告，而且毫无迹象。
     * 这是本工具最危险的一种静默故障，必须有测试守住。
     */
    @Test
    void throwsInsteadOfSilentlyReportingClean() {
        LlmClaimDetector detector = detectorWith(StubChatModel.failing("连接被拒绝"));

        LlmDetectionException e = assertThrows(LlmDetectionException.class,
                () -> detector.detect(reply()));

        assertTrue(e.getMessage().contains("case"), "报错信息应指明是哪个案例：" + e.getMessage());
        assertTrue(e.getMessage().contains("连接被拒绝"), "应带上原始失败原因：" + e.getMessage());
    }

    /** 模型返回无法解析的内容时，同样不能降级成"判为干净"。 */
    @Test
    void throwsWhenTheModelReturnsUnparseableOutput() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> "我不知道该怎么回答这个问题。"));

        assertThrows(LlmDetectionException.class, () -> detector.detect(reply()));
    }

    /** 模型返回空数组说明没按约定输出，同样不可信。 */
    @Test
    void throwsWhenTheModelReturnsAnEmptyClaimArray() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> "[]"));

        assertThrows(LlmDetectionException.class, () -> detector.detect(reply()));
    }

    // ---------------------------------------------------------------------

    /** 模型偶尔会用代码块围栏包住 JSON，这属于格式漂移，应能剥掉后正常解析。 */
    @Test
    void stripsMarkdownCodeFence() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> """
                ```json
                [{"claim":"断言甲","verdict":"SUPPORTED","evidence":""}]
                ```
                """));

        DetectionResult result = detector.detect(reply());

        assertFalse(result.hallucination());
        assertEquals(1, result.claims().size());
    }

    /** 大小写与前后空格不应影响 verdict 解析。 */
    @Test
    void parsesVerdictCaseInsensitively() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> """
                [{"claim":"断言甲","verdict":"  supported  ","evidence":""}]
                """));

        DetectionResult result = detector.detect(reply());

        assertFalse(result.hallucination());
        assertEquals(ClaimVerdict.SUPPORTED, result.claims().get(0).verdict());
    }

    /** 缺字段的条目应被跳过，而不是让整次解析失败。 */
    @Test
    void skipsIncompleteClaimEntries() {
        LlmClaimDetector detector = detectorWith(new StubChatModel(prompt -> """
                [
                  {"claim":"断言甲","verdict":"SUPPORTED","evidence":""},
                  {"claim":"","verdict":"SUPPORTED","evidence":""},
                  {"verdict":"SUPPORTED","evidence":""}
                ]
                """));

        List<Claim> claims = detector.detect(reply()).claims();

        assertEquals(1, claims.size(), "只有字段完整的条目应当保留");
    }
}
