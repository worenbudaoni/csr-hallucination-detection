package com.hallucination.detection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 规则检测器的判据。
 *
 * <p>这些词表是**中文电商客服领域的话术特征**，不是某批数据的答案。
 * 它们全部外置到配置里，理由是各家的业务话术差别很大：
 * 有的平台说"未接入"，有的说"暂未开通"；有的把退货地址叫"寄回地址"。
 * 放在配置里，业务方可以按自己的知识库话术调整，不需要改代码重新发版。
 *
 * <p>配置文件里写的是正则的，用单引号包裹（YAML 单引号内的反斜杠是字面量），
 * 例如 {@code '邮\s*编\s*\d{6}'}。
 */
@ConfigurationProperties(prefix = "app.rules")
public class RuleProperties {

    // ---------------------------------------------------------------------
    // 知识库侧的"缺失声明"
    // ---------------------------------------------------------------------

    /**
     * 知识库声明"系统不具备某项能力"的标记词。
     *
     * <p>命中后配合 {@link #actionAssertionPattern}：若回复同时声称执行了该动作，即为能力越界。
     */
    private List<String> capabilityAbsenceMarkers = new ArrayList<>();

    /** 知识库声明"没有记录这条信息"的标记词。 */
    private List<String> infoAbsenceMarkers = new ArrayList<>();

    /**
     * "无 X"构词的停用词表。
     *
     * <p>中文里"无"既可以是存在否定（"无此政策"），也可以是构词成分（"7天无理由退货"）。
     * 不排掉后者，"7天无理由退货"会被误读成"本店没有退货政策"。
     */
    private List<String> existenceNegationStoplist = new ArrayList<>();

    // ---------------------------------------------------------------------
    // 回复侧的"断言信号"
    // ---------------------------------------------------------------------

    /** 声称已经执行了某个动作。这类动词要求系统真的接了对应接口。 */
    private String actionAssertionPattern = "";

    /** 肯定断言的开场白。回复以此开头，等于对知识库的否定声明做了正面肯定。 */
    private String affirmativeAssertionPattern = "";

    // ---------------------------------------------------------------------
    // 禁止性披露
    // ---------------------------------------------------------------------

    /** 知识库中的禁止性表述。 */
    private String prohibitionPattern = "";

    /** 禁止性表述所约束的对象必须出现在知识库中，才认为禁止与该对象相关。 */
    private String prohibitionSubject = "";

    /** 具体地址的形态。知识库禁止口述地址而回复给出了地址，即构成违规披露。 */
    private String addressLikePattern = "";

    /** 邮编形态。 */
    private String postcodeLikePattern = "";

    // ---------------------------------------------------------------------
    // 分类与定级
    // ---------------------------------------------------------------------

    /** 客观事实类对象的关键词，用于把幻觉归入"参数事实编造"。 */
    private List<String> factKeywords = new ArrayList<>();

    /** 业务规则类对象的关键词，用于把幻觉归入"政策规则编造"。 */
    private List<String> policyKeywords = new ArrayList<>();

    /**
     * 触发 S1 的关键词：健康风险、材质欺诈、不可逆的物流与资金操作。
     *
     * <p>严重度只升不降，这些词命中时把类型基线往 S1 抬。
     */
    private List<String> severityEscalationKeywords = new ArrayList<>();

    // ---------------------------------------------------------------------

    /** 把配置里的正则编译出来。配置写错时尽早失败，而不是等到运行时才静默不匹配。 */
    public Pattern actionAssertion() {
        return compile(actionAssertionPattern, "app.rules.action-assertion-pattern");
    }

    public Pattern affirmativeAssertion() {
        return compile(affirmativeAssertionPattern, "app.rules.affirmative-assertion-pattern");
    }

    public Pattern prohibition() {
        return compile(prohibitionPattern, "app.rules.prohibition-pattern");
    }

    public Pattern addressLike() {
        return compile(addressLikePattern, "app.rules.address-like-pattern");
    }

    public Pattern postcodeLike() {
        return compile(postcodeLikePattern, "app.rules.postcode-like-pattern");
    }

    private static Pattern compile(String regex, String propertyName) {
        if (regex == null || regex.isBlank()) {
            throw new IllegalStateException("配置项 " + propertyName + " 不能为空");
        }
        return Pattern.compile(regex);
    }

    // ---------------------------------------------------------------------
    // getter / setter
    // ---------------------------------------------------------------------

    public List<String> getCapabilityAbsenceMarkers() {
        return capabilityAbsenceMarkers;
    }

    public void setCapabilityAbsenceMarkers(List<String> capabilityAbsenceMarkers) {
        this.capabilityAbsenceMarkers = capabilityAbsenceMarkers;
    }

    public List<String> getInfoAbsenceMarkers() {
        return infoAbsenceMarkers;
    }

    public void setInfoAbsenceMarkers(List<String> infoAbsenceMarkers) {
        this.infoAbsenceMarkers = infoAbsenceMarkers;
    }

    public List<String> getExistenceNegationStoplist() {
        return existenceNegationStoplist;
    }

    public void setExistenceNegationStoplist(List<String> existenceNegationStoplist) {
        this.existenceNegationStoplist = existenceNegationStoplist;
    }

    public String getActionAssertionPattern() {
        return actionAssertionPattern;
    }

    public void setActionAssertionPattern(String actionAssertionPattern) {
        this.actionAssertionPattern = actionAssertionPattern;
    }

    public String getAffirmativeAssertionPattern() {
        return affirmativeAssertionPattern;
    }

    public void setAffirmativeAssertionPattern(String affirmativeAssertionPattern) {
        this.affirmativeAssertionPattern = affirmativeAssertionPattern;
    }

    public String getProhibitionPattern() {
        return prohibitionPattern;
    }

    public void setProhibitionPattern(String prohibitionPattern) {
        this.prohibitionPattern = prohibitionPattern;
    }

    public String getProhibitionSubject() {
        return prohibitionSubject;
    }

    public void setProhibitionSubject(String prohibitionSubject) {
        this.prohibitionSubject = prohibitionSubject;
    }

    public String getAddressLikePattern() {
        return addressLikePattern;
    }

    public void setAddressLikePattern(String addressLikePattern) {
        this.addressLikePattern = addressLikePattern;
    }

    public String getPostcodeLikePattern() {
        return postcodeLikePattern;
    }

    public void setPostcodeLikePattern(String postcodeLikePattern) {
        this.postcodeLikePattern = postcodeLikePattern;
    }

    public List<String> getFactKeywords() {
        return factKeywords;
    }

    public void setFactKeywords(List<String> factKeywords) {
        this.factKeywords = factKeywords;
    }

    public List<String> getPolicyKeywords() {
        return policyKeywords;
    }

    public void setPolicyKeywords(List<String> policyKeywords) {
        this.policyKeywords = policyKeywords;
    }

    public List<String> getSeverityEscalationKeywords() {
        return severityEscalationKeywords;
    }

    public void setSeverityEscalationKeywords(List<String> severityEscalationKeywords) {
        this.severityEscalationKeywords = severityEscalationKeywords;
    }
}
