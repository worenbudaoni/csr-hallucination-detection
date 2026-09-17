package com.hallucination.detection.model;

/**
 * 幻觉分类体系。
 *
 * <p>分类轴选的是「出错对象」而不是「与知识库的偏离方式」。原因是业务方拿到判定结果后
 * 需要直接知道该找谁处理：参数错误找商品，政策错误找运营，能力越界找工程。
 * "偏离方式"这个轴在这个数据集上没有区分度——绝大多数案例的知识库恰好写了相反的值，
 * 按"有无依据"分会导致几乎所有案例都落到同一类里。
 */
public enum HallucinationType {

    FACT("参数事实编造",
            "产品参数、材质、成分、品牌关系等客观断言与知识库不符，或知识库中根本无据",
            Severity.S2),

    POLICY("政策规则编造",
            "退货、发票、发货、支付、优惠、退货地址等业务规则与知识库不符，或知识库中根本无据",
            Severity.S2),

    CAPABILITY("能力越界",
            "声称执行或查询了系统并不具备的动作（知识库明示未接入该接口或不具备该功能）",
            Severity.S2),

    OMISSION("信息遗漏误导",
            "选择性遗漏知识库中的关键限定条件，把带条件的提示抹平成确定性结论，导致建议失真",
            Severity.S3),

    NONE("无幻觉", "回复与知识库一致", Severity.NONE);

    private final String label;
    private final String definition;
    private final Severity baselineSeverity;

    HallucinationType(String label, String definition, Severity baselineSeverity) {
        this.label = label;
        this.definition = definition;
        this.baselineSeverity = baselineSeverity;
    }

    public String label() {
        return label;
    }

    public String definition() {
        return definition;
    }

    /** 该类型的默认严重度，可被规则上调（不可下调）。 */
    public Severity baselineSeverity() {
        return baselineSeverity;
    }
}
