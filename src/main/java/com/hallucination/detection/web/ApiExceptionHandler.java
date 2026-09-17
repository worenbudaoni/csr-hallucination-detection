package com.hallucination.detection.web;

import com.hallucination.detection.detector.llm.LlmDetectionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把内部异常翻译成调用方能照着处理的 HTTP 响应。
 *
 * <p>不加这层的话，模型不可用会以裸的 500 加一段 Java 堆栈返回——
 * 调用方看不出这是"密钥没配"还是"网络不通"还是"代码有 bug"，
 * 而这三种情况的处置方式完全不同。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 模型调用失败。用 503 而不是 500：这是依赖不可用，不是本服务出错。 */
    @ExceptionHandler(LlmDetectionException.class)
    public ResponseEntity<Map<String, Object>> onLlmFailure(LlmDetectionException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body(
                "llm_unavailable",
                "模型调用失败，检测已中止——不会产出可能错误的结论。",
                e.getMessage(),
                // 不说"去查 OPENAI_* 环境变量"：批量接口的地址和密钥是请求体里给的，
                // 指到环境变量上会把人带偏。
                "检查本次使用的端点地址、密钥与模型名是否正确，以及网络是否可达。"));
    }

    /** 请求体不是合法 JSON。这类错误发生在进控制器之前，只能在这一层兜。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(
                "malformed_json",
                "请求体不是合法的 JSON。",
                e.getMessage(),
                "检查 JSON 语法；如果是命令行传中文，注意别让终端把它编码成 GBK——"
                        + "把 payload 写进文件再用 --data-binary @file 更稳妥。"));
    }

    /**
     * 规则判据配置不完整（{@code app.rules.*} 有必填项为空）。
     *
     * <p>这类问题在启动后第一次检测时才暴露——规则检测器是懒编译正则的。
     * 属于部署配置问题，不是调用方的输入问题。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onBadConfiguration(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body(
                "invalid_configuration",
                "服务端配置有问题，无法完成检测。",
                e.getMessage(),
                "检查 application.yml 的 app.rules.* 是否有空缺项。"));
    }

    private static Map<String, Object> body(String error, String message, String detail, String hint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("detail", detail);
        body.put("hint", hint);
        return body;
    }
}
