package com.hallucination.detection.batch;

import com.hallucination.detection.model.Claim;
import com.hallucination.detection.model.GroundTruthRecord;

import java.util.List;
import java.util.Map;

/**
 * 一次批量检测的结果。
 *
 * @param engine               产出结论的模型，形如 {@code deepseek-v4-pro @ https://api.deepseek.com/v1}
 * @param total                参与检测的条数
 * @param detected             判为幻觉的条数
 * @param clean                判为干净的条数
 * @param typeDistribution     检出幻觉按类型分布，中文标签 → 条数
 * @param severityDistribution 检出幻觉按严重度分布
 * @param hasGroundTruth       本次是否提供了人工标注
 * @param metrics              检出率指标；无标注时为 null
 * @param missed               漏检的案例编号
 * @param falseAlarms          误报的案例编号
 * @param cases                逐条结果
 */
public record BatchResult(
        String engine,
        int total,
        int detected,
        int clean,
        Map<String, Integer> typeDistribution,
        Map<String, Integer> severityDistribution,
        boolean hasGroundTruth,
        MetricsView metrics,
        List<String> missed,
        List<String> falseAlarms,
        List<CaseOutcome> cases) {

    /**
     * 单条案例的检测结论。
     *
     * @param truth      人工标注，未提供时为 null
     * @param outcome    相对人工标注的结果（命中 / 漏检 / 误报 / 正确放行），未提供标注时为 null
     */
    public record CaseOutcome(
            String id,
            boolean hallucination,
            String type,
            String typeLabel,
            String severity,
            String severityLabel,
            String reason,
            List<Claim> claims,
            GroundTruthRecord truth,
            String outcome) {
    }
}
