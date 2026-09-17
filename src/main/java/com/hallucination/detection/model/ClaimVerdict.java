package com.hallucination.detection.model;

/**
 * 单条断言相对知识库的核对结论。
 *
 * <p>三种结论的边界是整个检测器判定的核心，尤其是 {@link #UNSUPPORTED} 与
 * {@link #CONSISTENT_HEDGED} 的区分——它决定了"知识库没写"到底算不算幻觉。
 */
public enum ClaimVerdict {

    /** 知识库明确支持该断言。 */
    SUPPORTED("有据"),

    /** 知识库明确记载了相反的值，断言与之直接冲突。 */
    CONTRADICTED("与知识库冲突"),

    /**
     * 知识库对此保持沉默，而回复给出了可核查的肯定断言。
     *
     * <p>按闭世界口径判为幻觉：知识库写"本项未标注""未提及该信息"，
     * 就是在声明"我们没有这条信息"，此时回复却肯定地陈述了具体内容。
     */
    UNSUPPORTED("知识库无据"),

    /**
     * 知识库未逐字覆盖，但语义一致，只是措辞不同。
     *
     * <p>必须放行，且这条与 {@link #UNSUPPORTED} 的区分是整个判定口径的关键：
     * 缺乏这条，任何"知识库没逐字写过"的合理表述都会被误报为幻觉。
     */
    CONSISTENT_HEDGED("语义一致，措辞未逐字覆盖"),

    /** 常识性、无法核查、也不影响决策的表述。 */
    NON_FACTUAL("非事实性表述");

    private final String label;

    ClaimVerdict(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 该结论是否构成幻觉证据。 */
    public boolean isHallucinatory() {
        return this == CONTRADICTED || this == UNSUPPORTED;
    }
}
