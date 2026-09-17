package com.hallucination.detection.model;

/**
 * 单案例的检测结果相对人工标注的分类。
 */
public enum Outcome {

    /** 人工判为幻觉，检测器也判为幻觉。 */
    TP("命中"),

    /** 人工判为干净，检测器却判为幻觉。 */
    FP("误报"),

    /** 人工判为干净，检测器也判为干净。 */
    TN("正确放行"),

    /** 人工判为幻觉，检测器漏掉了。 */
    FN("漏检");

    private final String label;

    Outcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
