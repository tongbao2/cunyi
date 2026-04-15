package com.cunyi.ai.manager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream

/**
 * 医疗知识库问答引擎
 * 基于 QASystemOnMedicalKG (liuhuanyong/QASystemOnMedicalKG) 架构
 * - AC 自动机做实体识别
 * - 规则引擎做意图分类
 * - SQLite 数据库做知识查询
 */
class MedicalKBQA private constructor(private val ctx: Context) {

    companion object {
        private const val TAG = "MedicalKBQA"
        private const val DB_NAME = "medical/medical_kb.db"

        @Volatile
        private var INSTANCE: MedicalKBQA? = null

        fun getInstance(context: Context): MedicalKBQA {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MedicalKBQA(context.applicationContext).also { INSTANCE = it }
            }
        }

        // 问句关键词（从 Python 版移植）
        private val SYMPTOM_QWDS = listOf("症状", "表征", "现象", "症候", "表现", "有什么反应")
        private val CAUSE_QWDS = listOf("原因", "成因", "为什么", "怎么会", "为何", "导致", "会造成", "引起")
        private val ACCOMPANY_QWDS = listOf("并发症", "并发", "一起发生", "伴随", "共现", "同时出现")
        private val FOOD_QWDS = listOf("饮食", "吃", "食", "膳食", "喝", "忌口", "补品", "食谱", "食物", "不能吃", "可以吃", "吃什么", "喝什么")
        private val DRUG_QWDS = listOf("药", "药品", "用药", "胶囊", "口服液", "炎片", "吃什么药", "用什么药", "药物")
        private val PREVENT_QWDS = listOf("预防", "防范", "防止", "怎么预防", "如何预防", "怎么避免")
        private val LASTTIME_QWDS = listOf("周期", "多久", "多长时间", "几天", "几年", "多久能好", "多久治愈")
        private val CUREWAY_QWDS = listOf("怎么治疗", "如何医治", "怎么治", "疗法", "治疗方案", "怎么办")
        private val CUREPROB_QWDS = listOf("治好", "治愈", "能治", "可治", "治好希望", "治好几率", "治愈率")
        private val EASYGET_QWDS = listOf("易感人群", "容易感染", "易发人群", "什么人", "哪些人", "谁容易")
        private val CHECK_QWDS = listOf("检查", "检查项目", "做检查", "检查什么", "需要做什么检查")
        private val DEPARTMENT_QWDS = listOf("属于什么科", "什么科", "科室", "看什么科", "挂什么科")
        private val DESC_QWDS = listOf("介绍", "什么是", "是什么意思", "详细说明", "解释")
    }

    private var db: SQLiteDatabase? = null
    private var initialized = false

    // 字典
    private val diseaseSet = mutableSetOf<String>()
    private val symptomSet = mutableSetOf<String>()
    private val drugSet = mutableSetOf<String>()
    private val foodSet = mutableSetOf<String>()
    private val checkSet = mutableSetOf<String>()

    // ===== 初始化 =====
    suspend fun init() = withContext(Dispatchers.IO) {
        if (initialized) return@withContext
        try {
            val dbFile = File(ctx.filesDir, DB_NAME)
            if (!dbFile.exists()) {
                dbFile.parentFile?.mkdirs()
                ctx.assets.open(DB_NAME).use { input ->
                    dbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "DB 复制到: ${dbFile.absolutePath}")
            }
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            initialized = true
            Log.i(TAG, "MedicalKBQA 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败: ${e.message}", e)
        }
    }

    // ===== 加载字典 =====
    private var dictsLoaded = false

    private suspend fun loadDictionaries() = withContext(Dispatchers.IO) {
        if (dictsLoaded) return@withContext
        try {
            fun loadDict(filename: String): Set<String> {
                return try {
                    ctx.assets.open("medical/$filename").bufferedReader().use { reader ->
                        reader.readLines().map { it.trim() }.filter { it.isNotEmpty() && it.length > 1 }.toSet()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "无法加载 $filename: ${e.message}")
                    emptySet()
                }
            }
            diseaseSet += loadDict("disease.txt")
            symptomSet += loadDict("symptom.txt")
            drugSet += loadDict("drug.txt")
            foodSet += loadDict("food.txt")
            checkSet += loadDict("check.txt")
            dictsLoaded = true
            Log.i(TAG, "字典加载: 疾病=${diseaseSet.size} 症状=${symptomSet.size} 药物=${drugSet.size}")
        } catch (e: Exception) {
            Log.e(TAG, "字典加载失败: ${e.message}")
        }
    }

    // ===== RAG 入口：查询医疗知识库，返回结构化上下文 =====
    suspend fun searchMedical(question: String): MedicalContext? = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext null

        try {
            loadDictionaries()

            // 实体识别
            val foundDiseases = diseaseSet.filter { question.contains(it) }
                .sortedByDescending { it.length }.take(3)
            val foundSymptoms = symptomSet.filter { question.contains(it) }
                .sortedByDescending { it.length }.take(3)
            val foundDrugs = drugSet.filter { question.contains(it) }
                .sortedByDescending { it.length }.take(3)

            val (entity, entityType) = when {
                foundDiseases.isNotEmpty() -> foundDiseases.first() to "disease"
                foundSymptoms.isNotEmpty() -> foundSymptoms.first() to "symptom"
                foundDrugs.isNotEmpty() -> foundDrugs.first() to "drug"
                else -> return@withContext null
            }

            val database = this@MedicalKBQA.db ?: return@withContext null

            // 疾病实体：查该疾病的完整信息
            if (entityType == "disease") {
                val cursor = database.rawQuery(
                    "SELECT * FROM diseases WHERE name LIKE ? LIMIT 1",
                    arrayOf("%$entity%")
                )
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    return@withContext null
                }
                val ctx = buildFullContext(cursor)
                cursor.close()
                return@withContext ctx
            }

            // 症状实体：找所有包含该症状的疾病，取第一个
            if (entityType == "symptom") {
                val symptomCursor = database.rawQuery(
                    "SELECT DISTINCT name FROM diseases WHERE symptom LIKE ? LIMIT 10",
                    arrayOf("%$entity%")
                )
                val relatedDiseases = mutableListOf<String>()
                while (symptomCursor.moveToNext()) {
                    relatedDiseases.add(symptomCursor.getString(0))
                }
                symptomCursor.close()
                if (relatedDiseases.isEmpty()) return@withContext null

                val cursor = database.rawQuery(
                    "SELECT * FROM diseases WHERE name = ? LIMIT 1",
                    arrayOf(relatedDiseases.first())
                )
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    return@withContext null
                }
                val ctx = buildFullContext(cursor)
                cursor.close()
                return@withContext ctx
            }

            // 药物实体：找所有包含该药物的疾病，取第一个
            if (entityType == "drug") {
                val drugCursor = database.rawQuery(
                    "SELECT DISTINCT name FROM diseases WHERE common_drug LIKE ? OR recommand_drug LIKE ? LIMIT 10",
                    arrayOf("%$entity%", "%$entity%")
                )
                val relatedDiseases = mutableListOf<String>()
                while (drugCursor.moveToNext()) {
                    relatedDiseases.add(drugCursor.getString(0))
                }
                drugCursor.close()
                if (relatedDiseases.isEmpty()) return@withContext null

                val cursor = database.rawQuery(
                    "SELECT * FROM diseases WHERE name = ? LIMIT 1",
                    arrayOf(relatedDiseases.first())
                )
                if (!cursor.moveToFirst()) {
                    cursor.close()
                    return@withContext null
                }
                val ctx = buildFullContext(cursor)
                cursor.close()
                return@withContext ctx
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "searchMedical 失败: ${e.message}", e)
            null
        }
    }

    /** 从 disease 行构建完整 MedicalContext */
    private fun buildFullContext(cursor: android.database.Cursor): MedicalContext {
        val disease = getString(cursor, "name")
        val symptoms = parseList(getDecompressed(cursor, "symptom"))
        val causes = getDecompressed(cursor, "cause")
        val prevents = getDecompressed(cursor, "prevent")
        val cureWays = parseList(getDecompressed(cursor, "cure_way"))
        val notFood = parseList(getDecompressed(cursor, "not_eat"))
        val doFood = parseList(getDecompressed(cursor, "do_eat"))
        val commonDrugs = parseList(getDecompressed(cursor, "common_drug"))
        val recommandDrugs = parseList(getDecompressed(cursor, "recommand_drug"))
        val allDrugs = (commonDrugs + recommandDrugs).distinct()
        val checks = parseList(getDecompressed(cursor, "checks"))
        val depts = parseList(getDecompressed(cursor, "cure_department"))
        val cureLast = getString(cursor, "cure_lasttime")
        val curedProb = getString(cursor, "cured_prob")
        val easyGet = getString(cursor, "easy_get")
        val desc = getDecompressed(cursor, "desc")

        return MedicalContext(
            disease = disease,
            symptom = if (symptoms.isNotEmpty()) "主要症状：${symptoms.take(10).joinToString("；")}" else "",
            cause = causes,
            prevent = prevents,
            cureWay = if (cureWays.isNotEmpty()) cureWays.take(8).joinToString("；") else "",
            food = buildString {
                if (notFood.isNotEmpty()) append("❌ 忌口：${notFood.take(8).joinToString("；")}\n")
                if (doFood.isNotEmpty()) append("✅ 推荐：${doFood.take(8).joinToString("；")}")
            }.trim(),
            drug = if (allDrugs.isNotEmpty()) allDrugs.take(10).joinToString("；") else "",
            check = if (checks.isNotEmpty()) checks.take(8).joinToString("；") else "",
            department = if (depts.isNotEmpty()) depts.joinToString("；") else "",
            other = buildString {
                if (desc.isNotEmpty()) append("【简介】$desc\n")
                if (cureLast.isNotEmpty()) append("【治疗周期】$cureLast\n")
                if (curedProb.isNotEmpty()) append("【治愈率】$curedProb\n")
                if (easyGet.isNotEmpty()) append("【易感人群】$easyGet")
            }.trim()
        )
    }

    private fun getString(cursor: android.database.Cursor, col: String): String =
        cursor.getString(cursor.getColumnIndexOrThrow(col)) ?: ""

    private fun getDecompressed(cursor: android.database.Cursor, col: String): String =
        decompress(getString(cursor, col))

    private fun decompress(b64: String?): String {
        if (b64.isNullOrEmpty()) return ""
        return try {
            val bytes = Base64.getDecoder().decode(b64)
            GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).readText()
        } catch (e: Exception) {
            b64
        }
    }

    private fun parseList(json: String?): List<String> {
        if (json.isNullOrEmpty() || json == "[]") return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===== 规则问答（保留，备用）=====
    suspend fun ask(question: String): MedicalAnswer = withContext(Dispatchers.IO) {
        if (!initialized) {
            return@withContext MedicalAnswer("医疗知识库未初始化。", 0f)
        }

        try {
            val qaCtx = classify(question) ?: return@withContext MedicalAnswer(
                "抱歉，我无法识别您的问题中的疾病或症状关键词。",
                0f
            )

            val answer = query(qaCtx)
            MedicalAnswer(answer, if (answer.isNotEmpty()) 0.85f else 0f)
        } catch (e: Exception) {
            Log.e(TAG, "问答失败: ${e.message}", e)
            MedicalAnswer("查询出错，请稍后重试。", 0f)
        }
    }

    private data class QAContext(val entity: String, val entityType: String, val questionType: String)

    private suspend fun classify(question: String): QAContext? {
        loadDictionaries()

        val foundDiseases = diseaseSet.filter { question.contains(it) }
            .sortedByDescending { it.length }.take(3)
        val foundSymptoms = symptomSet.filter { question.contains(it) }
            .sortedByDescending { it.length }.take(3)
        val foundDrugs = drugSet.filter { question.contains(it) }
            .sortedByDescending { it.length }.take(3)

        val (entity, entityType) = when {
            foundDiseases.isNotEmpty() -> foundDiseases.first() to "disease"
            foundSymptoms.isNotEmpty() -> foundSymptoms.first() to "symptom"
            foundDrugs.isNotEmpty() -> foundDrugs.first() to "drug"
            else -> return null
        }

        val qType = when (entityType) {
            "disease" -> classifyDiseaseQuestion(question)
            "symptom" -> "symptom_disease"
            "drug" -> "drug_disease"
            else -> return null
        }

        return QAContext(entity, entityType, qType)
    }

    private fun classifyDiseaseQuestion(q: String): String {
        val has = { words: List<String> -> words.any { q.contains(it) } }
        return when {
            has(PREVENT_QWDS) -> "disease_prevent"
            has(CAUSE_QWDS) -> "disease_cause"
            has(SYMPTOM_QWDS) -> "disease_symptom"
            has(ACCOMPANY_QWDS) -> "disease_acompany"
            has(FOOD_QWDS) -> if (listOf("不能吃", "忌口", "不可以吃", "不要吃", "避免").any { q.contains(it) }) "disease_not_food" else "disease_do_food"
            has(DRUG_QWDS) -> "disease_drug"
            has(CHECK_QWDS) -> "disease_check"
            has(LASTTIME_QWDS) -> "disease_lasttime"
            has(CUREWAY_QWDS) -> "disease_cureway"
            has(CUREPROB_QWDS) -> "disease_cureprob"
            has(EASYGET_QWDS) -> "disease_easyget"
            has(DEPARTMENT_QWDS) -> "disease_department"
            else -> "disease_desc"
        }
    }

    private fun query(ctx: QAContext): String {
        val database = db ?: return ""
        val disease = ctx.entity

        val cursor = database.rawQuery(
            "SELECT * FROM diseases WHERE name LIKE ? LIMIT 1",
            arrayOf("%$disease%")
        )
        if (!cursor.moveToFirst()) {
            cursor.close()
            return ""
        }

        val result = when (ctx.questionType) {
            "disease_symptom" -> {
                val items = parseList(getDecompressed(cursor, "symptom"))
                if (items.isNotEmpty()) "${disease}的主要症状：${items.take(10).joinToString("；")}" else ""
            }
            "symptom_disease" -> {
                val sc = database.rawQuery(
                    "SELECT DISTINCT name FROM diseases WHERE symptom LIKE ? LIMIT 10",
                    arrayOf("%$disease%")
                )
                val list = mutableListOf<String>()
                while (sc.moveToNext()) list.add(sc.getString(0))
                sc.close()
                if (list.isNotEmpty()) "「$disease」可能与以下疾病有关：${list.joinToString("；")}" else ""
            }
            "disease_cause" -> getDecompressed(cursor, "cause")
            "disease_prevent" -> getDecompressed(cursor, "prevent")
            "disease_acompany" -> {
                val items = parseList(getDecompressed(cursor, "acompany"))
                if (items.isNotEmpty()) "${disease}的并发症：${items.take(10).joinToString("；")}" else ""
            }
            "disease_not_food" -> {
                val items = parseList(getDecompressed(cursor, "not_eat"))
                if (items.isNotEmpty()) "${disease}患者应避免：${items.take(10).joinToString("；")}" else ""
            }
            "disease_do_food" -> {
                val items = parseList(getDecompressed(cursor, "do_eat"))
                if (items.isNotEmpty()) "${disease}患者适宜：${items.take(10).joinToString("；")}" else ""
            }
            "disease_drug" -> {
                val common = parseList(getDecompressed(cursor, "common_drug"))
                val recommand = parseList(getDecompressed(cursor, "recommand_drug"))
                val all = (common + recommand).distinct().take(10)
                if (all.isNotEmpty()) "${disease}常用药：${all.joinToString("；")}（用药请遵医嘱）" else ""
            }
            "disease_check" -> {
                val items = parseList(getDecompressed(cursor, "checks"))
                if (items.isNotEmpty()) "${disease}检查项目：${items.take(8).joinToString("；")}" else ""
            }
            "disease_lasttime" -> getString(cursor, "cure_lasttime")
            "disease_cureway" -> {
                val items = parseList(getDecompressed(cursor, "cure_way"))
                if (items.isNotEmpty()) "${disease}治疗方法：${items.take(8).joinToString("；")}" else ""
            }
            "disease_cureprob" -> getString(cursor, "cured_prob")
            "disease_easyget" -> getString(cursor, "easy_get")
            "disease_department" -> {
                val items = parseList(getDecompressed(cursor, "cure_department"))
                if (items.isNotEmpty()) "${disease}就诊科室：${items.joinToString("；")}" else ""
            }
            "disease_desc" -> {
                val d = getDecompressed(cursor, "desc")
                if (d.isNotEmpty()) "$disease：\n$d" else ""
            }
            "drug_disease" -> {
                val dc = database.rawQuery(
                    "SELECT DISTINCT name FROM diseases WHERE common_drug LIKE ? OR recommand_drug LIKE ? LIMIT 10",
                    arrayOf("%$disease%", "%$disease%")
                )
                val list = mutableListOf<String>()
                while (dc.moveToNext()) list.add(dc.getString(0))
                dc.close()
                if (list.isNotEmpty()) "「$disease」可用于治疗：${list.joinToString("；")}（用药请遵医嘱）" else ""
            }
            else -> ""
        }

        cursor.close()
        return result
    }

    fun destroy() {
        try {
            db?.close()
            db = null
            initialized = false
            dictsLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "销毁失败: ${e.message}")
        }
    }
}

/** 规则模式答案（备用） */
data class MedicalAnswer(val answer: String, val confidence: Float)
