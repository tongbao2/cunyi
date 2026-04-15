package com.cunyi.ai.manager

/**
 * 医疗知识库上下文（RAG 的 Retrieve 结果）
 * 由 MedicalKBQA 查询生成，注入 AiEngine 生成自然语言回答
 */
data class MedicalContext(
    val disease: String = "",
    val symptom: String = "",
    val cause: String = "",
    val prevent: String = "",
    val cureWay: String = "",
    val drug: String = "",
    val food: String = "",
    val check: String = "",
    val department: String = "",
    val other: String = ""
) {
    val hasData: Boolean
        get() = disease.isNotEmpty() || symptom.isNotEmpty() || cause.isNotEmpty() ||
                prevent.isNotEmpty() || cureWay.isNotEmpty() || drug.isNotEmpty() ||
                food.isNotEmpty() || check.isNotEmpty()
}
