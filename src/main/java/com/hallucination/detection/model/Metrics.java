package com.hallucination.detection.model;

/**
 * 二分类（幻觉 / 干净）的评测指标。
 *
 * <p><b>准确率在正负不平衡的数据上会严重误导。</b>本数据集是 18 正 : 2 负，
 * 一个什么都不做、一律判幻觉的策略就能拿到 90% 准确率和 100% 召回率——
 * 数字漂亮，实用价值为零。
 *
 * <p>所以页面上的报表以<b>漏检数与误报数</b>为主，并在正例占比超过 80% 时
 * 显式给出分布提示，而不是让使用者对着一个准确率数字下判断。
 */
public record Metrics(
        int truePositive,
        int falsePositive,
        int trueNegative,
        int falseNegative) {

    public static Metrics of(Iterable<Outcome> outcomes) {
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (Outcome o : outcomes) {
            switch (o) {
                case TP -> tp++;
                case FP -> fp++;
                case TN -> tn++;
                case FN -> fn++;
            }
        }
        return new Metrics(tp, fp, tn, fn);
    }

    public int total() {
        return truePositive + falsePositive + trueNegative + falseNegative;
    }

    /** 漏检数：人工判为幻觉但被放过的案例数。 */
    public int missed() {
        return falseNegative;
    }

    /** 误报数：人工判为干净却被判成幻觉的案例数。 */
    public int falseAlarms() {
        return falsePositive;
    }

    public double accuracy() {
        return total() == 0 ? 0 : (double) (truePositive + trueNegative) / total();
    }

    /**
     * 精确率。分母为 0（一条都没判成幻觉）时定义为 0，
     * 而不是 1——"什么都没检出"不该被记成完美精确。
     */
    public double precision() {
        int predicted = truePositive + falsePositive;
        return predicted == 0 ? 0 : (double) truePositive / predicted;
    }

    public double recall() {
        int actual = truePositive + falseNegative;
        return actual == 0 ? 0 : (double) truePositive / actual;
    }

    public double f1() {
        double p = precision();
        double r = recall();
        return (p + r) == 0 ? 0 : 2 * p * r / (p + r);
    }
}
