package com.hallucination.detection.detector;

import com.hallucination.detection.model.DetectionResult;
import com.hallucination.detection.model.ReplyRecord;

/**
 * 幻觉检测器。
 *
 * <p><b>接口有意只接受 {@link ReplyRecord}。</b>检测器在结构上拿不到人工标注，
 * 因此不存在"偷看答案"的可能——检出率反映的是真实检测能力。这条约束不是靠自觉，
 * 是靠类型系统保证的。
 */
public interface Detector {

    /** 检测器名称，用于在评测报告里区分各路基线与主检测器。 */
    String name();

    /**
     * 检测单条回复。
     *
     * @param reply 用户问题 + 系统回复 + 知识库条目
     * @return 判定结论，含类型、严重度与证据链
     */
    DetectionResult detect(ReplyRecord reply);
}
