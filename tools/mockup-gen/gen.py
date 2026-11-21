import json, os, re, sys
sys.path.insert(0, os.path.dirname(__file__))
from lib import page, raw_page, sub2_html, settings_nav_html, MODULES
import s1, s2, s3, s4

PROJ = os.path.join(os.path.dirname(__file__), "..", "..", "docs", "design")
written = []

screens = {}
for m in (s1, s2, s3, s4):
    screens.update(m.S)
for name, s in screens.items():
    open(os.path.join(PROJ, name + ".dc.html"), "w").write(page(name, s))
    written.append(name)
for name, (title, css, inner, w, h) in s4.RAW.items():
    inner = inner.replace('<a href="#" style="font-size: 13px">비밀번호를 잊으셨나요?</a>', '<a href="Login.dc.html" style="font-size: 13px">비밀번호를 잊으셨나요?</a>')
    inner = inner.replace('<a class="card" href="#"', '<a class="card" href="Main.dc.html"')
    open(os.path.join(PROJ, name + ".dc.html"), "w").write(raw_page(title, css, inner, w, h))
    written.append(name)

# ---- rewire existing files ----
new = set(written)
SUB2 = re.compile(r'    <nav class="sub2" aria-label="(\S+) 메뉴"[^>]*>\s*(.*?)\s*</nav>', re.S)
SET = re.compile(r'      <nav class="sub" aria-label="설정 메뉴"[^>]*>.*?</nav>', re.S)
changed = []
for fn in sorted(os.listdir(PROJ)):
    if not fn.endswith(".dc.html"):
        continue
    stem = fn[:-8]
    if stem in new:
        continue
    path = os.path.join(PROJ, fn)
    src = open(path).read()
    out = src

    def repl(m):
        mod = m.group(1)
        if mod not in MODULES:
            return m.group(0)
        act = re.search(r'class="on"[^>]*>([^<]+)</a>', m.group(2))
        return sub2_html(mod, act.group(1) if act else None, stem)
    out = SUB2.sub(repl, out)

    def srepl(m):
        act = re.search(r'class="on"[^>]*>([^<]+)</a>', m.group(0))
        return settings_nav_html(act.group(1) if act else None, stem)
    out = SET.sub(srepl, out)

    if stem == "CostLedger" and 'class="sub2"' not in out:
        out = out.replace("    </header>\n", "    </header>\n" + sub2_html("비용", "비용 현황", stem) + "\n", 1)
    if stem == "Integrations" and 'class="sub2"' not in out:
        out = out.replace("    </header>\n", "    </header>\n" + sub2_html("연동", "연동 현황", stem) + "\n", 1)
    if stem == "Inventory":
        out = out.replace('<a href="#" style="font-size: 13px">재고원장 전체 보기</a>', '<a href="Ledger.dc.html" style="font-size: 13px">재고원장 전체 보기</a>')
    if stem == "BulkJobs":
        out = out.replace('<a href="#">실패 사유</a>', '<a href="WorkQueue.dc.html">실패 사유</a>')
    if stem == "Profit":
        out = out.replace('<a href="#">27,000</a>', '<a href="Orders.dc.html">27,000</a>')
        out = re.sub(r'<a href="#">(−[\d,]+)</a>', r'<a href="CostLedger.dc.html">\1</a>', out)
    if stem == "ProfitSummary":
        out = re.sub(r'<a href="#">([\d,]+)</a>', r'<a href="ProfitDimension.dc.html">\1</a>', out)
    if out != src:
        open(path, "w").write(out)
        changed.append(stem)

print("written", len(written), written)
print("changed", len(changed), changed)

# ---- link check ----
files = {f[:-8] for f in os.listdir(PROJ) if f.endswith(".dc.html")}
canvas = json.load(open(os.path.join(PROJ, "canvas.json")))
boards = {k[:-8] for k in canvas["boards"]}
print("boards missing file:", sorted(boards - files))
print("files not on canvas:", sorted(files - boards))
bad, hashes = [], []
for f in sorted(files):
    src = open(os.path.join(PROJ, f + ".dc.html")).read()
    for t in re.findall(r'href="([^"]+)"', src):
        if t.startswith("http") or t.startswith("./"):
            continue
        if t == "#":
            hashes.append(f)
        elif t.endswith(".dc.html") and t[:-8] not in files:
            bad.append((f, t))
print("broken:", bad)
from collections import Counter
print("remaining #:", Counter(hashes))
