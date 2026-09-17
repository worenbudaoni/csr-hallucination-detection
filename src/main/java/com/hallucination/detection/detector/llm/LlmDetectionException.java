package com.hallucination.detection.detector.llm;

/**
 * 模型调用或解析失败，检测无法给出可信结论时抛出。
 *
 * <p><b>为什么必须抛而不是降级成"判为干净"。</b>
 * 大模型不可用（网络不通、401、限流、返回的 JSON 解析不了）时，
 * 如果按"没发现幻觉"处理，整批数据会被判成干净——你会得到一份格式完整、
 * 指标漂亮、但完全错误的报告，而且没有任何迹象表明它错了。
 *
 * <p>这类失败必须显式暴露。宁可让整次运行失败，也不要输出一份看似正常的错误结论。
 */
public class LlmDetectionException extends RuntimeException {

    public LlmDetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
