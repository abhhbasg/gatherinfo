#!/usr/bin/env python3
"""机会主题统计：对 data/raw_*.jsonl 用更精确的规则打标签，输出 report/themes.json"""
import json, re, glob, collections
from pathlib import Path
D = Path(__file__).parent
T = {
 "A 识别/过滤 AI 生成内容（真人认证）": r"ai[- ](slop|generated|cheat\w*|detect\w*|art|listings?|content|books?|music|songs?|resumes?|submissions?)|gptzero|turnitin|filter (out )?(the )?ai|hide ai|block ai|anti-ai|did not exist|fake (citations?|papers?|references?)|not disclosed|human[- ](made|written|reviewed)|is (this|it) ai",
 "B 记住我/品牌语气的持久记忆": r"remember(s)? (my|your|me|the)|memory|forgets?|brand voice|my (own )?(voice|tone|style)|every single time|re-?explain|starts? over|lose(s)? context|persistent context|organi[sz]e (my )?(ai )?(conversations|chats)|find(ing)? the conversation",
 "C 能真正办事的代理（不只是聊天）": r"actually (does|do|starts?|process\w*|takes? action)|not just (summari\w*|copywriting|chat\w*|explain\w*)|on my behalf|do (it|things) for me|across (your|my) tools|process returns|admin work|boring .* work|fill (out|in) (forms?|applications?)|book (a|my)",
 "D 视频/音频素材的语义检索与自动剪辑": r"b-?roll|raw footage|hours of (footage|video)|find (one|a|the) .{0,20}(moment|clip|shot)|auto(matically)? (edit|cut|clip|sync)|clips? into|repurpos\w*|shorts from|talking-head|beat of|lip-?sync|subtitles?|captions?|transcri\w+|watch videos? and podcasts?",
 "E 多语言本地化（视频/内容翻译配音）": r"translat\w+|locali[sz]\w+|dubb\w*|voice clon\w*|spanish|japanese|thai|german|polish|other languages|multilingual",
 "F 个人排程/督促/ADHD 执行教练": r"schedul\w+|planner|daily plan|accountab\w+|procrastinat\w*|adhd|deadline|to-?do|what i \*?planned|time block\w*|coach",
 "G 文档/票据/合同 自动处理": r"contract review|contracts?|invoic\w+|receipts?|pdf|spreadsheet|excel|documents? (organi\w+|misplaced)|millions of files|data entry|paperwork|letters? (for|to|related)|formal letters?|government documents?",
 "H 可信研究：带出处、不编造": r"hallucinat\w*|cit(e|ation)s?|sources?|made[- ]up|fabricat\w*|fact[- ]check\w*|did not exist|verify|accurate|single source of truth",
 "I 个性化学习/讲解生成": r"explainer\w*|explain (things|it) like|cheat ?sheets?|flashcards?|exam prep|study guide|tutor\w*|learn (about|to)|on audio|listen in the bus|lecture",
 "J 小商家获客与内容持续生产": r"ad creatives?|ad copy|graphics|content ideas|what do i post|posting consistency|leads?|follow[- ]?ups?|cold (email|outreach)|google business|reviews? (management|responding)|social media (tool|manager|management)",
 "K 隐私/本地运行": r"privacy|private|locally|offline|on[- ]device|self[- ]host\w*|sensitive (info|data)|my data",
 "L 用量/成本透明与订阅疲劳": r"token (spend|usage|converter)|usage limits?|5[- ]hour|credits? burn|too many (tools|subscriptions|apps)|subscription fatigue|cancel\w* (all )?(my )?(monthly )?sub\w*|one (app|tool) (for|that)|all[- ]in[- ]one|both claude .* chatgpt",
}
T = {k: re.compile(v, re.I) for k, v in T.items()}
recs = [json.loads(l) for f in glob.glob(str(D / "data/raw_*.jsonl")) for l in open(f)]
out = []
for name, rx in T.items():
    hits = [r for r in recs if rx.search(r["text"])]
    plats = collections.Counter(r["platform"] for r in hits)
    comms = collections.Counter(f'{r["platform"]}:{r["community"]}' for r in hits)
    top = sorted(hits, key=lambda r: -(r.get("score") or 0))[:12]
    out.append({"theme": name, "n": len(hits), "platforms": dict(plats), "n_communities": len(comms),
                "score_sum": sum(max(0, int(r.get("score") or 0)) for r in hits),
                "top_communities": comms.most_common(8),
                "examples": [{"score": r.get("score"), "where": f'{r["platform"]}/{r["community"]}', "url": r["url"],
                              "text": r["text"][:400].replace("\n", " ")} for r in top]})
out.sort(key=lambda x: -x["score_sum"])
json.dump({"total": len(recs), "by_platform": dict(collections.Counter(r["platform"] for r in recs)), "themes": out},
          open(D / "report/themes.json", "w"), ensure_ascii=False, indent=1)
for o in out:
    print(f'{o["n"]:4d} 条  平台{len(o["platforms"])} 社区{o["n_communities"]:3d} 热度{o["score_sum"]:5d}  {o["theme"]}  {o["platforms"]}')
    print("     ", ", ".join(c.split(":")[1] for c, _ in o["top_communities"]))
