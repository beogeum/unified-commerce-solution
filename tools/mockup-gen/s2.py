from decimal import Decimal, ROUND_HALF_UP
from lib import *

S = {}
LOGI_USER = ("박물류", "물류관리자")

# ---------------- 물류 > 운영현황 ----------------
def node_card(name, kind, conn, rows):
    return ('<section class="card" style="padding: 14px; display: flex; flex-direction: column; gap: 6px; min-width: 0">'
            f'<div style="display: flex; justify-content: space-between; gap: 8px; align-items: center"><b>{name}</b>{conn}</div><span class="ctx">{kind}</span>'
            + "".join(f'<div class="row"><span>{k}</span><span>{v}</span></div>' for k, v in rows) + "</section>")

S["LogisticsOverview"] = dict(
    title="물류 운영현황", nav="logistics", module="물류", tab="운영현황", user=LOGI_USER,
    body="\n".join([
        head("물류 운영현황 — 11월 21일", "거점별 오늘 출고·입고·반품과 연동 상태입니다. 3PL 거점은 모의 연동(mock-3pl) 기준입니다.", "M4",
             '<a class="btn" href="Logistics.dc.html">출고관리</a><a class="btn" href="Sla.dc.html">SLA</a>'),
        kpis([("오늘 출고 예정", "612건"), ("마감 전 미처리", "47건", "16:00 마감", "#8A4300"), ("3PL 전송 실패", "1건", "재시도 대기", "#A11A1A"), ("입고 예정", "3건"), ("반품 입고", "18건")]),
        '      <div class="grid2">\n        '
        + node_card("화성 자체 WMS", "자체 운영 · A·B·C 공용 · 마감 16:00", chip("정상", "ok"), [("출고 예정", "318"), ("피킹 중", "41"), ("미처리", "22"), ("실사 잠금 로케이션", "A-03 구역")])
        + "\n        " + node_card("파스토 용인", "3PL · 화주 FASSTO-A01 · A 코스메틱 · 마감 15:00", chip("모의 연동", "info"), [("출고 지시", "214"), ("3PL 접수 확인", "201"), ("전송 실패", "1 · F-88241"), ("재고 동기화", "13:40 · 워터마크 #F-88213")])
        + "\n        " + node_card("품고 김포", "3PL · 화주 B-01 · B 리빙 · 마감 14:00", chip("재고 판단 보류", "warn"), [("출고 지시", "80"), ("3PL 접수 확인", "80"), ("재고 차이", "RUG-GR-160 6개 판단 불가"), ("판매 제한", "RUG-GR-160")])
        + "\n      </div>",
        note("품고 김포 재고 파일이 이미 출고분을 반영했는지 판단할 수 없어 RUG-GR-160을 판매 제한하고 검토 업무를 만들었습니다. 수량을 임의로 다시 차감하지 않습니다. <a href=\"WorkQueue.dc.html\">업무함에서 보기</a>", "warn"),
    ]),
)

# ---------------- 물류 > 출고 배정정책 ----------------
S["RoutingPolicy"] = dict(
    title="출고 배정정책", nav="logistics", module="물류", tab="출고 배정정책", user=LOGI_USER,
    biz=["A 코스메틱"], full=True,
    body=split(
        "\n".join([
            head("출고 배정정책 — A 코스메틱", "주문을 어느 출고거점에서 내보낼지 정하는 규칙입니다. 위에서부터 순서대로 거점 후보를 좁힙니다.", "M4",
                 '<button class="btn" type="button">시뮬레이션</button><button class="btn pri" type="button">새 버전 게시 요청</button>'),
            '      <div style="display: flex; gap: 8px; flex-wrap: wrap; align-items: center">' + chip("운영 중 v4 · 11-02 게시", "ok") + chip("초안 v5 · 박물류 편집 중", "warn") + "</div>",
            tcard(["순서", "규칙", "조건", "결과"], [
                ["1", "<b>재고 소유</b>", "주문 사업자 소유 재고가 있는 거점만", "필수 · 끌 수 없음"],
                ["2", "<b>전 품목 확보</b>", "한 거점에서 주문 전체 확보 가능", "가능 거점 우선"],
                ["3", "<b>출고 마감</b>", "현재 시각이 거점 마감 전", "마감 지난 거점은 다음 날로"],
                ["4", "<b>LOT 기준</b>", "출고 허용 잔여 유통기한 충족", "미충족 LOT 제외"],
                ["5", "<b>예상 물류비</b>", "택배비 + 작업비 + 부자재", "낮은 거점 우선"],
                ["6", "<b>분할 출고</b>", "한 거점에서 불가할 때", "v5: 허용(최대 2개 거점) · v4: 보류"],
            ], [None, None, None, None, None, "sel"]),
            p("배정 결과와 적용된 규칙 버전은 출고 건마다 남습니다. 게시된 버전은 수정하지 않고 새 버전으로 바꿉니다."),
        ]),
        "v5 영향도 미리보기",
        "\n".join([
            rows_kv([("대상 기간", "최근 7일 주문 3,980건 재계산"), ("배정 거점이 바뀌는 주문", "112건(2.8%)"), ("분할 출고 발생", "37건 · 택배 2건씩"), ("예상 물류비 변화", "+96,200원 (분할 택배비)"), ("보류 감소", "37건 → 0건")]),
            '        <div class="note n-warn">분할 출고를 켜면 보류는 줄지만 택배비가 늘어납니다. 게시는 운영관리자 승인 후 적용되고, 이미 배정된 출고 건은 바뀌지 않습니다.</div>',
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn pri" type="button">게시 승인 요청</button><button class="btn" type="button">v4와 비교</button></div>',
        ])),
)

# ---------------- 물류 > 물류사업자 · 물류센터·거점 ----------------
S["Nodes"] = dict(
    title="물류사업자·거점", nav="logistics", module="물류", tab="물류센터·거점", user=LOGI_USER, full=True,
    body=split(
        "\n".join([
            head("물류사업자 · 출고거점", "자체 창고와 3PL을 출고거점으로 등록합니다. 3PL은 화주계정으로 연결하고, 지원 기능은 확인된 것만 켭니다.", "M4",
                 '<button class="btn pri" type="button">거점 추가</button>'),
            tabs(["출고거점 3", "물류사업자 2"], "구분"),
            tcard(["거점", "운영", "사용 사업자", "화주계정", "마감", "연동", "상태"], [
                ["<b>화성 자체 WMS</b>", "자체", "A · B · C", "—", "16:00", chip("내부", "ok"), chip("운영", "ok")],
                ["<b>파스토 용인</b>", "3PL · 파스토", "A 코스메틱", "FASSTO-A01", "15:00", chip("모의 연동", "info"), chip("운영", "ok")],
                ["<b>품고 김포</b>", "3PL · 품고", "B 리빙", "B-01", "14:00", chip("파일 연동", "mute"), chip("운영", "ok")],
            ], [None, "sel"]),
            p("한 거점을 여러 사업자가 쓸 수 있지만 재고 소유권은 사업자별로 따로 집계됩니다. 거점 비활성화는 남은 재고·진행 중 출고가 없을 때만 가능합니다."),
        ]),
        "파스토 용인",
        "\n".join([
            rows_kv([("주소", "경기 용인시 처인구 (예시)"), ("운영시간", "평일 09:00–18:00"), ("택배사", "계약 택배사 1곳 · 송장은 3PL 발급"), ("로케이션 관리", "3PL 관리 — 내부 로케이션 없음")]),
            '        <h3 style="margin: 8px 0 0; font-size: 14px">지원 기능</h3>',
            tcard(["기능", "상태"], [["출고 지시 전송", chip("모의 확인", "info")], ["출고 결과 수신", chip("모의 확인", "info")], ["재고 조회", chip("모의 확인", "info")], ["입고 예정 등록", chip("미확인", "mute")], ["반품 입고 결과", chip("미확인", "mute")]]).replace("      <div", "        <div"),
            '        <p class="ctx" style="margin: 0">실제 파스토 API 권한이 확인되기 전까지 모든 전송은 모의 연동으로만 실행됩니다. 미확인 기능은 파일 업로드로 대신합니다.</p>',
        ])),
)

# ---------------- 물류 > 물류비 ----------------
bill = [("출고 작업비", 4180, 800, 4200, 800, "반품 재출고 20건 포함 여부"),
        ("보관료(팔레트·일)", 1140, 1000, 1140, 1000, ""),
        ("부자재(3PL 공급)", 4180, 500, 4200, 520, "계약 단가 500 vs 청구 520"),
        ("택배비", 4180, 3200, 4200, 3200, "출고 건수 차이 20건"),
        ("반품 처리비", 120, 1500, 120, 1500, "")]
b_rows, e_t, a_t = [], 0, 0
for n, eq, eu, aq, au, why in bill:
    e, a = eq * eu, aq * au
    e_t += e; a_t += a
    d = a - e
    b_rows.append([n, f"{eq:,} × {eu:,}", won(e), f"{aq:,} × {au:,}", won(a), (f'<span class="neg">+{d:,}</span>' if d else "0"), why or "—", chip("차이 검토", "warn") if d else chip("일치", "ok")])
b_rows.append(["<b>합계</b>", "", won(e_t), "", won(a_t), f"+{a_t - e_t:,}", "", ""])
S["LogisticsBilling"] = dict(
    title="물류비 대사", nav="logistics", module="물류", tab="물류비", user=("정재무", "재무"),
    biz=["A 코스메틱"],
    body="\n".join([
        head("물류비 대사 — 파스토 용인 · 2026년 10월 청구서", "우리가 계산한 예상 물류비와 3PL 청구액을 항목별로 맞춰 봅니다. 예상과 청구는 더하지 않고, 확정되면 청구액이 예상을 대체합니다.", "M5",
             '<button class="btn" type="button">청구서 원본</button><button class="btn pri" type="button">차이 확정·조정 요청</button>'),
        kpis([("예상 물류비", won(e_t) + "원"), ("청구액", won(a_t) + "원"), ("차이", f"+{a_t - e_t:,}원", "검토 2항목", "#A11A1A"), ("청구서 수신", "11-05 · 파일", "SHA-256 41ab…")]),
        tcard(["항목", "#예상 수량 × 단가", "#예상", "#청구 수량 × 단가", "#청구", "#차이", "원인 후보", "상태"], b_rows, [None] * len(bill) + ["tot"]),
        note("확정 전까지 10월 손익의 물류비는 '예상'으로 표시됩니다. 차이를 확정하면 예상분을 되돌리고 청구액을 실제 비용으로 남기며, 월 마감이 끝난 10월은 11월 조정으로 반영됩니다.", "info"),
    ]),
)

# ---------------- 물류 > SLA ----------------
S["Sla"] = dict(
    title="배송·출고 SLA", nav="logistics", module="물류", tab="SLA", user=LOGI_USER,
    body="\n".join([
        head("배송 · 출고 SLA", "거점별 당일 출고율과 채널 발송 기한 준수율입니다. 채널 발송 기한은 판매계정에 입력한 값이며, 채널 API로 확인된 값이 아닙니다.", "M4",
             '<select class="inp" aria-label="기간"><option>최근 7일</option><option>최근 30일</option></select>'),
        kpis([("당일 출고율", "96.3%", "목표 97%", "#8A4300"), ("발송 기한 초과", "14건", "최근 7일", "#A11A1A"), ("오늘 위험 주문", "9건", "마감 1시간 이내"), ("평균 배송 소요", "1.4일", "출고 → 배송완료")]),
        tcard(["거점", "#출고", "#당일 출고", "당일 출고율", "#발송 기한 초과", "주요 원인"], [
            ["화성 자체 WMS", "2,210", "2,161", "97.8%", "4", "피킹 지연(오후 집중)"],
            ["파스토 용인", "1,436", "1,366", "95.1%", "6", "출고 지시 전송 지연 1회"],
            ["품고 김포", "334", "305", "91.3%", "4", "판매 제한 해제 지연"],
            ["합계", "3,980", "3,832", "96.3%", "14", ""],
        ], [None, None, None, "tot"]),
        tcard(["주문", "판매계정", "거점", "발송 기한", "현재 단계", "위험"], [
            ["O-26112100298", "쿠팡 · A 코스메틱", "파스토", "오늘 16:00", "상품 미연결 보류", chip("2시간 남음", "warn")],
            ["O-26112100391", "네이버 · A 공식스토어", "화성", "오늘 16:00", "주소 보류", chip("2시간 남음", "err")],
            ["O-26112100402", "카페24 · B 기본몰", "품고", "오늘 14:00", "3PL 접수 대기", chip("초과", "err")],
        ], None, "오늘 위험 주문"),
        p("당일 출고율 = 마감 전 결제 주문 중 당일 출고 확정 비율. 보류 주문도 기한 계산에서 빠지지 않습니다."),
    ]),
)

# ---------------- 물류 > 반품물류 ----------------
S["ReturnLogistics"] = dict(
    title="반품물류", nav="logistics", module="물류", tab="반품물류", user=("서창고", "창고작업자"),
    search="반품번호, 송장, 주문번호", full=True,
    body=split(
        "\n".join([
            head("반품물류 — 회수 · 입고 · 검수", "반품 요청·환불 확정과 별개로 실물 회수·입고·검수·판정을 따로 기록합니다.", "M4"),
            '      <ol aria-label="단계" style="list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 8px">'
            '<li class="step">회수 요청 11</li><li class="step">회수 중 23</li><li class="step on" aria-current="step">입고 · 검수대기 18</li><li class="step">판정 완료(오늘) 26</li></ol>',
            tcard(["반품 · 주문", "SKU", "거점", "회수 송장", "입고", "환불", "판정"], [
                ["R-2611-0042 · O-26111800122", "AMP-30 × 1", "파스토", "회수 완료", "11:20", chip("환불 확정", "ok"), chip("검수대기", "warn")],
                ["R-2611-0045 · O-26111900340", "CLF-300 × 2", "화성", "회수 완료", "12:05", chip("환불 보류", "mute"), chip("검수 중", "info")],
                ["R-2611-0038 · O-26111700087", "RUG-GR-160 × 1", "품고", "회수 완료", "11-20", chip("환불 확정", "ok"), chip("불량", "err")],
            ], [None, "sel"]),
            p("환불은 채널 정책에 따라 실물 입고 전에 확정될 수 있습니다. 환불 확정과 재고 입고·원가 환입은 서로 다른 이벤트로 남고, 원가 환입은 검수 판정이 '정상'일 때 한 번만 일어납니다."),
        ]),
        "R-2611-0045 검수",
        "\n".join([
            rows_kv([("주문", "O-26111900340 · 쿠팡 · A 코스메틱"), ("사유(고객)", "단순 변심"), ("수량", "CLF-300 × 2"), ("LOT", "2609-B")]),
            '        <fieldset style="border: 1px solid #E3E6EA; border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 6px"><legend class="ctx">판정(수량별)</legend>'
            '<label><input type="radio" name="j" checked> 정상 — 판매가능 복귀 · 원가 환입</label><label><input type="radio" name="j"> 재포장 후 정상 — 부자재 비용 발생</label><label><input type="radio" name="j"> 불량 — 비가용 · 평가 대상</label><label><input type="radio" name="j"> 폐기 요청 — 승인 필요</label></fieldset>',
            '        <label class="fld">검수 사진·메모<input class="inp" value="외관 이상 없음, 봉인 유지"></label>',
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn pri" type="button">판정 저장</button><button class="btn" type="button">일부 수량 다르게</button></div>',
            '        <p class="ctx" style="margin: 0">판정을 바꾸려면 반대 거래로 정정합니다. 같은 반품에 원가 환입이 두 번 생기지 않도록 반품 수량 단위로 막습니다.</p>',
        ])),
)

# ---------------- 발주 > 공급처 ----------------
S["Suppliers"] = dict(
    title="공급처", nav="procurement", module="발주", tab="공급처", user=("한상품", "상품/MD"),
    search="공급처명, 사업자등록번호",
    body="\n".join([
        head("공급처", "사업자별로 공급처와 거래 조건을 관리합니다. 계좌·담당자 연락처는 권한자에게만 원문으로 보입니다.", "M3",
             '<button class="btn pri" type="button">공급처 추가</button>'),
        tcard(["공급처", "거래 사업자", "사업자등록번호", "주요 품목", "#리드타임", "#최소 발주(MOQ)", "결제 조건", "정산 계좌", "상태"], [
            ["<b>루미에르랩(가상 OEM)</b>", "A 코스메틱", "000-81-00021", "AMP-30 · CLF-150 · CLF-300", "21일", "1,000", "월말 마감 익월 15일", "기업 ***-***-4410", chip("거래 중", "ok")],
            ["<b>온하우스텍스(가상)</b>", "B 리빙", "000-86-00035", "RUG-GR-160 · RUG-GR-200 · PAD-160", "30일", "200", "선급 30% · 입고 후 70%", "국민 ***-***-0927", chip("거래 중", "ok")],
            ["<b>그레인팜(가상)</b>", "C 푸드", "000-01-00048", "GRN-500", "10일", "500", "입고 후 7일", "농협 ***-***-3381", chip("거래 중", "ok")],
            ["<b>팩앤박스(가상)</b>", "A · B · C 공용", "000-81-00052", "BOX-S · 에어캡 · 테이프", "5일", "5,000", "월말 마감 익월 말", "신한 ***-***-7715", chip("거래 중", "ok")],
        ]),
        p("공급처 계좌는 암호화 저장되고 목록에서는 끝 4자리만 보입니다. 계좌 변경은 승인 후 적용되며 변경이력에 남습니다. 부자재 공급처는 여러 사업자가 함께 쓰지만 매입·원가는 사업자별로 나뉩니다."),
    ]),
)

# ---------------- 발주 > 매입 · 매입원가 · 차이 ----------------
po_q, recv_q, unit, freight = 3000, 2980, 8600, 450000
base = recv_q * unit
landed_total = base + freight
landed_unit = (Decimal(landed_total) / Decimal(recv_q)).quantize(Decimal("1"), ROUND_HALF_UP)
old_q, old_c = 598, 9000
new_avg = (Decimal(old_q * old_c + landed_total) / Decimal(old_q + recv_q)).quantize(Decimal("1"), ROUND_HALF_UP)
S["PurchaseCost"] = dict(
    title="매입·매입원가", nav="procurement", module="발주", tab="매입원가", user=("정재무", "재무 · 원가 조회 권한"),
    biz=["A 코스메틱"], full=True,
    body=split(
        "\n".join([
            head("매입 · 매입원가 — PO-2611-012", "발주·입고·청구 세 가지를 맞춘 뒤 매입을 확정하고 원가를 계산합니다. 금액은 부가세 별도 공급가입니다.", "M3"),
            tabs(["매입 확정 대기 2", "확정", "차이 1"], "상태"),
            tcard(["구분", "#발주", "#입고", "#청구", "#단가", "#금액(공급가)", "상태"], [
                ["AMP-30 비타 앰플 30ml", f"{po_q:,}", f"{recv_q:,}", f"{po_q:,}", won(unit), won(po_q * unit) + " 청구", chip(f"수량 차이 {recv_q - po_q}", "err")],
                ["운반비(부대비용)", "", "", "1", won(freight), won(freight), chip("일치", "ok")],
            ]),
            note(f"청구 수량 {po_q:,}개가 입고 수량 {recv_q:,}개보다 {po_q - recv_q}개 많습니다(차이 {won((po_q - recv_q) * unit)}원). 입고 수량 기준으로 매입을 확정하고, 차액은 공급처 정정 청구 요청으로 남깁니다.", "warn"),
            tcard(["원가 계산(입고 기준)", "#금액"], [
                [f"상품 대금 {recv_q:,} × {won(unit)}", won(base)],
                ["부대비용(운반비)", won(freight)],
                [f"<b>입고 원가 합계</b>", f"<b>{won(landed_total)}</b>"],
                [f"단위 입고원가 ({won(landed_total)} ÷ {recv_q:,}, 원 미만 반올림)", won(int(landed_unit))],
            ], [None, None, "tot", None], "매입원가"),
        ]),
        "이동평균 원가 반영",
        "\n".join([
            rows_kv([("원가 방식", "이동평균 (A 코스메틱 설정값)"), ("기존 재고", f"{old_q} × {won(old_c)} = {won(old_q * old_c)}"), ("입고분", f"{recv_q:,} × {won(int(landed_unit))} (합계 {won(landed_total)})"),
                     ("새 평균원가", f"<b>{won(int(new_avg))}원</b> (이전 {won(old_c)})"), ("적용 시점", "매입 확정 시각 이후 출고분부터")]),
            '        <div class="note n-info">이미 출고된 주문의 원가는 바뀌지 않습니다. 확정 후 단가가 정정되면 차액은 원가 조정 원장으로 남고 기존 원장을 덮어쓰지 않습니다.</div>',
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn pri" type="button">입고 수량 기준 매입 확정</button><button class="btn" type="button">공급처 정정 요청</button></div>',
            '        <p class="ctx" style="margin: 0">매입 부가세는 별도 집계되어 부가세 신고자료의 매입세액으로 넘어갑니다.</p>',
        ])),
)
