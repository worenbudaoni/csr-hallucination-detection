package com.hallucination.detection.batch;

import com.hallucination.detection.model.Metrics;

/**
 * 接口返回的指标视图。
 *
 * <p>存在的理由：{@link Metrics} 是个 record，Jackson 只序列化<b>记录组件</b>，
 * 也就是四个计数；{@code accuracy()} / {@code precision()} 这些是方法，不会出现在 JSON 里。
 * 直接返回它的话，调用方读 {@code accuracy} 会拿到 undefined——
 * 这个问题单测看不出来（Java 侧调用的是方法），是端到端跑一遍才暴露的。
 *
 * <p>所以这里把派生指标显式摊平成字段。
 */
public record MetricsView(
        int truePositive,
        int falsePositive,
        int trueNegative,
        int falseNegative,
        double accuracy,
        double precision,
        double recall,
        double f1) {

    public static MetricsView of(Metrics metrics) {
        return new MetricsView(
                metrics.truePositive(),
                metrics.falsePositive(),
                metrics.trueNegative(),
                metrics.falseNegative(),
                metrics.accuracy(),
                metrics.precision(),
                metrics.recall(),
                metrics.f1());
    }
}
