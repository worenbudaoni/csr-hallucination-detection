package com.hallucination.detection.model;

/**
 * 人工标注结果。
 *
 * <p><b>只有 evaluator 模块可以访问这个类型。</b>检测器在运行时读取它会构成作弊——
 * 检出率将不再反映任何真实的检测能力。这条约束由 {@code DetectionPipeline} 的结构保证：
 * 检测链路拿不到 {@code GroundTruthRecord}。
 *
 * @param id                案例编号
 * @param isHallucination   人工判定是否为幻觉
 * @param hallucinationType 人工标注的类型原文（如"参数编造""能力越界"），非幻觉时为 null
 * @param detail            人工标注的判定理由
 */
public record GroundTruthRecord(
        String id,
        boolean isHallucination,
        String hallucinationType,
        String detail) {
}
