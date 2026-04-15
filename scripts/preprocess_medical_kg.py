#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
将 QASystemOnMedicalKG 的 medical.json 转换为精简 SQLite 数据库。
策略：单表 + GZIP 压缩长文本字段，大幅压缩体积。
输出：android/CunYiAI/src/main/assets/medical/medical_kb.db
"""
import json
import sqlite3
import os
import zlib
import base64

# 配置
DB_PATH = "/Users/macosx/Desktop/cunyi-ai/android/CunYiAI/src/main/assets/medical/medical_kb.db"
JSON_PATH = "/tmp/QASystemOnMedicalKG/data/medical.json"
os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)

# 核心疾病列表（用于精确匹配保留）
CORE_DISEASES = {
    "感冒", "流行性感冒", "发烧", "咳嗽", "肺炎", "支气管炎", "支气管肺炎",
    "哮喘", "慢阻肺", "肺结核", "肺癌", "肺气肿", "胸膜炎", "肺脓肿",
    "胃炎", "胃溃疡", "胃癌", "十二指肠溃疡", "胃下垂", "急性胃肠炎",
    "慢性胃炎", "萎缩性胃炎", "浅表性胃炎", "功能性消化不良",
    "肠炎", "结肠炎", "溃疡性结肠炎", "克罗恩病", "肠易激综合征",
    "阑尾炎", "腹膜炎", "胆囊炎", "胆结石", "胆囊结石", "脂肪肝",
    "肝硬化", "肝癌", "肝炎", "乙肝", "丙肝", "酒精肝", "甲肝",
    "肾炎", "肾小球肾炎", "肾盂肾炎", "肾衰竭", "尿毒症", "肾结石",
    "膀胱炎", "尿道炎", "前列腺炎", "前列腺增生", "前列腺癌",
    "高血压", "低血压", "高血压病", "心脏病", "冠心病", "心绞痛",
    "心肌梗死", "急性心肌梗死", "心律失常", "心房颤动", "心衰",
    "心力衰竭", "先天性心脏病", "心肌炎", "心包炎", "动脉硬化",
    "脑梗死", "脑出血", "脑卒中", "中风", "偏头痛", "紧张性头痛",
    "三叉神经痛", "面神经炎", "坐骨神经痛", "神经衰弱", "焦虑症",
    "抑郁症", "躁狂症", "精神分裂症", "双相情感障碍", "失眠",
    "帕金森病", "阿尔茨海默病", "老年痴呆", "癫痫", "脑膜炎", "脑炎",
    "皮肤过敏", "湿疹", "荨麻疹", "银屑病", "痤疮", "脂溢性皮炎",
    "神经性皮炎", "接触性皮炎", "带状疱疹", "水痘", "手足口病",
    "过敏性鼻炎", "鼻炎", "慢性鼻炎", "鼻窦炎", "咽炎", "喉炎",
    "扁桃体炎", "中耳炎", "外耳道炎", "耳鸣", "突发性耳聋",
    "近视", "远视", "散光", "白内障", "青光眼", "结膜炎", "角膜炎",
    "葡萄膜炎", "视网膜病变", "糖尿病", "糖尿病肾病", "糖尿病足",
    "甲亢", "甲减", "甲状腺炎", "甲状腺结节", "甲状腺癌",
    "痛风", "高尿酸血症", "风湿热", "类风湿关节炎", "骨关节炎",
    "强直性脊柱炎", "系统性红斑狼疮", "干燥综合征", "皮肌炎",
    "骨质疏松", "骨质增生", "腰椎间盘突出", "颈椎病", "骨折",
    "股骨头坏死", "骨髓炎", "缺铁性贫血", "巨幼细胞性贫血",
    "再生障碍性贫血", "白血病", "淋巴瘤", "多发性骨髓瘤",
    "紫癜", "血友病", "血小板减少性紫癜", "宫颈炎", "宫颈癌",
    "子宫肌瘤", "子宫内膜异位症", "卵巢囊肿", "卵巢癌", "乳腺癌",
    "乳腺炎", "乳腺增生", "盆腔炎", "附件炎", "阴道炎", "月经不调",
    "痛经", "更年期综合征", "不孕症", "自然流产", "妊娠高血压",
    "小儿腹泻", "小儿肺炎", "小儿感冒", "小儿发热", "新生儿黄疸",
    "早产", "佝偻病", "小儿贫血", "梅毒", "淋病", "尖锐湿疣",
    "生殖器疱疹", "艾滋病", "HPV感染", "细菌性肺炎", "病毒性肺炎",
    "支原体肺炎", "新冠肺炎", "甲流", "诺如病毒", "狂犬病", "破伤风",
    "恙虫病", "伤寒", "霍乱", "痢疾", "细菌性痢疾", "疟疾",
    "蛔虫病", "钩虫病", "绦虫病", "血吸虫病", "蛲虫病",
    "痔疮", "肛裂", "肛瘘", "肛周脓肿", "便秘", "腹泻", "便血",
    "尿路感染", "尿路结石", "肾积水", "膀胱结石", "鞘膜积液",
    "静脉曲张", "血栓性静脉炎", "脉管炎", "动脉栓塞", "动脉瘤",
    "疝气", "腹股沟疝", "脐疝", "切口疝", "腹壁疝",
    "烧伤", "烫伤", "电击伤", "冻伤", "中暑", "溺水", "一氧化碳中毒",
    "药物中毒", "酒精中毒", "食物中毒", "有机磷中毒", "安眠药中毒",
    "休克", "昏迷", "晕厥", "低血糖", "高血糖", "代谢综合征",
    "营养不良", "肥胖症", "厌食症", "贪食症", "维生素缺乏症",
    "缺钙", "缺铁", "缺锌", "电解质紊乱", "酸碱失衡",
    "败血症", "脓毒症", "感染性休克", "多器官功能障碍",
    "肺栓塞", "气胸", "血胸", "胸腔积液",
    "肺水肿", "急性呼吸窘迫综合征", "呼吸衰竭", "肝衰竭", "多器官衰竭",
}


def gzip_text(text):
    """压缩长文本（用于 desc/cause/prevent/cost_money）"""
    if not text:
        return ""
    compressed = zlib.compress(text.encode("utf-8"), level=6)
    return base64.b64encode(compressed).decode("ascii")


def safe_json_list(val):
    if not val:
        return "[]"
    if isinstance(val, list):
        return json.dumps(val, ensure_ascii=False)
    return json.dumps(val if val else [], ensure_ascii=False)


def safe_str(val):
    return val or ""


def load_diseases():
    """加载并过滤疾病数据"""
    diseases = []
    skip_count = 0
    with open(JSON_PATH, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
                name = d.get("name", "")
                desc = d.get("desc", "")

                # 过滤策略：核心列表精确匹配 或 有完整描述
                has_meaningful_desc = bool(desc and len(desc) >= 50)
                is_core = name in CORE_DISEASES

                if not (has_meaningful_desc or is_core):
                    skip_count += 1
                    continue

                diseases.append(d)
            except json.JSONDecodeError:
                skip_count += 1
    print(f"加载 {len(diseases)} 条疾病，跳过 {skip_count} 条（原始 8808 条）")
    return diseases


def build_db(diseases):
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)

    conn = sqlite3.connect(DB_PATH)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA page_size=4096")

    # 单表设计，压缩长文本
    conn.execute("""
        CREATE TABLE diseases (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            desc TEXT,          -- GZIP 压缩
            category TEXT,
            prevent TEXT,       -- GZIP 压缩
            cause TEXT,         -- GZIP 压缩
            easy_get TEXT,
            cure_department TEXT,
            cure_way TEXT,
            cure_lasttime TEXT,
            cured_prob TEXT,
            cost_money TEXT,    -- GZIP 压缩
            get_way TEXT,
            symptom TEXT,
            acompany TEXT,
            not_eat TEXT,
            do_eat TEXT,
            common_drug TEXT,
            recommand_drug TEXT,
            checks TEXT,
            yibao_status TEXT,
            get_prob TEXT
        )
    """)
    conn.execute("CREATE INDEX idx_disease_name ON diseases(name)")

    # 去重
    seen = set()
    unique_diseases = []
    for d in diseases:
        name = d.get("name", "")
        if name and name not in seen:
            seen.add(name)
            unique_diseases.append(d)
    diseases = unique_diseases
    print(f"去重后 {len(diseases)} 条疾病")

    records = []
    for d in diseases:
        records.append((
            safe_str(d.get("name")),
            gzip_text(safe_str(d.get("desc"))),           # 压缩
            safe_json_list(d.get("category")),
            gzip_text(safe_str(d.get("prevent"))),        # 压缩
            gzip_text(safe_str(d.get("cause"))),          # 压缩
            safe_str(d.get("easy_get")),
            safe_json_list(d.get("cure_department")),
            safe_json_list(d.get("cure_way")),
            safe_str(d.get("cure_lasttime")),
            safe_str(d.get("cured_prob")),
            gzip_text(safe_str(d.get("cost_money"))),     # 压缩
            safe_str(d.get("get_way")),
            safe_json_list(d.get("symptom")),
            safe_json_list(d.get("acompany")),
            safe_json_list(d.get("not_eat")),
            safe_json_list(d.get("do_eat") or d.get("recommand_eat")),
            safe_json_list(d.get("common_drug")),
            safe_json_list(d.get("recommand_drug")),
            safe_json_list(d.get("check")),
            safe_str(d.get("yibao_status")),
            safe_str(d.get("get_prob"))
        ))

    conn.executemany(
        """INSERT INTO diseases (name, desc, category, prevent, cause, easy_get,
        cure_department, cure_way, cure_lasttime, cured_prob, cost_money, get_way,
        symptom, acompany, not_eat, do_eat, common_drug, recommand_drug, checks,
        yibao_status, get_prob) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        records
    )
    conn.commit()

    cur = conn.execute("SELECT COUNT(*) FROM diseases")
    disease_count = cur.fetchone()[0]

    db_size = os.path.getsize(DB_PATH) / 1024 / 1024
    conn.close()
    print(f"\n数据库构建完成!")
    print(f"  疾病数量: {disease_count}")
    print(f"  文件大小: {db_size:.1f} MB")
    print(f"  输出路径: {DB_PATH}")


if __name__ == "__main__":
    diseases = load_diseases()
    build_db(diseases)
