package com.hallucination.detection.model;

/**
 * 从回复中拆出的一条独立断言，以及它相对知识库的核对结论。
 *
 * <p>拆到断言粒度是必要的：一条回复可能同时说对了 A、说错了 B、又说错了 C。
 * 整体判定会把它看成"基本正确"直接放过，逐条核对则不会。
 *
 * @param text     断言原文
 * @param verdict  核对结论
 * @param evidence 判定依据，通常是知识库中的对应原文
 */
public record Claim(
        String text,
        ClaimVerdict verdict,
        String evidence) {

    public static Claim of(String text, ClaimVerdict verdict, String evidence) {
        return new Claim(text, verdict, evidence);
    }
}
