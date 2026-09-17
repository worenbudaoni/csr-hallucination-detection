package com.hallucination.detection.detector.llm;

import com.hallucination.detection.model.ReplyRecord;

/**
 * 检测用提示词。
 *
 * <p>判定口径全部写死在提示词里，而不是留给模型自由发挥——这套口径是需求评审阶段定下来的，
 * 尤其是 {@code CONTRADICTED} 与 {@code CONSISTENT_HEDGED} 的分界，它直接决定了
 * "知识库没写"到底算不算幻觉。模型不被告知这条规则，就会在两种截然相反的判法之间随机漂移。
 *
 * <p><b>提示词里不含任何具体业务案例。</b>举例一律用抽象描述，
 * 这样换一个业务域（比如金融、医疗客服）时提示词不用改。
 *
 * <p>提示词开头带一个任务标记（{@code TASK:xxx}），便于在日志与调用记录里区分请求类型。
 */
public final class Prompts {

    public static final String MARK_CLAIM_VERIFICATION = "TASK:CLAIM_VERIFICATION";
    public static final String MARK_WHOLE_REPLY_VERDICT = "TASK:WHOLE_REPLY_VERDICT";

    private static final String KB_OPEN = "<知识库>";
    private static final String KB_CLOSE = "</知识库>";
    private static final String REPLY_OPEN = "<回复>";
    private static final String REPLY_CLOSE = "</回复>";

    private Prompts() {
    }

    // ---------------------------------------------------------------------
    // 主路径：断言拆分 + 逐条核对
    // ---------------------------------------------------------------------

    public static String claimVerification(ReplyRecord reply) {
        return MARK_CLAIM_VERIFICATION + "\n"
                + """
                你是客服回复的事实核查员。把"回复"拆成独立的断言，逐条核对是否被"知识库"支持。

                判定标准（严格按此执行）：
                1. SUPPORTED —— 知识库明确写了该内容。
                2. CONTRADICTED —— 知识库明确写了相反的值。
                   数值、单位、型号、专有名词、材质成分、政策条款、时间期限不一致，都算冲突。
                3. UNSUPPORTED —— 知识库对该断言保持沉默，而回复给出了可核查的肯定断言。
                   知识库中"本项未标注""未提及该信息"这类写法，就是在声明"我们没有这条信息"；
                   此时回复却肯定地陈述了具体内容，判 UNSUPPORTED。
                   **知识库没写，不等于回复可以随便说。**
                4. CONSISTENT_HEDGED —— 知识库未逐字覆盖，但回复的表述与知识库语义一致，
                   只是措辞不同，且保留了知识库中的限定条件。**这种情况不算幻觉。**
                5. NON_FACTUAL —— 问候、致歉、共情等非事实性表述，不参与幻觉判定。

                【最关键的一条】如何区分 UNSUPPORTED 与 CONSISTENT_HEDGED —— 看回复有没有保留限定条件：
                · 保留了限定词（"基本""轻微""可能""建议咨询专业人士"这类）→ CONSISTENT_HEDGED，放行。
                · 把限定条件抹平成确定性结论，并给出与知识库相悖的断言 → CONTRADICTED。
                  即：知识库指出某条件只在部分情形下成立，回复却宣称它普遍成立或根本不成立。

                【部分正确也要逐条拆】回复可能同时说对了 A、说错了 B。
                必须拆成两条分别判定，绝不能因为"大体正确"就整体放行。

                【逐项比对具体值】凡是出现数字、单位、型号、专有名词、时间期限的断言，
                必须与知识库逐项核对；句式相似不代表内容一致。

                """ + KB_OPEN + "\n" + reply.knowledgeBase() + "\n" + KB_CLOSE + "\n\n"
                + REPLY_OPEN + "\n" + reply.systemReply() + "\n" + REPLY_CLOSE + "\n\n"
                + """
                只输出 JSON 数组，不要任何解释性文字。格式：
                [{"claim":"断言原文","verdict":"SUPPORTED|CONTRADICTED|UNSUPPORTED|CONSISTENT_HEDGED|NON_FACTUAL","evidence":"知识库中对应的原文，没有则填空字符串"}]
                """;
    }

    // ---------------------------------------------------------------------
    // 基线路：不做断言拆分，直接整体判定
    // ---------------------------------------------------------------------

    /**
     * 整体判定基线。
     *
     * <p>这条基线有意保持"朴素"——不做断言拆分。它存在的价值正是用来验证
     * <b>断言级拆分是不是过度设计</b>：面对一条同时说对一部分、说错另一部分的回复，
     * 整体判定倾向于给出"基本正确"的结论从而漏检。
     *
     * <p>如果这条基线的指标和主路径一样好，那就说明拆分是白做的。
     */
    public static String wholeReplyVerdict(ReplyRecord reply) {
        return MARK_WHOLE_REPLY_VERDICT + "\n"
                + """
                你是客服回复的幻觉检测员。判断"回复"相对"知识库"是否存在幻觉——
                包括编造知识库中没有的事实、或与知识库中的记载相矛盾。

                """ + KB_OPEN + "\n" + reply.knowledgeBase() + "\n" + KB_CLOSE + "\n\n"
                + REPLY_OPEN + "\n" + reply.systemReply() + "\n" + REPLY_CLOSE + "\n\n"
                + """
                只输出 JSON 对象，不要任何解释性文字。格式：
                {"is_hallucination":true或false,"type":"FACT|POLICY|CAPABILITY|OMISSION|NONE","severity":"S1|S2|S3|NONE","reason":"判定理由"}
                """;
    }
}
