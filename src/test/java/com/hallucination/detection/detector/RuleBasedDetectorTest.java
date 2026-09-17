package com.hallucination.detection.detector;

import com.hallucination.detection.config.RuleProperties;
import com.hallucination.detection.model.DetectionResult;
import com.hallucination.detection.model.HallucinationType;
import com.hallucination.detection.model.ReplyRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则检测器的逻辑验证。
 *
 * <p><b>用测试内构造的合成用例，不读任何数据文件。</b>
 * 词表也是本测试自带的、与仓库里那份示例数据无关的词汇——
 * 这样测试验证的是"规则引擎逻辑对不对"，而不是"某批数据的答案记得牢不牢"。
 * 换一批业务数据时，这个测试不需要跟着改。
 */
class RuleBasedDetectorTest {

    // 本测试自己的词表。刻意与示例数据中的话术错开，避免产生"测试在背答案"的错觉。
    private static final RuleProperties RULES = syntheticRules();

    private final RuleBasedDetector detector = new RuleBasedDetector(RULES, new TaxonomyRules(RULES));

    private static RuleProperties syntheticRules() {
        RuleProperties p = new RuleProperties();
        p.setCapabilityAbsenceMarkers(List.of("本店未开通该服务"));
        p.setInfoAbsenceMarkers(List.of("资料中未收录"));
        p.setExistenceNegationStoplist(List.of("无添加", "无门槛"));
        p.setActionAssertionPattern("已经为您办妥|我们已代为处理");
        p.setAffirmativeAssertionPattern("已经开通|可以的");
        p.setProhibitionPattern("禁止外传|不得透露");
        p.setProhibitionSubject("编号");
        p.setAddressLikePattern("[^，。；]{1,8}省[^，。；]{1,8}市[^，。；]{1,6}区");
        p.setPostcodeLikePattern("邮\\s*编\\s*\\d{6}");
        p.setFactKeywords(List.of("规格", "材质"));
        p.setPolicyKeywords(List.of("资费", "周期"));
        p.setSeverityEscalationKeywords(List.of("孕期", "成分"));
        return p;
    }

    private static ReplyRecord reply(String question, String answer, String kb) {
        return new ReplyRecord("case", question, answer, kb);
    }

    // ---------------------------------------------------------------------
    // 规则一：能力越界
    // ---------------------------------------------------------------------

    @Test
    void flagsCapabilityOverreach() {
        DetectionResult result = detector.detect(reply(
                "能帮我改一下吗",
                "已经为您办妥，明天生效。",
                "本店未开通该服务，需转人工处理。"));

        assertTrue(result.hallucination(), "知识库说没开通、回复说已办妥，应判为幻觉");
        assertEquals(HallucinationType.CAPABILITY, result.type());
    }

    /** 知识库说没开通，回复也没声称做了——没有冲突，必须放行。 */
    @Test
    void passesWhenReplyDoesNotClaimTheAction() {
        DetectionResult result = detector.detect(reply(
                "能帮我改一下吗",
                "抱歉，这项服务目前无法在线办理。",
                "本店未开通该服务，需转人工处理。"));

        assertFalse(result.hallucination(), "回复没有声称执行动作，不构成能力越界");
    }

    // ---------------------------------------------------------------------
    // 规则二：禁止性披露
    // ---------------------------------------------------------------------

    @Test
    void flagsProhibitedDisclosure() {
        DetectionResult result = detector.detect(reply(
                "寄到哪里",
                "请寄到：江苏省南京市玄武区中山路100号，邮编210000。",
                "回寄编号由系统按订单生成后短信下发，禁止外传。"));

        assertTrue(result.hallucination(), "知识库禁止披露编号类信息，回复给出了具体地址，应判为幻觉");
        assertEquals(HallucinationType.POLICY, result.type());
        assertEquals(com.hallucination.detection.model.Severity.S1, result.severity(),
                "不可逆的物流失误属于高危");
    }

    /**
     * 知识库的禁止针对的是别的东西时，不应对本次披露判违规。
     *
     * <p>这里知识库里没有出现「编号」这个被禁止的对象，说明禁止条款约束的不是本类信息。
     */
    @Test
    void ignoresProhibitionAboutSomethingElse() {
        DetectionResult result = detector.detect(reply(
                "寄到哪里",
                "请寄到：江苏省南京市玄武区中山路100号。",
                "禁止外传内部培训材料。回寄地址以短信为准。"));

        assertFalse(result.hallucination(), "禁止的对象与本条披露无关，不应判违规");
    }

    // ---------------------------------------------------------------------
    // 规则三：知识库声明"没有这条信息"
    // ---------------------------------------------------------------------

    @Test
    void flagsAffirmativeClaimAboutMissingInfo() {
        DetectionResult result = detector.detect(reply(
                "支持这个功能吗",
                "可以的，我们支持。",
                "资料中未收录该功能。"));

        assertTrue(result.hallucination(), "知识库说没有收录，回复却肯定说支持，应判为幻觉");
    }

    /** 知识库说没有，回复也没正面肯定——没有冲突。 */
    @Test
    void passesWhenReplyIsAlsoNegative() {
        DetectionResult result = detector.detect(reply(
                "支持这个功能吗",
                "抱歉，这项我们查不到相关信息。",
                "资料中未收录该功能。"));

        assertFalse(result.hallucination(), "回复未作肯定断言，不应判为幻觉");
    }

    // ---------------------------------------------------------------------
    // 极性一致：关键词命中但意思是同向的，不能误判
    // ---------------------------------------------------------------------

    /**
     * 这是规则路径最容易写错的地方。
     * 知识库与回复说的是同一件事、方向一致时，关键词会命中，但这是**正确**回复。
     */
    @Test
    void passesWhenKnowledgeBaseAndReplyAgreeOnANegative() {
        DetectionResult result = detector.detect(reply(
                "能赊账吗",
                "本店不支持赊账，请选择在线支付。",
                "支付方式：在线支付。本店不支持赊账。"));

        assertFalse(result.hallucination(), "知识库与回复同向否认，属于一致，不得误报");
    }

    /**
     * "无"在中文里既可以是存在否定，也可以是构词成分。
     * "无添加"是构词，不能被读成"本店没有该商品"。
     */
    @Test
    void doesNotTreatCompoundWuAsExistenceNegation() {
        DetectionResult result = detector.detect(reply(
                "这款能退货吗",
                "可以的，凭订单号申请即可。",
                "本商品支持7天无添加退货。"));

        assertFalse(result.hallucination(), "“无添加”是构词而非存在否定，不得据此判幻觉");
    }

    // ---------------------------------------------------------------------
    // 兜底行为
    // ---------------------------------------------------------------------

    /** 未命中任何规则时必须显式返回"干净"，不能把 null 透传出去。 */
    @Test
    void alwaysReturnsAResult() {
        DetectionResult result = detector.detect(reply(
                "什么时候发货",
                "下单后三个工作日内发出。",
                "发货时效：三个工作日。"));

        assertFalse(result.hallucination());
        assertEquals(HallucinationType.NONE, result.type());
        assertTrue(result.claims().isEmpty(), "规则路径不做断言拆分，证据链应为空");
    }

    @Test
    void handlesEmptyKnowledgeBase() {
        DetectionResult result = detector.detect(reply("在吗", "您好，请问有什么可以帮您？", ""));

        assertFalse(result.hallucination(), "知识库为空时规则路径应放行，不做臆测");
    }
}
