# Shared template + helpers for the remaining mockup screens.
import html as _h

CSS = """body{margin:0;background:#F4F5F7}
a{color:#2350C8}a:hover{color:#163A99}
.nav a{display:flex;align-items:center;min-height:36px;padding:0 12px;border-radius:6px;color:#2B3139;text-decoration:none;font-size:14px}
.nav a:hover{background:#ECEEF2;color:#15191F}
.nav a.on{background:#EAF0FD;color:#163A99;font-weight:600}
.navh{font-size:12px;font-weight:600;color:#59616C;padding:14px 12px 6px}
.sub2 a{display:inline-flex;align-items:center;min-height:38px;padding:0 12px;border-bottom:2px solid transparent;color:#59616C;text-decoration:none;font-size:14px;white-space:nowrap}
.sub2 a.on{border-bottom-color:#2350C8;color:#15191F;font-weight:600}
.sub a{display:block;padding:8px 10px;border-radius:6px;color:#2B3139;text-decoration:none;font-size:14px}
.sub a.on{background:#15191F;color:#fff;font-weight:600}
.card{background:#fff;border:1px solid #E3E6EA;border-radius:10px}
.chip{display:inline-flex;align-items:center;height:22px;padding:0 8px;border-radius:999px;font-size:12px;font-weight:600;white-space:nowrap}
.c-ok{background:#E5F3EA;color:#1D6A3A}.c-info{background:#EAF0FD;color:#1E4BB8}.c-warn{background:#FFF0DC;color:#8A4300}.c-err{background:#FDEBEB;color:#A11A1A}.c-mute{background:#ECEEF2;color:#3D444D}
.btn{display:inline-flex;align-items:center;justify-content:center;min-height:36px;padding:0 14px;border-radius:7px;border:1px solid #CDD2D9;background:#fff;color:#15191F;font:inherit;font-size:14px;font-weight:500;cursor:pointer;text-decoration:none}
.btn.pri{background:#2350C8;border-color:#2350C8;color:#fff}
.btn.dan{border-color:#C93A3A;color:#A11A1A}
.tab{min-height:36px;padding:0 12px;border:0;border-bottom:2px solid transparent;background:none;font:inherit;font-size:14px;color:#59616C;cursor:pointer}
.tab.on{border-bottom-color:#2350C8;color:#15191F;font-weight:600}
table{border-collapse:collapse;width:100%;font-size:13px}
th{text-align:left;font-weight:600;color:#59616C;font-size:12px;padding:10px;border-bottom:1px solid #E3E6EA;background:#FAFBFC;white-space:nowrap}
td{padding:10px;border-bottom:1px solid #EEF0F3;color:#15191F;vertical-align:top}
tr.sel td{background:#F2F6FE}
tr.tot td{font-weight:700;background:#FAFBFC}
.ctx{font-size:12px;color:#59616C}
.num{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}
.pos{color:#1D6A3A}.neg{color:#A11A1A}
.inp{min-height:36px;border:1px solid #CDD2D9;border-radius:7px;padding:0 10px;font:inherit;font-size:14px;color:#15191F;background:#fff;box-sizing:border-box}
.row{display:flex;justify-content:space-between;gap:8px;font-size:13px;padding:7px 0;border-bottom:1px solid #EEF0F3}
.kpi{padding:12px;display:flex;flex-direction:column;gap:4px}
.kpi b{font-size:20px;font-variant-numeric:tabular-nums}
.bar{height:8px;border-radius:4px;background:#E7EAEE;overflow:hidden}
.bar span{display:block;height:100%;background:#2350C8}
.note{border-radius:8px;padding:10px 12px;font-size:13px;line-height:1.6}
.n-warn{background:#FFF6E8;color:#6B3500}.n-err{background:#FDEBEB;color:#7A1414}.n-info{background:#EEF3FD;color:#1A3A85}
.fld{display:flex;flex-direction:column;gap:4px;font-size:12px;color:#59616C}
.grid2{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:12px}
.step{flex:1 1 110px;display:flex;align-items:center;gap:8px;font-size:13px;padding:10px 12px;border-radius:8px;background:#F7F8FA}
.step.on{background:#EAF0FD;color:#163A99;font-weight:600}
.step.done{background:#E5F3EA;color:#1D6A3A}
.dot{width:22px;height:22px;border-radius:50%;background:#2350C8;color:#fff;display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;flex:0 0 22px}
.tg{display:inline-block;width:34px;height:20px;border-radius:10px;background:#CDD2D9;position:relative;vertical-align:middle}
.tg.on{background:#2350C8}
.tg::after{content:"";position:absolute;top:2px;left:2px;width:16px;height:16px;border-radius:50%;background:#fff}
.tg.on::after{left:16px}"""

NAV = [
    ("home", "홈", "Main"), ("wq", "업무함", "WorkQueue"),
    ("@", "판매·주문"), ("products", "상품", "Products"), ("orders", "주문", "Orders"), ("cs", "CS", "CsInbox"),
    ("@", "재고·물류"), ("inventory", "재고", "Inventory"), ("logistics", "물류·3PL", "Logistics"), ("procurement", "발주·매입", "Procurement"),
    ("@", "재무"), ("settlement", "정산", "Settlement"), ("cost", "비용·원가", "CostLedger"), ("profit", "손익", "ProfitSummary"),
    ("@", "분석"), ("analytics", "분석", "Analytics"), ("insights", "인사이트", "Insights"),
    ("@", "관리"), ("integrations", "연동 관리", "Integrations"), ("settings", "설정", "BusinessEntities"),
]

MODULES = {
    "상품": [("상품마스터", "Products"), ("SKU", "Skus"), ("묶음·세트", "Bundles"), ("GWP·증정", "Gwp"), ("채널상품·연결", "ChannelListings"), ("가격·판매정책", "PricePolicy"), ("품질 점검", "QualityCheck"), ("일괄 작업", "BulkJobs")],
    "주문": [("전체 주문", "Orders"), ("배송관리", "Shipments"), ("주문 보류", "OrderHolds"), ("취소", "Claims"), ("교환", "Exchange"), ("반품", "Claims"), ("수동·B2B 주문", "ManualOrder"), ("수집·처리 오류", "WorkQueue")],
    "CS": [("CS 통합함", "CsInbox"), ("문의", "CsInbox"), ("배송문제", "Shipments"), ("상담이력", "CsInbox"), ("답변 템플릿", "CsTemplates"), ("처리 기준", "CsTemplates"), ("현황", "CsStats")],
    "재고": [("통합재고", "Inventory"), ("거점별 재고", "Inventory"), ("재고원장", "Ledger"), ("예약·할당", "Reservations"), ("재고이동", "Transfers"), ("재고조정·실사", "StockCount"), ("LOT·유통기한", "LotExpiry"), ("비가용·불용재고", "Unsellable"), ("채널 재고배분", "Allocation"), ("재고정책", "StockPolicy")],
    "물류": [("운영현황", "LogisticsOverview"), ("출고관리", "Logistics"), ("입고관리", "Inbound"), ("반품물류", "ReturnLogistics"), ("WMS 작업", "WmsMobile"), ("출고 배정정책", "RoutingPolicy"), ("물류사업자", "Nodes"), ("물류센터·거점", "Nodes"), ("3PL 연동", "Integrations"), ("물류비", "LogisticsBilling"), ("SLA", "Sla")],
    "발주": [("현황", "Procurement"), ("공급처", "Suppliers"), ("발주", "Procurement"), ("발주 제안", "Procurement"), ("입고예정", "Inbound"), ("매입", "PurchaseCost"), ("매입원가", "PurchaseCost"), ("차이", "PurchaseCost")],
    "정산": [("현황", "Settlement"), ("예정", "SettlementExpected"), ("확정", "SettlementExpected"), ("채널별", "SettlementExpected"), ("대사·입금", "Settlement"), ("조정", "SettlementAdjust"), ("월 마감", "MonthClose"), ("부가세 신고자료", "VatReport")],
    "비용": [("비용 현황", "CostLedger"), ("상품 원가", "ProductCost"), ("매입원가", "PurchaseCost"), ("물류비 대사", "LogisticsBilling"), ("원가·마감 정책", "CostClosePolicy")],
    "손익": [("현황", "ProfitSummary"), ("주문별", "Profit"), ("상품별", "ProfitDimension"), ("SKU별", "ProfitDimension"), ("묶음별", "ProfitDimension"), ("채널별", "ProfitDimension"), ("판매계정별", "ProfitDimension"), ("브랜드별", "ProfitDimension"), ("사업자별", "ProfitDimension"), ("GWP 성과", "ProfitDimension"), ("클레임 손익", "AnalyticsClaims"), ("대사", "Settlement")],
    "분석": [("매출", "Analytics"), ("재고", "AnalyticsInventory"), ("클레임", "AnalyticsClaims"), ("배송/SLA", "Sla"), ("광고", "AnalyticsAds"), ("상품/채널 비교", "ProfitDimension")],
    "인사이트": [("오늘의 인사이트", "Insights"), ("이익 개선", "Insights"), ("재고 최적화", "AnalyticsInventory"), ("물류 최적화", "LogisticsOverview"), ("상품 분석", "ProfitDimension"), ("시뮬레이션", "Simulation")],
    "연동": [("연동 현황", "Integrations"), ("파일 연동", "IntegrationFile"), ("택배·광고·기타", "IntegrationOthers"), ("연동 진단", "IntegrationDiag")],
}

SETTINGS = [("운영그룹", "WorkspaceSettings"), ("사업자 관리", "BusinessEntities"), ("사용자·권한", "AccessPolicy"), ("판매계정", "ChannelAccounts"), ("화주계정", "ShipperAccounts"), ("정산계좌", "BankAccounts"), ("승인정책", "ApprovalPolicy"), ("알림정책", "NotificationPolicy"), ("원가·마감 정책", "CostClosePolicy"), ("변경이력", "AuditLog")]


def sub2_html(module, active_label, self_file=None):
    parts = []
    for label, target in MODULES[module]:
        if label == active_label:
            href = (self_file or target) + ".dc.html"
            parts.append(f'<a class="on" href="{href}" aria-current="page">{label}</a>')
        else:
            parts.append(f'<a href="{target}.dc.html">{label}</a>')
    return ("    <nav class=\"sub2\" aria-label=\"%s 메뉴\" style=\"display: flex; gap: 2px; padding: 0 24px; background: #fff; border-bottom: 1px solid #E3E6EA; overflow-x: auto\">\n      %s\n    </nav>"
            % (module, "".join(parts)))


def settings_nav_html(active_label, self_file=None):
    lines = []
    for label, target in SETTINGS:
        if label == active_label:
            lines.append(f'        <a class="on" href="{(self_file or target)}.dc.html" aria-current="page">{label}</a>')
        else:
            lines.append(f'        <a href="{target}.dc.html">{label}</a>')
    return ('      <nav class="sub" aria-label="설정 메뉴" style="flex: 1 1 180px; max-width: 200px; padding: 24px 12px; box-sizing: border-box; display: flex; flex-direction: column; gap: 2px">\n'
            + "\n".join(lines) + "\n      </nav>")


def nav_html(active):
    out = ['  <nav class="nav" aria-label="주 메뉴" style="flex: 1 1 220px; max-width: 240px; background: #fff; border-right: 1px solid #E3E6EA; padding: 16px 12px; box-sizing: border-box; display: flex; flex-direction: column; gap: 2px">',
           '    <div style="display: flex; align-items: center; gap: 10px; padding: 4px 12px 12px"><div aria-hidden="true" style="width: 28px; height: 28px; border-radius: 7px; background: #15191F; color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 13px">C</div><div style="font-weight: 700; font-size: 15px">커머스 운영 OS</div></div>']
    for item in NAV:
        if item[0] == "@":
            out.append(f'    <div class="navh">{item[1]}</div>')
            continue
        key, label, target = item
        extra = ' <span class="chip c-err" style="margin-left: auto; height: 20px">7</span>' if key == "wq" else ""
        if key == active:
            out.append(f'    <a class="on" href="{target}.dc.html" aria-current="page">{label}{extra}</a>')
        else:
            out.append(f'    <a href="{target}.dc.html">{label}{extra}</a>')
    out.append("  </nav>")
    return "\n".join(out)


def page(file, s):
    title = s["title"]
    biz = s.get("biz", ["전체 사업자 (3)"])
    opts = "".join(f"<option>{o}</option>" for o in biz)
    search = s.get("search")
    search_html = (f'\n      <label class="ctx" style="flex: 1 1 260px; display: flex; flex-direction: column; gap: 2px">통합검색<input class="inp" type="search" placeholder="{search}  ( / )"></label>' if search else "")
    user, role = s["user"]
    header = f'''    <header style="display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px 24px; background: #fff; border-bottom: 1px solid #E3E6EA">
      <div style="display: flex; flex-direction: column"><span class="ctx">운영그룹</span><span style="font-weight: 600; font-size: 14px">데모 운영그룹</span></div>
      <label class="ctx" style="display: flex; flex-direction: column; gap: 2px">사업자<select class="inp">{opts}</select></label>{search_html}
      <button class="btn" type="button" aria-label="알림 5건">알림 5</button>
      <div style="display: flex; flex-direction: column"><span style="font-size: 13px; font-weight: 600">{user}</span><span class="ctx">{role}</span></div>
    </header>'''
    body = s["body"]
    if "settings" in s:
        content = (f'''    <div style="display: flex; flex-wrap: wrap; flex: 1 1 auto">
{settings_nav_html(s["settings"], file)}
      <main style="flex: 999 1 480px; min-width: 0; padding: 24px 24px 24px 8px; display: flex; flex-direction: column; gap: 16px; box-sizing: border-box">
{body}
      </main>
    </div>''')
        subnav = ""
    else:
        subnav = "\n" + sub2_html(s["module"], s["tab"], file) if "module" in s else ""
        if s.get("full"):
            content = body
        else:
            mw = s.get("maxw", "")
            mws = f" max-width: {mw}; width: 100%;" if mw else ""
            content = f'''    <main style="padding: 24px; display: flex; flex-direction: column; gap: 16px; box-sizing: border-box;{mws}">
{body}
    </main>'''
    return f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title} — 커머스 운영 OS</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600;700&amp;display=swap">
<style>
{CSS}
</style>
</helmet>
<div style="min-height: {s.get("h", 1200)}px; display: flex; flex-wrap: wrap; font-family: 'IBM Plex Sans KR', system-ui, sans-serif; color: #15191F">
{nav_html(s["nav"])}

  <div style="flex: 999 1 560px; min-width: 0; display: flex; flex-direction: column">
{header}{subnav}

{content}
  </div>
</div>
</x-dc>
{script(1440, s.get("h", 1200))}
</body>
</html>
'''


def script(w, h):
    return ('<script type="text/x-dc" data-dc-script data-props=\'{"$preview":{"width":%d,"height":%d}}\'>\n'
            'class Component extends DCLogic {\n  renderVals() {\n    return {};\n  }\n}\n</script>' % (w, h))


def raw_page(title, css, inner, w, h):
    return f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title} — 커머스 운영 OS</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600;700&amp;display=swap">
<style>
{CSS}
{css}
</style>
</helmet>
{inner}
</x-dc>
{script(w, h)}
</body>
</html>
'''


# ---------- content helpers ----------
def won(n):
    s = f"{abs(n):,}"
    return ("−" + s) if n < 0 else s


def head(title, desc, milestone, actions=""):
    act = f'<div style="display: flex; gap: 8px; flex-wrap: wrap">{actions}</div>' if actions else ""
    return (f'      <div style="display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-end; gap: 12px">\n'
            f'        <div><h1 style="margin: 0; font-size: 24px">{title}</h1><p style="margin: 4px 0 0; font-size: 14px; color: #59616C">{desc} · <span class="chip c-mute">{milestone} 시안 · 예시 데이터</span></p></div>\n'
            f'        {act}\n      </div>')


def kpis(items, minw=150):
    cells = []
    for it in items:
        label, value = it[0], it[1]
        sub = it[2] if len(it) > 2 and it[2] else ""
        color = it[3] if len(it) > 3 else None
        st = f' style="color: {color}"' if color else ""
        subh = f'<span class="ctx">{sub}</span>' if sub else ""
        cells.append(f'<div class="card kpi"><span class="ctx">{label}</span><b{st}>{value}</b>{subh}</div>')
    return (f'      <section aria-label="요약" style="display: grid; grid-template-columns: repeat(auto-fit, minmax({minw}px, 1fr)); gap: 10px">\n        '
            + "\n        ".join(cells) + "\n      </section>")


def table(headers, rows, row_classes=None, caption=None):
    ths = []
    numcols = set()
    for i, h in enumerate(headers):
        if h.startswith("#"):
            numcols.add(i)
            ths.append(f'<th scope="col" class="num">{h[1:]}</th>')
        elif h == "":
            ths.append('<th scope="col"><span class="ctx">조치</span></th>')
        else:
            ths.append(f'<th scope="col">{h}</th>')
    trs = []
    for r_i, r in enumerate(rows):
        cls = ""
        if row_classes and r_i < len(row_classes) and row_classes[r_i]:
            cls = f' class="{row_classes[r_i]}"'
        tds = []
        for i, c in enumerate(r):
            if i in numcols:
                tds.append(f'<td class="num">{c}</td>')
            else:
                tds.append(f"<td>{c}</td>")
        trs.append(f"<tr{cls}>" + "".join(tds) + "</tr>")
    cap = f'<caption style="text-align: left; padding: 14px 16px 6px; font-weight: 600; font-size: 15px">{caption}</caption>' if caption else ""
    return ('        <table>' + cap + '\n          <thead><tr>' + "".join(ths) + '</tr></thead>\n          <tbody>\n            '
            + "\n            ".join(trs) + "\n          </tbody>\n        </table>")


def tcard(headers, rows, row_classes=None, caption=None):
    return '      <div class="card" style="overflow-x: auto">\n' + table(headers, rows, row_classes, caption) + "\n      </div>"


def section(title, inner, hid):
    return (f'      <section class="card" aria-labelledby="{hid}" style="padding: 16px; display: flex; flex-direction: column; gap: 10px; min-width: 0">\n'
            f'        <h2 id="{hid}" style="margin: 0; font-size: 16px">{title}</h2>\n{inner}\n      </section>')


def tabs(items, label, active=0):
    bs = []
    for i, t in enumerate(items):
        on = i == active
        bs.append(f'<button class="tab{" on" if on else ""}" role="tab" type="button" aria-selected="{"true" if on else "false"}">{t}</button>')
    return f'      <div role="tablist" aria-label="{label}" style="display: flex; flex-wrap: wrap; gap: 4px; border-bottom: 1px solid #E3E6EA">' + "".join(bs) + "</div>"


def chip(text, kind="mute"):
    return f'<span class="chip c-{kind}">{text}</span>'


def p(text):
    return f'      <p class="ctx" style="margin: 0">{text}</p>'


def note(text, kind="info"):
    return f'      <div class="note n-{kind}">{text}</div>'


def rows_kv(pairs):
    return "\n".join(f'        <div class="row"><span>{k}</span><span>{v}</span></div>' for k, v in pairs)


def split(main_inner, aside_title, aside_inner, aid="aside-h"):
    """Main + right detail panel (body must be used with full=True)."""
    return f'''    <div style="display: flex; flex-wrap: wrap; flex: 1 1 auto">
      <main style="flex: 999 1 560px; min-width: 0; padding: 24px; display: flex; flex-direction: column; gap: 16px; box-sizing: border-box">
{main_inner}
      </main>
      <aside aria-labelledby="{aid}" style="flex: 1 1 340px; max-width: 420px; background: #fff; border-left: 1px solid #E3E6EA; padding: 20px; box-sizing: border-box; display: flex; flex-direction: column; gap: 12px">
        <h2 id="{aid}" style="margin: 0; font-size: 18px">{aside_title}</h2>
{aside_inner}
      </aside>
    </div>'''


def esc(s):
    return _h.escape(s)
