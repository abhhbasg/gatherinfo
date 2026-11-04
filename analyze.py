#!/usr/bin/env python3
"""
把 data/raw_*.jsonl 中的"需求表达"按与平台无关的需求主题归类，统计
  - 声量: 命中条数
  - 广度: 覆盖多少个平台 / 社区(子版块)  ← 判断"平台无关、广泛存在"
  - 热度: 点赞/分数之和
  - 付费意愿: 文中出现 pay / 付费 等的条数
输出 data/summary.json 与 data/samples_<topic>.jsonl
"""
import json, re, glob, collections
from pathlib import Path

D = Path(__file__).parent / "data"

TOPICS = {
 "记忆与长期上下文 (AI 记住我/项目)": r"\b(remember|memory|memories|forget|forgets|context window|long[- ]term|persistent|across (chats|sessions|conversations)|lose context|starts? over)\b|记住|记忆|上下文",
 "可信 / 不胡编 / 带出处": r"\b(hallucinat\w*|make[s]? (stuff|things) up|made[- ]up|fabricat\w*|cite|citation|sources?|fact[- ]check\w*|accurate|accuracy|verify|trust)\b|幻觉|胡编|瞎编|出处|准确",
 "个人助理 / 邮件日程收件箱": r"\b(email|inbox|gmail|outlook|calendar|schedul\w*|meeting|appointment|remind\w*|to-?do|todo|personal assistant|executive assistant)\b|邮件|日程|会议|提醒|待办|助理",
 "会议 / 通话 / 语音转录总结": r"\b(transcri\w*|meeting notes|notes? taker|summari[sz]e (the )?(call|meeting|video|podcast)|voice memo|dictat\w*|record(ing)? (calls|meetings))\b|转录|字幕|会议纪要|录音",
 "文档 / PDF / 表格处理与数据录入": r"\b(pdf|spreadsheet|excel|csv|invoice|receipt|form[s]?|data entry|extract\w*|parse|ocr|scan\w*|document[s]?|paperwork|template)\b|表格|发票|报销|文档|识别",
 "内容创作 / 社媒运营 / 剪辑": r"\b(video edit\w*|edit(ing)? videos?|clips?|shorts|reels|tiktok|thumbnail|caption[s]?|subtitle|content creat\w*|social media|post(s|ing)? (to|on)|youtube|instagram|repurpos\w*|b-roll)\b|剪辑|短视频|文案|封面|小红书|抖音|运营",
 "求职 / 简历 / 申请自动化": r"\b(resume|cv|cover letter|job (application|search|hunt\w*)|apply(ing)? (to|for) jobs|interview prep|linkedin)\b|简历|求职|面试",
 "学习 / 辅导 / 个性化讲解": r"\b(learn\w*|tutor\w*|study|studying|explain\w*|flashcard\w*|quiz|homework|lecture|course|teach\w*|language learning)\b|学习|讲解|辅导|背单词|课程",
 "写作润色 / 去 AI 味 / 保持个人风格": r"\b(my (own )?(voice|tone|style)|sound(s)? like (me|ai|a robot)|humani[sz]\w*|ai detector|detect\w* ai|rewrite|proofread\w*|grammar|ghost ?writ\w*)\b|润色|去ai味|AI味|改写|语气",
 "搜索 / 研究 / 信息过滤": r"\b(research|search(ing)?|find (me|all|the best)|deep research|compare (prices|products)|news(letter)?|filter (out|my)|feed|aggregat\w*|monitor\w*|alert[s]?)\b|搜索|调研|资讯|信息流|过滤|监控",
 "电脑 / 浏览器 / 手机操作代理 (代我点)": r"\b(computer use|browser (agent|automation|extension)|fill (out|in) (forms?|applications?)|click\w*|navigate|book (a|my|flights?|tickets?)|on my behalf|do it for me|automat(e|ing) (the|my|this) (task|workflow|process)|repetitive)\b|代我|帮我操作|自动点击|自动填",
 "文件 / 照片 / 知识库整理": r"\b(organi[sz]e (my )?(files|photos|notes|documents|bookmarks)|photos?|folder[s]?|notes app|obsidian|notion|second brain|knowledge base|tag(ging)?|bookmark\w*|my own (docs|data|files))\b|整理|相册|笔记|知识库|收藏",
 "编程 / 无代码建应用": r"\b(code|coding|codebase|developer|bug[s]?|refactor|vibe cod\w*|no-?code|build (an? )?(app|website)|website builder|wordpress|script)\b|编程|写代码|建站|小程序",
 "客服 / 销售线索 / 小企业运营": r"\b(customer (service|support)|leads?|crm|follow[- ]?ups?|cold (email|outreach)|bookkeeping|quickbooks|inventory|quotes?|estimat\w*|invoic\w*|small business|clients?)\b|客服|获客|线索|记账|库存|报价",
 "隐私 / 本地运行 / 数据不出门": r"\b(privacy|private|local(ly)?|offline|on[- ]device|self[- ]host\w*|my data|sell (my )?data|without (sending|uploading))\b|隐私|本地|离线|私有化",
 "价格 / 订阅疲劳 / 一个工具统一": r"\b(subscription[s]?|too expensive|cheaper|pricing|one (app|tool|place)|all[- ]in[- ]one|consolidat\w*|too many (tools|apps|subscriptions)|switch(ing)? between)\b|订阅|太贵|一站式|聚合",
 "健康 / 饮食 / 情绪陪伴": r"\b(health|diet|meal plan\w*|calorie[s]?|workout|fitness|therap\w*|mental health|anxiety|adhd|journal\w*|sleep|medical|symptom[s]?)\b|健康|饮食|减肥|健身|情绪|陪伴",
 "购物 / 理财 / 生活事务": r"\b(budget\w*|expenses?|tax(es)?|bills?|shopping|deals?|grocer\w*|travel (plan\w*|itinerar\w*)|trip|insurance|claims?)\b|理财|报税|购物|旅行|账单",
}
TOPICS = {k: re.compile(v, re.I) for k, v in TOPICS.items()}
PAY = re.compile(r"\bpay(ing)?\b|take my money|\$\d+|subscri\w+|付费|花钱|愿意买", re.I)

def main():
    recs = []
    for fn in glob.glob(str(D / "raw_*.jsonl")):
        for line in open(fn):
            recs.append(json.loads(line))
    stats = collections.defaultdict(lambda: {"n": 0, "platforms": collections.Counter(),
                                             "communities": collections.Counter(), "score": 0, "pay": 0, "items": []})
    for r in recs:
        t = r["text"]
        hit = False
        for name, rx in TOPICS.items():
            if rx.search(t):
                hit = True
                s = stats[name]
                s["n"] += 1; s["platforms"][r["platform"]] += 1
                s["communities"][f'{r["platform"]}:{r["community"]}'] += 1
                s["score"] += max(0, int(r.get("score") or 0))
                if PAY.search(t): s["pay"] += 1
                s["items"].append(r)
        if not hit:
            stats["(未归类)"]["n"] += 1
    out = []
    for name, s in stats.items():
        if name == "(未归类)":
            out.append({"topic": name, "n": s["n"]}); continue
        top = sorted(s["items"], key=lambda r: -(r.get("score") or 0))
        slug = re.sub(r"\W+", "_", name)[:30]
        with open(D / f"samples_{slug}.jsonl", "w") as f:
            for r in top[:60]:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        out.append({"topic": name, "n": s["n"], "score": s["score"], "pay_mentions": s["pay"],
                    "n_platforms": len(s["platforms"]), "platforms": dict(s["platforms"]),
                    "n_communities": len(s["communities"]),
                    "top_communities": s["communities"].most_common(10), "samples_file": f"samples_{slug}.jsonl"})
    out.sort(key=lambda x: -x["n"])
    json.dump({"total_records": len(recs),
               "by_platform": dict(collections.Counter(r["platform"] for r in recs)),
               "topics": out}, open(D / "summary.json", "w"), ensure_ascii=False, indent=1)
    print(f"total {len(recs)}")
    for o in out:
        print(f'{o["n"]:5d}  plat={o.get("n_platforms","-")} comm={o.get("n_communities","-"):>3} '
              f'score={o.get("score","-"):>6} pay={o.get("pay_mentions","-"):>4}  {o["topic"]}')

if __name__ == "__main__":
    main()
