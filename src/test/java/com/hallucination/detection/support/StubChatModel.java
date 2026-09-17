package com.hallucination.detection.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 测试用的 {@link ChatModel} 替身。
 *
 * <p><b>这不是产品里的离线模式，产品侧已经没有离线分支了。</b>
 * 它只存在于 {@code src/test} 下，作用是让集成测试能在没有网络、没有 API key 的环境里
 * 跑通整条管线——CI 上不该因为连不上模型服务而红。
 *
 * <p>它对提示词的回应是脚本化的：按提示词开头的任务标记分派，
 * 返回预先设定的内容。因此它不具备任何检测能力，
 * <b>也正因如此，它不能用来评价检测质量</b>——那必须用真实模型跑。
 */
public class StubChatModel implements ChatModel {

    private final Function<String, String> responder;

    public StubChatModel(Function<String, String> responder) {
        this.responder = responder;
    }

    /** 一律回报"全部断言有据"，即判定为没有幻觉。 */
    public static StubChatModel alwaysClean() {
        return new StubChatModel(prompt -> {
            if (prompt.contains("TASK:CLAIM_VERIFICATION")) {
                return """
                        [{"claim":"（替身模型的占位断言）","verdict":"SUPPORTED","evidence":""}]
                        """;
            }
            if (prompt.contains("TASK:WHOLE_REPLY_VERDICT")) {
                return """
                        {"is_hallucination":false,"type":"NONE","severity":"NONE","reason":"（替身模型）"}
                        """;
            }
            // FactCheckingEvaluator 自带提示词，不认上面的任务标记；它期望的是 Yes/No
            return "Yes";
        });
    }

    /** 一律回报"存在幻觉"，用于验证聚合逻辑。 */
    public static StubChatModel alwaysHallucinating() {
        return new StubChatModel(prompt -> {
            if (prompt.contains("TASK:CLAIM_VERIFICATION")) {
                return """
                        [{"claim":"（替身模型的占位断言）","verdict":"CONTRADICTED","evidence":"（替身）"}]
                        """;
            }
            if (prompt.contains("TASK:WHOLE_REPLY_VERDICT")) {
                return """
                        {"is_hallucination":true,"type":"FACT","severity":"S2","reason":"（替身模型）"}
                        """;
            }
            return "No";
        });
    }

    /** 永远抛异常，用于验证"模型不可用时不静默降级"这条约束。 */
    public static StubChatModel failing(String message) {
        return new StubChatModel(prompt -> {
            throw new IllegalStateException(message);
        });
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String answer = responder.apply(prompt.getContents());
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }
}
