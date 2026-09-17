package com.hallucination.detection.model;

/**
 * 严重度。作为横切轴独立于幻觉类型：类型回答"错在哪"，严重度回答"有多严重"。
 *
 * <p>定级依据（启发式）：看这条幻觉被用户当真之后会造成什么后果。
 */
public enum Severity {

    /** 健康/安全风险，或不可逆的资金、物流损失。 */
    S1("高危", "可能造成人身健康风险，或不可逆的资金与物流损失"),

    /** 导致错误的购买决策，但后果可挽回。 */
    S2("中", "导致用户做出错误的购买或售后决策"),

    /** 体验类误导，用户据此行动也不会产生实质损失。 */
    S3("低", "表述失准，对用户决策影响有限"),

    /** 非幻觉。 */
    NONE("无", "未检出幻觉");

    private final String label;
    private final String criterion;

    Severity(String label, String criterion) {
        this.label = label;
        this.criterion = criterion;
    }

    public String label() {
        return label;
    }

    public String criterion() {
        return criterion;
    }

    /** 取更严重的那个，用于"只可上调不可下调"的合并。 */
    public Severity worse(Severity other) {
        return this.ordinal() <= other.ordinal() ? this : other;
    }
}
