package com.hallucination.detection.model;

/**
 * 一条待检测的客服回复。
 *
 * @param id            案例编号，由调用方定义，只需在同一批数据内唯一
 * @param userQuestion  用户问题
 * @param systemReply   客服系统的回复（待检测对象）
 * @param knowledgeBase 知识库条目。部分案例形如"无（客服系统未接入物流查询接口）"，
 *                      这种"知识库明示能力不存在"的写法本身就是强信号，规则检测器直接吃这一条。
 */
public record ReplyRecord(
        String id,
        String userQuestion,
        String systemReply,
        String knowledgeBase) {
}
