package com.hallucination.detection.batch;

import com.hallucination.detection.detector.Detector;
import com.hallucination.detection.detector.HybridDetector;
import com.hallucination.detection.detector.RuleBasedDetector;
import com.hallucination.detection.detector.TaxonomyRules;
import com.hallucination.detection.detector.llm.LlmClaimDetector;
import com.hallucination.detection.detector.llm.OpenAiCompatibleChatModel;
import com.hallucination.detection.model.DetectionResult;
import com.hallucination.detection.model.GroundTruthRecord;
import com.hallucination.detection.model.Metrics;
import com.hallucination.detection.model.Outcome;
import com.hallucination.detection.model.ReplyRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按调用方现给的凭据跑一次批量检测。
 *
 * <p>用的是<b>规则 + 大模型</b>的主检测器：规则先吃掉"知识库明示没有该能力"的确定性案例，
 * 其余交给大模型做断言级核对。和配置驱动的那条路径是同一套逻辑，只是模型客户端
 * 按请求临时构造——凭据用完即弃，不落盘。
 *
 * <p><b>并发但失败即停。</b>逐条串行太慢（20 条要等好几分钟），所以开了线程池；
 * 但任何一条失败就停止派发新任务并把错误抛出去——继续跑只会产出更多不可信的结论，
 * 汇总指标也就失去意义。
 */
@Service
public class BatchDetectionService {

    /**
     * 并发上限。
     *
     * <p>取 4 是折中：串行太慢（20 条要等好几分钟），太高又容易撞上厂商的限流（429）。
     * 想让调用方自己调的话，把它提到请求参数里即可。
     */
    private static final int MAX_CONCURRENCY = 4;

    private final RuleBasedDetector ruleDetector;
    private final TaxonomyRules taxonomy;

    public BatchDetectionService(RuleBasedDetector ruleDetector, TaxonomyRules taxonomy) {
        this.ruleDetector = ruleDetector;
        this.taxonomy = taxonomy;
    }

    public BatchResult detect(BatchDetectionRequest request) {
        String engine = request.model() + " @ " + request.baseUrl();
        Detector detector = buildDetector(request);

        Map<String, GroundTruthRecord> truthById = request.hasGroundTruth()
                ? request.groundTruth().stream().collect(Collectors.toMap(
                        GroundTruthRecord::id, Function.identity(), (a, b) -> a))
                : Map.of();

        List<BatchResult.CaseOutcome> cases = detectAll(detector, request.replies(), truthById);
        return summarize(engine, cases, request.hasGroundTruth());
    }

    private Detector buildDetector(BatchDetectionRequest request) {
        // 模型客户端按请求构造：地址、密钥、模型都来自这一次调用，不共享、不缓存
        var chatModel = new OpenAiCompatibleChatModel(request.baseUrl(), request.apiKey(), request.model());
        return new HybridDetector(ruleDetector, new LlmClaimDetector(chatModel, taxonomy));
    }

    // ---------------------------------------------------------------------

    private List<BatchResult.CaseOutcome> detectAll(Detector detector,
                                                    List<ReplyRecord> replies,
                                                    Map<String, GroundTruthRecord> truthById) {
        int threads = Math.min(Math.max(1, Math.min(replies.size(), MAX_CONCURRENCY)), replies.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<BatchResult.CaseOutcome>> futures = new ArrayList<>();
            for (ReplyRecord reply : replies) {
                futures.add(pool.submit(() -> toOutcome(detector.detect(reply), truthById.get(reply.id()))));
            }

            List<BatchResult.CaseOutcome> outcomes = new ArrayList<>();
            RuntimeException firstFailure = null;
            for (Future<BatchResult.CaseOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (Exception e) {
                    // 第一个失败就取消其余任务，不再产出更多可能错误的结论
                    if (firstFailure == null) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        firstFailure = cause instanceof RuntimeException re
                                ? re
                                : new IllegalStateException(cause.getMessage(), cause);
                        futures.forEach(f -> f.cancel(true));
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private BatchResult.CaseOutcome toOutcome(DetectionResult result, GroundTruthRecord truth) {
        String outcome = truth == null ? null
                : outcomeLabel(truth.isHallucination(), result.hallucination()).label();

        return new BatchResult.CaseOutcome(
                result.caseId(),
                result.hallucination(),
                result.type().name(),
                result.type().label(),
                result.severity().name(),
                result.severity().label(),
                result.reason(),
                result.claims(),
                truth,
                outcome);
    }

    private static Outcome outcomeLabel(boolean actual, boolean predicted) {
        if (actual) {
            return predicted ? Outcome.TP : Outcome.FN;
        }
        return predicted ? Outcome.FP : Outcome.TN;
    }

    private BatchResult summarize(String engine,
                                  List<BatchResult.CaseOutcome> cases,
                                  boolean hasGroundTruth) {
        int detected = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> bySeverity = new LinkedHashMap<>();

        for (BatchResult.CaseOutcome c : cases) {
            if (!c.hallucination()) {
                continue;
            }
            detected++;
            byType.merge(c.typeLabel(), 1, Integer::sum);
            bySeverity.merge(c.severity(), 1, Integer::sum);
        }

        // 每条案例已经带了相对标注的结果，直接数即可，不用再回表查一遍
        MetricsView metrics = null;
        if (hasGroundTruth) {
            int tp = 0, fp = 0, tn = 0, fn = 0;
            for (BatchResult.CaseOutcome c : cases) {
                if (c.truth() == null) {
                    continue;
                }
                switch (outcomeLabel(c.truth().isHallucination(), c.hallucination())) {
                    case TP -> tp++;
                    case FP -> fp++;
                    case TN -> tn++;
                    case FN -> fn++;
                }
            }
            metrics = MetricsView.of(new Metrics(tp, fp, tn, fn));
        }

        return new BatchResult(engine, cases.size(), detected, cases.size() - detected,
                byType, bySeverity, hasGroundTruth, metrics,
                labelsOf(cases, Outcome.FN), labelsOf(cases, Outcome.FP), cases);
    }

    private static List<String> labelsOf(List<BatchResult.CaseOutcome> cases, Outcome want) {
        return cases.stream()
                .filter(c -> c.truth() != null && want.label().equals(c.outcome()))
                .map(BatchResult.CaseOutcome::id)
                .toList();
    }
}
