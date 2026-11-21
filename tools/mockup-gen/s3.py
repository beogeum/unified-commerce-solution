from decimal import Decimal, ROUND_HALF_UP
from lib import *

S = {}
FIN = ("정재무", "재무 · 원가 조회 권한")

# ---------------- 정산 > 예정 · 확정 · 채널별 ----------------
exp = [("네이버 · A 공식스토어", "11-14 구매확정분", 412, 11_300_000, Decimal("0.055"), "11-24", "예정"),
       ("쿠팡 · A 코스메틱", "11-09 ~ 11-15 판매분", 355, 9_800_000, Decimal("0.108"), "판매계정 입력 주기", "예정"),
       ("카페24 · 자사몰 (PG)", "11-14 승인분", 148, 4_200_000, Decimal("0.033"), "11-18", "확정 · 입금 대기")]
e_rows = []
tot = [0, 0, 0, 0]
for acc, period, n, sup, rate, day, st in exp:
    gross = sup * 11 // 10
    fee = int((Decimal(sup) * rate).quantize(Decimal("1"), ROUND_HALF_UP))
    net = gross - fee
    tot = [tot[0] + n, tot[1] + gross, tot[2] + fee, tot[3] + net]
    e_rows.append([acc, period, f"{n:,}", won(gross), f'<span class="neg">{won(-fee)}</span>', f"<b>{won(net)}</b>", day,
                   chip(st, "ok" if st.startswith("확정") else "info")])
e_rows.append(["합계", "", f"{tot[0]:,}", won(tot[1]), won(-tot[2]), won(tot[3]), "", ""])
S["SettlementExpected"] = dict(
    title="정산 예정·확정", nav="settlement", module="정산", tab="예정", user=FIN,
    biz=["A 코스메틱"],
    body="\n".join([
        head("정산 예정 · 확정", "주문 기준으로 계산한 예상 정산액과 채널 정산서로 확정된 금액을 나눠 봅니다. 매출 · 정산액 · 입금액은 서로 다른 숫자입니다.", "M5",
             '<button class="btn" type="button">정산서 업로드</button>'),
        tabs(["예정 2", "확정 · 입금 대기 1", "입금 완료", "채널별 보기"], "상태"),
        kpis([("매출(부가세 포함)", won(tot[1]) + "원", "결제 기준"), ("예상 수수료", won(tot[2]) + "원", "공급가 × 계약 수수료율"), ("예상 정산액", won(tot[3]) + "원"), ("입금 확인", "0원", "대사·입금에서 매칭")]),
        tcard(["판매계정", "대상", "#주문", "#매출", "#예상 수수료", "#예상 정산액", "지급 예정", "상태"], e_rows, [None, None, None, "tot"]),
        note("예상 수수료는 판매계정에 입력한 계약 수수료율로 계산한 값입니다. 정산서가 들어오면 예상값을 덮어쓰지 않고 확정값을 따로 저장한 뒤 차이를 대사합니다. 쿠팡 정산 주기는 채널에서 확인되지 않아 사업자가 입력한 주기를 씁니다.", "info"),
        p("정산서 파일은 원본(해시 포함)과 매핑 규칙 버전을 함께 보관합니다. 확정 정산액과 입금액이 다르면 대사·입금 화면에서 차이 업무가 생깁니다."),
    ]),
)

# ---------------- 정산 > 조정 ----------------
S["SettlementAdjust"] = dict(
    title="정산 조정", nav="settlement", module="정산", tab="조정", user=FIN, full=True,
    body=split(
        "\n".join([
            head("정산 조정", "확정된 정산을 고치지 않고 조정 원장을 새로 남깁니다. 마감된 달의 조정은 다음 달에 반영됩니다.", "M5",
                 '<button class="btn pri" type="button">조정 등록</button>'),
            tcard(["조정번호", "판매계정", "원 정산", "유형", "#금액", "반영 월", "요청 · 승인", "상태"], [
                ["ADJ-2611-004", "쿠팡 · A 코스메틱", "ST-2610-CP-03", "반품배송비 이중 차감 정정", '<span class="pos">+38,000</span>', "11월(10월 마감됨)", "정재무 · 김운영", chip("승인됨", "ok")],
                ["ADJ-2611-003", "네이버 · A 공식스토어", "ST-2610-NV-28", "수수료 소급 환급", '<span class="pos">+12,400</span>', "11월", "정재무 · 김운영", chip("승인됨", "ok")],
                ["ADJ-2611-002", "카페24 · 자사몰", "ST-2611-PG-14", "PG 수수료 단가 정정", '<span class="neg">−8,600</span>', "11월", "정재무 · —", chip("승인 대기", "warn")],
                ["ADJ-2611-001", "쿠팡 · A 코스메틱", "ST-2611-CP-01", "프로모션 분담금", '<span class="neg">−150,000</span>', "11월", "정재무 · —", chip("승인 대기", "warn")],
            ], [None, None, None, "sel"]),
            p("조정은 정산액에만 영향을 주는 것(입금 차이)과 손익에도 영향을 주는 것(수수료·분담금)을 구분해 원장에 남깁니다."),
        ]),
        "ADJ-2611-001 프로모션 분담금",
        "\n".join([
            rows_kv([("원 정산", "ST-2611-CP-01 · 확정 11-08"), ("금액", "−150,000원 (부가세 포함 여부: 포함)"), ("근거", "쿠팡 정산서 '프로모션 분담' 항목 · 파일 행 214"), ("손익 영향", "11월 판매수수료·판촉비 +136,364 (공급가)"), ("요청자", "정재무 · 11-20 17:10")]),
            '        <div class="note n-warn">요청자는 이 조정을 승인할 수 없습니다. 승인정책: 100,000원 이상 정산 조정 → 운영관리자.</div>',
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn pri" type="button" disabled style="opacity: .6">승인(권한 없음)</button><button class="btn" type="button">요청 취소</button></div>',
        ])),
)

# ---------------- 비용·원가 > 상품 원가 ----------------
S["ProductCost"] = dict(
    title="상품 원가", nav="cost", module="비용", tab="상품 원가", user=FIN, full=True, search="SKU, 상품명",
    body=split(
        "\n".join([
            head("상품 원가", "SKU 원가를 사업자별로 따로 관리합니다. 같은 SKU라도 사업자마다 원가가 다를 수 있고, 변경은 적용 시작 시각이 있는 새 버전으로 남습니다.", "M5",
                 '<button class="btn" type="button">원가 일괄 등록</button>'),
            tcard(["SKU", "사업자", "원가 방식", "#현재 원가", "적용 시작", "#직전 원가", "출처"], [
                ["<b>AMP-30</b> 비타 앰플 30ml", "A 코스메틱", "이동평균", "9,000", "10-28 매입 확정", "9,050", "PO-2610-031"],
                ["<b>CLF-150</b> 클렌징폼 150ml", "A 코스메틱", "이동평균", "5,200", "10-15", "5,200", "PO-2610-018"],
                ["<b>CLF-300</b> 클렌징폼 300ml", "A 코스메틱", "이동평균", "8,100", "09-30", "8,000", "PO-2609-044"],
                ["<b>RUG-GR-160</b> 러그 그레이 160", "B 리빙", "이동평균", "41,000", "10-02", "41,000", "PO-2609-051"],
                ["<b>RUG-GR-200</b> 러그 그레이 200", "B 리빙", "—", chip("미등록", "err"), "—", "—", "손익 '계산 불가'"],
                ["<b>PAD-160</b> 미끄럼방지 패드", "B 리빙", "이동평균", "12,400", "10-02", "12,400", "PO-2609-051"],
                ["<b>BOX-S</b> 포장박스 S", "A · B · C 각각", "부자재 · 이동평균", "380", "11-01", "370", "팩앤박스"],
                ["<b>GIFT-PCH</b> 증정 파우치", "A 코스메틱", "이동평균", "1,200", "10-20", "1,200", "GWP 전용"],
            ], ["sel"]),
            p("원가 조회 권한이 없는 사용자에게는 이 화면과 손익의 원가 열이 보이지 않습니다. 원가 변경은 이미 출고된 주문에 소급되지 않습니다."),
        ]),
        "포장 부자재 BOM — 기본 출고 v3",
        "\n".join([
            tcard(["부자재", "#수량", "#단가", "#금액"], [["포장박스 S (BOX-S)", "1", "380", "380"], ["에어캡", "1", "120", "120"], ["테이프", "1", "30", "30"], ["안내카드", "1", "50", "50"], ["합계", "", "", "580"]], [None] * 4 + ["tot"]).replace("      <div", "        <div"),
            rows_kv([("적용", "화성 자체 WMS 출고 · 11-01부터"), ("3PL 출고", "부자재는 3PL 청구액(물류비 대사)으로 반영"), ("손익 항목", "부자재 — 물류비와 별도 표시")]),
            '        <p class="ctx" style="margin: 0">출고 확정 시 출고 건별로 포장 BOM 버전이 고정되어 부자재 원가가 원장에 남습니다. BOM을 바꾸면 v4가 생기고 이후 출고부터 적용됩니다.</p>',
        ])),
)

# ---------------- 손익 > 상품별 등 차원별 ----------------
cols = ["순매출", "출고원가", "수수료", "광고", "물류·배송", "부자재", "GWP", "클레임·재고손실"]
A_total = [400_000_000, 140_000_000, 36_000_000, 30_000_000, 52_000_000, 4_000_000, 3_000_000, 15_000_000]
prods = [("AMP-30 비타 앰플 30ml", [180_000_000, 54_000_000, 16_200_000, 14_000_000, 19_800_000, 1_700_000, 1_500_000, 5_800_000]),
         ("CLF-150 클렌징폼 150ml", [86_000_000, 26_000_000, 7_700_000, 6_000_000, 13_500_000, 1_000_000, 600_000, 3_200_000]),
         ("CLF-300 클렌징폼 300ml", [65_000_000, 24_300_000, 5_900_000, 5_000_000, 10_200_000, 700_000, 500_000, 3_000_000])]
other = [A_total[i] - sum(pv[1][i] for pv in prods) for i in range(8)]
prods.append(("그 외 14개 상품", other))

def prow(name, v):
    contrib = v[0] - sum(v[1:])
    rate = (Decimal(contrib) * 100 / Decimal(v[0])).quantize(Decimal("0.01"), ROUND_HALF_UP)
    return [name, won(v[0])] + [won(-x) if x else "0" for x in v[1:]] + [f"<b>{won(contrib)}</b>", f"{rate}%"], contrib

pd_rows, csum = [], 0
for n, v in prods:
    r, c = prow(n, v)
    pd_rows.append(r); csum += c
tr, tc = prow("<b>A 코스메틱 합계</b>", A_total)
assert tc == csum == 120_000_000
pd_rows.append(tr)
S["ProfitDimension"] = dict(
    title="상품별 손익", nav="profit", module="손익", tab="상품별", user=FIN, biz=["A 코스메틱", "전체 사업자 (3)"],
    body="\n".join([
        head("상품별 손익 — A 코스메틱 · 2026년 10월(마감)", "상품·SKU·묶음·채널·판매계정·브랜드·사업자·GWP 기준으로 같은 원장을 다시 묶어 봅니다. 합계는 손익 현황과 같습니다.", "M5",
             '<button class="btn" type="button">내보내기(권한 필요)</button>'),
        '      <div style="display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-end">'
        '<label class="fld">묶는 기준<select class="inp"><option>상품</option><option>SKU</option><option>묶음·세트</option><option>채널</option><option>판매계정</option><option>브랜드</option><option>사업자</option><option>GWP</option></select></label>'
        '<label class="fld">기간<select class="inp"><option>2026년 10월(마감)</option><option>2026년 11월(진행 중)</option></select></label>'
        '<label class="fld">묶음 상품 원가<select class="inp"><option>부품 SKU로 나눠 집계</option><option>묶음 상품으로 집계</option></select></label></div>',
        tcard(["상품", "#순매출"] + ["#" + c for c in cols[1:]] + ["#공헌이익", "#이익률"], pd_rows, [None] * len(prods) + ["tot"], "단위: 원 · 공급가"),
        p("A 코스메틱 합계(순매출 400,000,000 · 공헌이익 120,000,000)는 손익 현황의 A 코스메틱 행과 같습니다. 공동 광고비는 배부 규칙 v1로 나눈 금액이 들어갑니다. 금액을 누르면 해당 원장 목록으로 이동합니다."),
    ]),
)

# ---------------- 분석 > 재고 ----------------
S["AnalyticsInventory"] = dict(
    title="재고 분석", nav="analytics", module="분석", tab="재고", user=("오분석", "분석 담당"),
    body="\n".join([
        head("재고 분석", "판매 속도 대비 재고 보유일과 품절·과잉 위험을 봅니다. 판매 속도는 최근 7일 평균입니다.", "M6"),
        kpis([("품절 위험 SKU", "3", "보유 7일 미만", "#A11A1A"), ("과잉 재고 SKU", "4", "보유 90일 초과", "#8A4300"), ("불용 재고", "210개", "180일 미판매"), ("평균 재고 보유일", "21.4일")]),
        tcard(["SKU", "사업자", "#판매가능", "#입고 예정", "#하루 판매(7일 평균)", "#보유일", "판단"], [
            ["AMP-30", "A 코스메틱", "513", "2,980 (11-21 화성 입고)", "186", "18.8 (입고 반영)", chip("정상", "ok")],
            ["CLF-150", "A 코스메틱", "1,240", "—", "160", "7.8", chip("발주 검토", "warn")],
            ["CLF-300", "A 코스메틱", "0", "1,500 (11-28)", "95", "0", chip("품절", "err")],
            ["RUG-GR-160", "B 리빙", "0 (판매 제한)", "—", "9", "판단 보류", chip("3PL 재고 판단 불가", "warn")],
            ["GRN-500", "C 푸드", "310", "—", "12", "25.8", chip("정상", "ok")],
            ["PAD-160", "B 리빙", "980", "—", "8", "122.5", chip("과잉", "warn")],
        ]),
        p("보유일 = (판매가능 + 확정된 입고 예정) ÷ 하루 판매. 판매 제한·비가용 재고는 판매가능에 넣지 않습니다. 발주 제안은 발주·매입에서 공급처 리드타임과 함께 계산합니다."),
    ]),
)

# ---------------- 분석 > 클레임 ----------------
cl = [("회수 배송비", 6_200_000), ("교환 재발송 배송비", 3_000_000), ("재고 평가손실", 3_100_000), ("폐기손실", 1_900_000), ("재포장 부자재", 800_000)]
assert sum(v for _, v in cl) == 15_000_000
S["AnalyticsClaims"] = dict(
    title="클레임 분석", nav="analytics", module="분석", tab="클레임", user=("오분석", "분석 담당"),
    biz=["A 코스메틱"],
    body="\n".join([
        head("클레임 분석 · 클레임 손익 — A 코스메틱 · 2026년 10월", "취소·교환·반품 비율과 사유, 클레임으로 생긴 비용을 봅니다. 비용 합계는 손익의 '클레임·재고손실'과 같습니다.", "M6"),
        kpis([("반품률", "2.6%", "출고 대비"), ("교환률", "0.9%"), ("취소율(출고 전)", "1.8%"), ("클레임·재고손실", "15,000,000원", "손익 현황과 일치")]),
        '      <div class="grid2">',
        tcard(["SKU", "#반품률", "주요 사유"], [["AMP-30", "2.1%", "단순 변심 54% · 피부 트러블 21%"], ["CLF-150", "1.9%", "단순 변심 61%"], ["CLF-300", "3.4%", "용기 파손 38% · 단순 변심 35%"]], None, "SKU별"),
        tcard(["비용 항목", "#금액"], [[n, won(v)] for n, v in cl] + [["합계", "15,000,000"]], [None] * len(cl) + ["tot"], "클레임 비용"),
        "      </div>",
        note("CLF-300 용기 파손 반품이 늘었습니다(9월 1.2% → 10월 3.4%). 파손 사유 반품 중 화성 출고분이 82%라 포장 BOM(에어캡 1장) 보강 검토가 필요합니다. <a href=\"Insights.dc.html\">인사이트로 보내기</a>", "warn"),
        p("평가손실과 폐기손실은 같은 수량에 대해 겹치지 않도록 장부가 기준으로 계산합니다. 채널이 부담한 회수비는 비용에서 빠지고 정산 조정으로 대사합니다."),
    ]),
)

# ---------------- 분석 > 광고 ----------------
ads = [("네이버 검색광고 · A", 19_000_000, 96_000_000, "공동 광고 배부 4,200,000 포함"), ("쿠팡 광고 · A", 11_000_000, 52_000_000, ""),
       ("네이버 검색광고 · B", 8_000_000, 30_000_000, "공동 광고 배부 1,800,000 포함"), ("네이버 검색광고 · C", 1_500_000, 4_000_000, "")]
a_rows = []
for n, c, r, m in ads:
    roas = (Decimal(r) * 100 / Decimal(c)).quantize(Decimal("0.1"), ROUND_HALF_UP)
    a_rows.append([n, won(c), won(r), f"{roas}%", m or "—"])
ct, rt = sum(a[1] for a in ads), sum(a[2] for a in ads)
assert ct == 39_500_000
a_rows.append(["합계", won(ct), won(rt), f"{(Decimal(rt) * 100 / Decimal(ct)).quantize(Decimal('0.1'), ROUND_HALF_UP)}%", ""])
S["AnalyticsAds"] = dict(
    title="광고 분석", nav="analytics", module="분석", tab="광고", user=("오분석", "분석 담당"),
    body="\n".join([
        head("광고 분석 — 2026년 10월", "광고비와 광고 플랫폼이 보고한 기여 매출입니다. 광고 API는 미확인이라 광고 리포트 파일 업로드로 받습니다.", "M6",
             '<a class="btn" href="IntegrationFile.dc.html">광고 리포트 업로드</a>'),
        tcard(["광고 계정", "#광고비(부가세 제외)", "#플랫폼 보고 매출", "#ROAS(보고 기준)", "비고"], a_rows, [None] * len(ads) + ["tot"]),
        note("플랫폼 보고 매출은 광고 플랫폼의 기여 기준이라 우리 주문 매출과 같지 않습니다. 손익에는 광고비만 들어가고, 보고 매출은 비교용으로만 씁니다.", "info"),
        p("광고비 합계 39,500,000원은 손익 현황의 광고 합계와 같습니다. A·B 공동 검색광고 6,000,000원은 클릭 기여 배부 규칙 v1로 나눴습니다."),
    ]),
)

# ---------------- 인사이트 > 시뮬레이션 ----------------
def unit_contrib(price):
    supply = int((Decimal(price) / Decimal("1.1")).quantize(Decimal("1"), ROUND_HALF_UP))
    fee = int((Decimal(supply) * Decimal("0.055")).quantize(Decimal("1"), ROUND_HALF_UP))
    return supply, fee, supply - 9000 - fee - 3200 - 580

s0, f0, c0 = unit_contrib(33000)
s1, f1, c1 = unit_contrib(31000)
assert c0 == 15570
base_q = 3200
scen = [("현재 33,000원", base_q, c0), ("31,000원 · 판매 +5%", round(base_q * 1.05), c1), ("31,000원 · 판매 +15%", round(base_q * 1.15), c1)]
be = (Decimal(c0) / Decimal(c1) - 1) * 100
sim_rows = []
for n, q, c in scen:
    total = q * c
    diff = total - base_q * c0
    sim_rows.append([n, f"{q:,}", won(c), won(total), ("0" if diff == 0 else (f'<span class="pos">+{diff:,}</span>' if diff > 0 else f'<span class="neg">{won(diff)}</span>'))])
S["Simulation"] = dict(
    title="손익 시뮬레이션", nav="insights", module="인사이트", tab="시뮬레이션", user=("오분석", "분석 담당"), biz=["A 코스메틱"], full=True,
    body=split(
        "\n".join([
            head("손익 시뮬레이션 — AMP-30 · 네이버 A 공식스토어", "가격·수수료·배송비·판매량 가정을 바꿔 공헌이익 변화를 봅니다. 결과는 추정이며 실제 데이터를 바꾸지 않습니다.", "M6"),
            tcard(["시나리오", "#월 판매량", "#개당 공헌이익", "#월 공헌이익", "#현재 대비"], sim_rows, [None, "sel", None]),
            note(f"31,000원으로 내리면 개당 공헌이익이 {won(c0)} → {won(c1)}원으로 {won(c0 - c1)}원 줄어, 판매량이 {be.quantize(Decimal('0.1'), ROUND_HALF_UP)}% 이상 늘어야 현재 이익을 유지합니다.", "info"),
            p(f"개당 공헌이익 = 공급가 − 원가 9,000 − 수수료(공급가 × 5.5%) − 배송비 3,200 − 부자재 580. 33,000원: 공급가 {won(s0)}, 수수료 {won(f0)}. 31,000원: 공급가 {won(s1)}, 수수료 {won(f1)}. 월 판매량 3,200개는 10월 실적 기준 가정입니다."),
        ]),
        "가정",
        "\n".join([
            '        <label class="fld">판매가(부가세 포함)<input class="inp" value="31,000"></label>',
            '        <label class="fld">판매량 변화<input class="inp" value="+15%"></label>',
            '        <label class="fld">수수료율<input class="inp" value="5.5% (판매계정 계약값)"></label>',
            '        <label class="fld">배송비 · 부자재<input class="inp" value="3,200 · 580 (포장 BOM v3)"></label>',
            '        <div class="note n-warn">31,000원은 최저판매가 29,700원보다 높아 저장할 수 있는 가격입니다. 실제 변경은 가격·판매정책에서 합니다.</div>',
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn pri" type="button">시나리오 저장</button><a class="btn" href="PricePolicy.dc.html">가격·판매정책으로</a></div>',
        ])),
)
