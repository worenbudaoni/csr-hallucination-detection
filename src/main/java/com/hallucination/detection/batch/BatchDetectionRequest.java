package com.hallucination.detection.batch;

import com.hallucination.detection.model.GroundTruthRecord;
import com.hallucination.detection.model.ReplyRecord;

import java.util.List;

/**
 * 一次批量检测请求：调用方现给数据和模型凭据。
 *
 * @param baseUrl     OpenAI 兼容端点，需带各家的路径前缀
 * @param apiKey      密钥；连本地模型时可留空
 * @param model       模型名
 * @param replies     待检测的回复
 * @param groundTruth 可选的人工标注，给了才能算检出率
 */
public record BatchDetectionRequest(
        String baseUrl,
        String apiKey,
        String model,
        List<ReplyRecord> replies,
        List<GroundTruthRecord> groundTruth) {

    public boolean hasGroundTruth() {
        return groundTruth != null && !groundTruth.isEmpty();
    }
}
