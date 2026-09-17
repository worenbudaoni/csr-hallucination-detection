package com.hallucination.detection;

import com.hallucination.detection.config.RuleProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 应用能否正常装配起来。
 *
 * <p>看起来是个空测试，但它守住的是**配置绑定**——`RuleProperties` 那一大堆
 * 词表和正则全是从 `application.yml` 绑进来的，少写一项、写错一个引号，
 * 编译照样通过，只有真把上下文拉起来才会炸。
 *
 * <p>另外也验证"没有模型凭据也能启动"：密钥由调用方在页面上现填，
 * 服务端不该因为缺 key 而起不来。
 */
@SpringBootTest
class ApplicationStartsTest {

    @Autowired
    private RuleProperties rules;

    @Test
    void contextLoads() {
        assertNotNull(rules, "规则判据应当被绑定进来");
    }

    @Test
    void ruleVocabularyIsBoundFromConfiguration() {
        assertFalse(rules.getCapabilityAbsenceMarkers().isEmpty(),
                "能力缺失标记词表没绑定上");
        assertFalse(rules.getInfoAbsenceMarkers().isEmpty(),
                "信息缺失标记词表没绑定上");
        assertFalse(rules.getExistenceNegationStoplist().isEmpty(),
                "“无 X”的停用词表没绑定上");
        assertFalse(rules.getFactKeywords().isEmpty(), "事实类关键词没绑定上");
        assertFalse(rules.getPolicyKeywords().isEmpty(), "政策类关键词没绑定上");
        assertFalse(rules.getSeverityEscalationKeywords().isEmpty(), "严重度升级词没绑定上");
    }

    /**
     * 正则是懒编译的，只有真正取用一次才会发现写错了。
     * 这里全部取一遍，让配置错误在启动测试里就暴露。
     */
    @Test
    void everyRulePatternCompiles() {
        assertNotNull(rules.actionAssertion(), "动作断言正则编译失败");
        assertNotNull(rules.affirmativeAssertion(), "肯定断言正则编译失败");
        assertNotNull(rules.prohibition(), "禁止性表述正则编译失败");
        assertNotNull(rules.addressLike(), "地址形态正则编译失败");
        assertNotNull(rules.postcodeLike(), "邮编形态正则编译失败");
    }
}
