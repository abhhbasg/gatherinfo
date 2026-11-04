#!/usr/bin/env python3
"""
跨平台 AI 工具需求采集器（仅使用公开、免登录的开放 API）

数据源（均为 GitHub 上的开源项目 / 开放接口）:
  - Reddit   : Arctic Shift 存档 API   https://github.com/ArthurHeitmann/arctic_shift
  - Hacker News: HN Algolia Search API  https://github.com/algolia/hn-search
  - V2EX     : 官方公开 API v1
  - Lemmy    : 联邦社交平台开放 API     https://github.com/LemmyNet/lemmy

思路: 抓取评论/帖子 -> 用"需求表达句式"正则过滤 (wish there was / is there an ai / 求一个 ...)
      -> 输出 data/raw.jsonl，供 analyze.py 归类。
"""
import json, re, time, sys, urllib.parse, urllib.request
from pathlib import Path

OUT = Path(__file__).parent / "data"
OUT.mkdir(exist_ok=True)
UA = {"User-Agent": "gatherinfo-research/0.1 (demand analysis)"}
AFTER = "2025-11-01"          # 近一年
AFTER_TS = 1761955200

def get(url, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.loads(r.read().decode("utf-8", "replace"))
        except Exception as e:
            print("  ! retry", i, url[:90], e, file=sys.stderr)
            time.sleep(8 * (i + 1))
    return None

# 需求表达句式（英文 + 中文）
DEMAND = re.compile(r"""(
    wish\s+(there\s+(was|were|is)|someone\s+would|i\s+had|ai\s+could|it\s+could)
  | is\s+there\s+(an?\s+)?(ai|tool|app|gpt|llm|way|service|extension|plugin)
  | are\s+there\s+any\s+(ai|tools?|apps?)
  | any\s+(ai|tool|app)s?\s+(that|which|to|for)
  | (looking|searching)\s+for\s+(an?\s+)?(ai|tool|app)
  | someone\s+(should|needs\s+to)\s+(build|make|create)
  | (i'?d|i\s+would|would)\s+(happily\s+)?pay\s+for
  | (shut\s+up\s+and\s+)?take\s+my\s+money
  | why\s+(can'?t|doesn'?t|won'?t)\s+(ai|chatgpt|claude|gemini|it)
  | need\s+(an?\s+)?(ai|tool|app)\s+(that|to|which|for)
  | does\s+(anyone|anybody)\s+know\s+(of\s+)?(an?\s+)?(ai|tool|app)
  | 求(一个|推荐|个)?.{0,8}(ai|AI|工具|插件|软件)
  | 有没有.{0,12}(ai|AI|工具|插件|软件|办法)
  | 希望(有|能)|要是.{0,6}(ai|AI).{0,4}能
  | 愿意(付费|花钱)|付费也(行|愿意)
)""", re.I | re.X)
AI = re.compile(r"\b(ai|a\.i\.|gpt|chatgpt|llm|claude|gemini|copilot|agent|automat\w*|machine learning)\b|人工智能|大模型|AI", re.I)

seen = set()
def emit(f, rec):
    key = (rec["platform"], rec["id"])
    if key in seen:
        return False
    text = rec["text"]
    if len(text) < 40 or len(text) > 3000:
        return False
    if not DEMAND.search(text) or not AI.search(text):
        return False
    seen.add(key)
    f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    return True

# ---------------- Reddit (Arctic Shift) ----------------
SUBS = [
    # 通用 AI 用户
    "ChatGPT", "ClaudeAI", "OpenAI", "artificial", "GeminiAI", "singularity", "LocalLLaMA", "ChatGPTPro",
    # 职业 / 场景用户（与平台无关的真实需求更多在这里）
    "smallbusiness", "Entrepreneur", "SaaS", "startups", "sidehustle", "freelance", "productivity",
    "marketing", "socialmedia", "content_marketing", "YouTubers", "NewTubers", "podcasting", "VideoEditing",
    "Teachers", "college", "GradSchool", "PhD", "students", "ADHD", "writing", "selfpublish",
    "realtors", "Accounting", "legaladvice", "LawFirm", "sales", "recruiting", "jobs", "resumes",
    "personalfinance", "ecommerce", "shopify", "AmazonSeller", "Etsy", "nursing", "medicine",
    "webdev", "nocode", "automation", "excel", "projectmanagement", "languagelearning", "parenting",
]
QUERIES = ["wish", "is there", "pay for", "tool that", "looking for"]
WINDOWS = [("2025-11-01", "2026-02-01"), ("2026-02-01", "2026-05-01"),
           ("2026-05-01", "2026-08-01"), ("2026-08-01", "2026-11-05")]

def _reddit_sub(sub):
    recs = []
    jobs = [("comments", q, w) for q in QUERIES for w in WINDOWS] + [("posts", q, WINDOWS[0][0:1] + ("2026-11-05",)) for q in QUERIES]
    for kind, q, (a, b) in jobs:
        field = "body" if kind == "comments" else "query"
        url = (f"https://arctic-shift.photon-reddit.com/api/{kind}/search?"
               + urllib.parse.urlencode({"subreddit": sub, field: q, "limit": 100, "after": a, "before": b, "sort": "desc"}))
        d = get(url, tries=3); time.sleep(1.5)
        for x in (d or {}).get("data") or []:
            text = x.get("body") if kind == "comments" else (x.get("title", "") + "\n" + (x.get("selftext") or ""))
            recs.append({"platform": "reddit", "community": sub, "kind": kind[:-1], "id": x.get("id"),
                   "score": x.get("score", 0), "created": x.get("created_utc"),
                   "url": "https://reddit.com" + (x.get("permalink") or f"/r/{sub}/comments/{(x.get('link_id') or '___')[3:]}/_/{x.get('id')}"),
                   "text": (text or "").strip()})
    return sub, recs

def reddit(f):
    from concurrent.futures import ThreadPoolExecutor
    n = 0
    with ThreadPoolExecutor(2) as ex:
        for sub, recs in ex.map(_reddit_sub, SUBS):
            got = sum(emit(f, r) for r in recs)
            f.flush(); n += got
            print(f"reddit r/{sub}: fetched {len(recs)} kept {got}", file=sys.stderr)
    return n

# ---------------- Hacker News (Algolia) ----------------
HN_Q = ["wish there was AI", "is there an AI tool", "I would pay for AI", "someone should build AI",
        "AI tool that", "looking for AI tool", "why can't AI", "wish AI could", "Ask HN AI tool"]
def hn(f):
    n = 0
    for q in HN_Q:
        for tags in ("comment", "story"):
            for page in range(3):
                url = ("https://hn.algolia.com/api/v1/search?" + urllib.parse.urlencode(
                    {"query": q, "tags": tags, "hitsPerPage": 100, "page": page,
                     "numericFilters": f"created_at_i>{AFTER_TS}"}))
                d = get(url)
                hits = (d or {}).get("hits") or []
                for h in hits:
                    text = h.get("comment_text") or ((h.get("title") or "") + "\n" + (h.get("story_text") or ""))
                    text = re.sub(r"<[^>]+>", " ", text or "")
                    import html; text = html.unescape(text).strip()
                    rec = {"platform": "hackernews", "community": "HN", "kind": tags, "id": h["objectID"],
                           "score": h.get("points") or 0, "created": h.get("created_at_i"),
                           "url": f"https://news.ycombinator.com/item?id={h['objectID']}", "text": text}
                    if emit(f, rec): n += 1
                if len(hits) < 100: break
    print(f"hackernews: {n}", file=sys.stderr)
    return n

# ---------------- V2EX ----------------
def v2ex(f):
    n = 0
    tids = set()
    for node in ("create", "ai", "openai", "programmer", "share", "qna", "chatgpt", "ideas", "macos", "apple"):
        for page in (1, 2, 3):
            d = get(f"https://www.v2ex.com/api/topics/show.json?node_name={node}&p={page}")
            if not isinstance(d, list): break
            for t in d:
                tids.add(t["id"])
                rec = {"platform": "v2ex", "community": node, "kind": "post", "id": t["id"],
                       "score": t.get("replies", 0), "created": t.get("created"), "url": t.get("url"),
                       "text": (t.get("title", "") + "\n" + (t.get("content") or "")).strip()}
                if emit(f, rec): n += 1
            time.sleep(1)
    for t in ("hot", "latest"):
        for x in get(f"https://www.v2ex.com/api/topics/{t}.json") or []:
            rec = {"platform": "v2ex", "community": x["node"]["name"], "kind": "post", "id": x["id"],
                   "score": x.get("replies", 0), "created": x.get("created"), "url": x.get("url"),
                   "text": (x.get("title", "") + "\n" + (x.get("content") or "")).strip()}
            if emit(f, rec): n += 1
    print(f"v2ex: {n}", file=sys.stderr)
    return n

# ---------------- Lemmy ----------------
def lemmy(f):
    n = 0
    for inst in ("lemmy.world", "lemmy.ml", "sh.itjust.works"):
        for q in ("wish AI", "AI tool", "is there an AI", "pay for AI"):
            for page in (1, 2, 3):
                for typ in ("Comments", "Posts"):
                    d = get(f"https://{inst}/api/v3/search?" + urllib.parse.urlencode(
                        {"q": q, "type_": typ, "limit": 50, "page": page, "sort": "TopYear"}))
                    items = (d or {}).get(typ.lower()) or []
                    for it in items:
                        if typ == "Comments":
                            c = it["comment"]; text = c["content"]; cid = c["ap_id"]
                        else:
                            c = it["post"]; text = c["name"] + "\n" + (c.get("body") or ""); cid = c["ap_id"]
                        rec = {"platform": "lemmy", "community": it["community"]["name"], "kind": typ[:-1].lower(),
                               "id": cid, "score": it.get("counts", {}).get("score", 0), "created": c.get("published"),
                               "url": cid, "text": text.strip()}
                        if emit(f, rec): n += 1
    print(f"lemmy: {n}", file=sys.stderr)
    return n

if __name__ == "__main__":
    which = sys.argv[1:] or ["reddit", "hn", "v2ex", "lemmy"]
    for w in which:
        with open(OUT / f"raw_{w}.jsonl", "w") as f:
            {"reddit": reddit, "hn": hn, "v2ex": v2ex, "lemmy": lemmy}[w](f)
