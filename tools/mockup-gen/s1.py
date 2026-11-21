from lib import *

S = {}

# ---------------- 주문 > 수동·B2B 주문 ----------------
lines = [("RUG-GR-160", "러그 그레이 160", 10, 58000), ("PAD-160", "미끄럼방지 패드 160", 10, 21000)]
supply = sum(q * u for _, _, q, u in lines)
vat = supply // 10
mo_rows = [[f"<b>{c}</b><div class=\"ctx\">{n}</div>", str(q), won(u), won(q * u), "과세"] for c, n, q, u in lines]
mo_rows.append(["<b>합계</b>", "20", "", f"<b>{won(supply)}</b>", f"부가세 {won(vat)}"])
S["ManualOrder"] = dict(
    title="수동·B2B 주문", nav="orders", module="주문", tab="수동·B2B 주문", user=("김운영", "운영관리자"),
    biz=["B 리빙"], search="주문번호, 거래처", full=True,
    body=split(
        "\n".join([
            head("수동·B2B 주문 등록", "채널 밖 주문(도매·전화·행사)을 등록합니다. 채널 주문과 같은 재고 예약·출고 배정·손익 흐름을 탑니다.", "M4"),
            '      <section class="card" aria-label="주문 정보" style="padding: 16px">\n        <div class="grid2">'
            '<label class="fld">사업자<select class="inp"><option>B 리빙</option></select></label>'
            '<label class="fld">판매단위<select class="inp"><option>직접판매 · B2B 도매</option><option>직접판매 · 전화주문</option></select></label>'
            '<label class="fld">거래처<select class="inp"><option>온하우스 잠실점(가상 거래처)</option></select></label>'
            '<label class="fld">수동 주문번호<input class="inp" value="M-2611-0007 (자동 발급)" readonly></label>'
            '<label class="fld">단가 기준<select class="inp"><option>부가세 별도 공급가</option><option>부가세 포함 판매가</option></select></label>'
            '<label class="fld">결제 조건<select class="inp"><option>후불 · 월말 청구 30일</option><option>선불 · 입금 확인 후 출고</option></select></label>'
            '<label class="fld">출고 거점<select class="inp"><option>출고 배정정책 자동</option><option>품고 김포 지정</option></select></label>'
            '<label class="fld">희망 출고일<input class="inp" value="2026-11-24"></label>'
            '</div>\n      </section>',
            tcard(["상품 · SKU", "#수량", "#단가(공급가)", "#공급가액", "과세"], mo_rows, [None, None, "tot"], "주문 상품"),
            note("<b>RUG-GR-160은 판매 제한 중입니다.</b> 품고 김포 재고가 이미 출고분을 반영했는지 확인되지 않아 판매가능 수량을 0으로 막아 두었습니다. 지금 저장하면 주문은 '재고' 사유로 <a href=\"OrderHolds.dc.html\">주문 보류</a>에 들어가고, 제한이 풀리면 자동으로 예약됩니다.", "warn"),
        ]),
        "등록 전 확인",
        "\n".join([
            '        <section aria-label="금액">',
            rows_kv([("공급가액", won(supply) + "원"), ("부가세(10%)", won(vat) + "원"), ("청구 합계", f"<b>{won(supply + vat)}원</b>"), ("수금", "후불 · 매출채권 2026-12-31 만기")]),
            "        </section>",
            '        <section aria-label="처리 흐름">',
            rows_kv([("재고", "저장 즉시 예약 시도(SKU별 원자적)"), ("출고 배정", "B 리빙 재고 보유 거점 → 품고 김포"), ("매출 인식", "출고 확정 시"), ("손익", "판매단위 '직접판매'로 집계")]),
            "        </section>",
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn pri" type="button">주문 등록</button><button class="btn" type="button">임시 저장</button></div>',
            '        <p class="ctx" style="margin: 0">같은 내용을 두 번 눌러도 주문은 한 건만 생깁니다(멱등키). 등록·수정·취소는 모두 변경이력에 남습니다. 거래처 연락처는 개인정보 권한이 있는 사용자에게만 원문으로 보입니다.</p>',
        ])),
)

# ---------------- CS > 답변 템플릿 · 처리 기준 ----------------
S["CsTemplates"] = dict(
    title="답변 템플릿·처리 기준", nav="cs", module="CS", tab="답변 템플릿", user=("윤상담", "CS 담당"),
    search="템플릿 이름, 내용", full=True,
    body=split(
        "\n".join([
            head("답변 템플릿 · 처리 기준", "자주 쓰는 답변과 문의 유형별 처리 기준(응답 기한·자동 업무)을 관리합니다.", "M6",
                 '<button class="btn pri" type="button">템플릿 추가</button>'),
            tabs(["답변 템플릿 14", "처리 기준 6"], "구분"),
            tcard(["템플릿", "문의 유형", "사용 채널", "변수", "#최근 30일 사용", "상태"], [
                ["<b>배송 지연 안내</b>", "배송", "전체", "{고객명} {주문번호} {예상 출고일}", "212", chip("사용 중", "ok")],
                ["<b>교환 회수 안내</b>", "교환", "전체", "{고객명} {택배사} {회수 예정일}", "96", chip("사용 중", "ok")],
                ["<b>반품 검수 결과</b>", "반품", "전체", "{고객명} {검수 결과} {환불 예정액}", "71", chip("사용 중", "ok")],
                ["<b>유통기한 문의</b>", "상품", "C 푸드 계정", "{상품명} {LOT 유통기한}", "18", chip("사용 중", "ok")],
                ["<b>품절 · 입고 예정</b>", "상품", "전체", "{상품명} {입고 예정일}", "0", chip("검토 중", "warn")],
            ], ["sel"]),
            section("처리 기준", tcard(["문의 유형", "첫 응답 기한", "자동 생성 업무", "에스컬레이션"], [
                ["배송 지연·미수령", "4시간", "배송 추적 확인(물류)", "24시간 미해결 → 물류관리자"],
                ["교환·반품 요청", "24시간", "클레임 생성 제안", "회수 3일 지연 → 운영관리자"],
                ["상품 파손", "4시간", "사진 확인 · 반품 회수", "—"],
                ["개인정보 변경 요청", "24시간", "본인 확인 업무", "개인정보 권한자만 처리"],
            ]).replace("      <div", "        <div"), "rule-h"),
        ]),
        "템플릿 편집 — 배송 지연 안내",
        "\n".join([
            '        <label class="fld">제목<input class="inp" value="주문하신 상품 출고가 늦어지고 있습니다"></label>',
            '        <label class="fld">본문<textarea class="inp" rows="7" style="padding: 8px 10px; line-height: 1.6">{고객명}님, 주문({주문번호}) 상품이 입고 지연으로 {예상 출고일}에 출고될 예정입니다. 불편을 드려 죄송합니다.</textarea></label>',
            '        <div class="note n-info"><b>미리보기</b> — 김*진님, 주문(O-26112100377) 상품이 입고 지연으로 11월 24일에 출고될 예정입니다. 불편을 드려 죄송합니다.</div>',
            rows_kv([("글자 수", "68 / 채널 제한은 판매계정별 확인 필요"), ("개인정보 변수", "{고객명}은 마스킹 규칙 적용")]),
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn pri" type="button">저장(새 버전)</button><button class="btn" type="button">이전 버전</button></div>',
            '        <p class="ctx" style="margin: 0">채널로 바로 답변 보내기는 판매계정의 \'문의 답변\' 지원 기능이 확인된 계정에서만 켜집니다. 미확인 계정은 복사해 채널 관리자에서 답변합니다.</p>',
        ])),
)

# ---------------- CS > 현황 ----------------
types = [("배송 문의", 31, 4, "52분", 1), ("교환·반품", 22, 5, "1시간 48분", 2), ("상품 문의", 18, 3, "1시간 05분", 0), ("취소", 9, 1, "38분", 0), ("기타", 6, 1, "2시간 10분", 0)]
tot = sum(t[1] for t in types)
open_ = sum(t[2] for t in types)
sla = sum(t[4] for t in types)
S["CsStats"] = dict(
    title="CS 현황", nav="cs", module="CS", tab="현황", user=("윤상담", "CS 담당"),
    body="\n".join([
        head("CS 현황 — 오늘", "문의 유형·채널·담당자별 처리 현황입니다. 응답 기한은 처리 기준을 따릅니다.", "M6",
             '<select class="inp" aria-label="기간"><option>오늘</option><option>최근 7일</option><option>이번 달</option></select>'),
        kpis([("오늘 접수", f"{tot}건"), ("미답변", f"{open_}건", "", "#8A4300"), ("평균 첫 응답", "1시간 12분"), ("응답 기한 초과", f"{sla}건", "처리 기준 대비", "#A11A1A"), ("재문의율", "6.2%", "7일 내 같은 주문")]),
        tcard(["문의 유형", "#접수", "#미답변", "#평균 첫 응답", "#기한 초과"],
              [[n, str(a), str(b), c, str(d)] for n, a, b, c, d in types] + [["합계", str(tot), str(open_), "1시간 12분", str(sla)]],
              [None] * len(types) + ["tot"], "유형별"),
        '      <div class="grid2">',
        tcard(["판매계정", "#접수", "#미답변"], [["네이버 · A 공식스토어", "34", "5"], ["쿠팡 · A 코스메틱", "29", "6"], ["카페24 · B 기본몰", "17", "2"], ["C 푸드 · 스마트스토어", "6", "1"]], None, "채널별"),
        tcard(["담당자", "#처리", "#보유", "#평균 첫 응답"], [["윤상담", "41", "6", "58분"], ["이주문", "19", "3", "1시간 20분"], ["미배정", "—", "5", "—"]], None, "담당자별"),
        "      </div>",
        p("채널에서 수집하지 못한 문의(전화·이메일)는 수동 등록분만 포함됩니다. 문의 수집은 판매계정 지원 기능 확인 후 자동화됩니다."),
    ]),
)

# ---------------- 재고 > 예약·할당 ----------------
nodes_r = [("화성 자체 WMS", 24, 22, 6), ("파스토 용인", 22, 5, 6)]
rs = [sum(x[i] for x in nodes_r) for i in (1, 2, 3)]
S["Reservations"] = dict(
    title="예약·할당", nav="inventory", module="재고", tab="예약·할당", user=("박물류", "물류관리자"),
    biz=["A 코스메틱"], search="SKU, 주문번호",
    body="\n".join([
        head("예약·할당 — AMP-30 비타 앰플 30ml", "확보 수량은 예약 → 할당 → 피킹 중 한 단계에만 있습니다. 단계가 넘어갈 때 수량이 옮겨질 뿐 다시 차감되지 않습니다.", "M3",
             '<button class="btn" type="button">만료 예약 해제 점검</button>'),
        kpis([("판매가능", "513", "화성 314 · 파스토 199"), ("예약", str(rs[0])), ("할당", str(rs[1])), ("피킹", str(rs[2])), ("확보 합계", str(sum(rs)), "예약+할당+피킹"), ("고아 예약", "0", "주문 없는 예약", "#1D6A3A")]),
        tcard(["거점", "#판매가능", "#예약", "#할당", "#피킹", "#확보 합계"],
              [[n, str(sa), str(a), str(b), str(c), str(a + b + c)] for (n, a, b, c), sa in zip(nodes_r, (314, 199))]
              + [["합계", "513", str(rs[0]), str(rs[1]), str(rs[2]), str(sum(rs))]], [None, None, "tot"], "거점별"),
        tcard(["주문 · 판매계정", "단계", "#수량", "거점 · LOT", "생성", "만료", "비고"], [
            ["O-26112100341 · 쿠팡", chip("할당", "info"), "2", "파스토 · 2610-C", "13:20", "—", "F-88229 출고 지시"],
            ["O-26112100322 · 네이버", chip("예약", "mute"), "1", "파스토", "13:51", "결제 후 72시간", ""],
            ["O-26112100377 · 카페24", chip("예약", "mute"), "1", "화성", "11:15", "결제 후 72시간", "묶음 GIFT-BOX 부품 동시 예약"],
            ["O-26112100198 · 쿠팡", chip("피킹", "warn"), "4", "화성 · 2611-A", "09:02", "—", "피킹 시작 서창고"],
            ["O-26112000911 · 네이버", chip("예약 만료 임박", "err"), "1", "화성", "11-20 15:10", "오늘 15:10", "미결제 · 자동 해제 예정"],
        ], None, "최근 확보 내역"),
        p("동시에 들어온 주문은 SKU·거점 단위로 잠금 후 판매가능 수량을 확인해 예약합니다. 가상 묶음은 모든 부품이 확보될 때만 예약되고, 하나라도 부족하면 전체가 예약되지 않습니다."),
    ]),
)

# ---------------- 재고 > LOT·유통기한 ----------------
S["LotExpiry"] = dict(
    title="LOT·유통기한", nav="inventory", module="재고", tab="LOT·유통기한", user=("박물류", "물류관리자"),
    search="SKU, LOT",
    body="\n".join([
        head("LOT·유통기한", "LOT별 수량과 남은 유통기한입니다. 출고는 유통기한이 빠른 LOT부터(FEFO) 배정합니다.", "M3",
             '<button class="btn" type="button">임박 LOT 판매 제한</button><button class="btn" type="button">폐기 요청</button>'),
        kpis([("관리 SKU", "41"), ("30일 내 만료", "2 LOT", "C 푸드", "#A11A1A"), ("90일 내 만료", "5 LOT", "", "#8A4300"), ("LOT 미기록 입고", "1건", "GRN-500 검토 필요")]),
        tabs(["전체", "출고 허용 기준 미달 2", "90일 내 만료 5", "LOT 미기록 1"], "필터"),
        tcard(["SKU · LOT", "사업자 · 거점", "제조일", "유통기한", "#남은 일수", "#수량", "출고 허용 기준", "상태"], [
            ["GRN-500 · 2605-B", "C 푸드 · 화성", "2026-05-12", "2026-12-10", "19", "84", "잔여 30일 이상", chip("출고 제한", "err")],
            ["GRN-500 · 2606-A", "C 푸드 · 화성", "2026-06-03", "2026-12-18", "27", "40", "잔여 30일 이상", chip("출고 제한", "err")],
            ["GRN-500 · 2609-C", "C 푸드 · 화성", "2026-09-20", "2027-03-20", "119", "310", "잔여 30일 이상", chip("정상", "ok")],
            ["AMP-30 · 2610-C", "A 코스메틱 · 파스토", "2026-10-02", "2028-10-01", "680", "248", "잔여 180일 이상", chip("정상", "ok")],
            ["AMP-30 · 2611-A", "A 코스메틱 · 화성", "2026-11-03", "2028-11-02", "712", "420", "잔여 180일 이상", chip("정상", "ok")],
        ], ["sel"]),
        note("GRN-500 2605-B · 2606-A는 출고 허용 기준(잔여 30일, C 푸드 사업자 설정값)에 못 미쳐 판매가능에서 빠졌습니다. 할인 판매로 돌리려면 기준 예외 승인이 필요하고, 폐기는 별도 승인 후 폐기손실로 남습니다.", "warn"),
        p("출고 허용 기준은 사업자 재고정책에서 정하고, 거점·채널별로 다르게 줄 수 있습니다. 3PL이 LOT을 주지 않는 경우 LOT 미기록으로 표시하고 임의로 채우지 않습니다."),
    ]),
)

# ---------------- 재고 > 재고이동 ----------------
S["Transfers"] = dict(
    title="재고이동", nav="inventory", module="재고", tab="재고이동", user=("박물류", "물류관리자"),
    biz=["A 코스메틱"], search="이동번호, SKU", full=True,
    body=split(
        "\n".join([
            head("재고이동", "같은 사업자 재고를 거점 간에 옮깁니다. 이동 중 수량은 어느 거점에서도 판매가능이 아닙니다.", "M3",
                 '<button class="btn pri" type="button">이동 요청</button>'),
            tabs(["진행 중 2", "완료", "차이 발생 1"], "상태"),
            tcard(["이동번호", "SKU", "출발 → 도착", "#요청", "#출발", "#도착", "상태"], [
                ["TR-2611-008", "AMP-30", "화성 → 파스토 용인", "200", "200", "—", chip("이동 중", "info")],
                ["TR-2611-007", "CLF-150", "화성 → 파스토 용인", "300", "300", "296", chip("도착 차이 −4", "err")],
                ["TR-2611-006", "BOX-S", "화성 → 파스토 용인", "2,000", "2,000", "2,000", chip("완료", "ok")],
            ], ["sel"]),
            p("사업자가 다른 재고는 이동이 아니라 사업자 간 거래(매입·매출)로 처리합니다. 손익 화면의 내부거래 경고(REV-074)와 연결됩니다."),
        ]),
        "TR-2611-008 · AMP-30 200개",
        "\n".join([
            '        <ol aria-label="진행" style="list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px">'
            '<li class="step done"><span class="dot" aria-hidden="true">1</span>요청 · 박물류 11-20 16:10</li>'
            '<li class="step done"><span class="dot" aria-hidden="true">2</span>화성 출고 확정 · 200 · 11-21 09:40</li>'
            '<li class="step on" aria-current="step"><span class="dot" aria-hidden="true">3</span>이동 중 · 예정 도착 11-21 17:00</li>'
            '<li class="step"><span class="dot" aria-hidden="true">4</span>파스토 입고 확인(파스토 입고 결과 수신)</li></ol>',
            rows_kv([("원장", "화성 실재고 −200 · 이동 중 +200"), ("도착 시", "이동 중 −도착 수량 · 파스토 +도착 수량"), ("차이 발생 시", "차이 수량은 '이동 차이'로 남기고 조정 승인 대기"), ("운송비", "예상 120,000원 · 물류비 대사 대상")]),
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn" type="button">이동 취소(출발 전만)</button><button class="btn" type="button">이력</button></div>',
        ])),
)

# ---------------- 재고 > 비가용·불용재고 ----------------
cats = [("검수대기", 32, None), ("불량", 9, 81000), ("파손", 4, 20800), ("유통기한 기준 미달", 124, 186000), ("3PL 수량 차이 보류", 6, 246000), ("불용(180일 미판매)", 210, 2_520_000)]
S["Unsellable"] = dict(
    title="비가용·불용재고", nav="inventory", module="재고", tab="비가용·불용재고", user=("정재무", "재무 · 원가 조회 권한"),
    search="SKU, LOT",
    body="\n".join([
        head("비가용·불용재고", "판매가능에서 빠진 재고를 사유별로 봅니다. 평가손실·폐기손실은 각각 한 번만 원장에 남습니다.", "M3",
             '<button class="btn" type="button">재판정 요청</button><button class="btn" type="button">평가손실 반영</button><button class="btn dan" type="button">폐기 요청</button>'),
        tcard(["사유", "#수량", "#원가 기준 평가액", "다음 조치"],
              [[c, f"{q:,}", (won(v) if v is not None else "검수 후 결정"), a] for (c, q, v), a in zip(cats, [
                  "검수 → 정상/불량 판정", "반품 출고 또는 폐기 요청", "폐기 요청", "할인 판매 승인 또는 폐기", "3PL 대사 완료 시 자동 해제", "할인·묶음 판매 제안(인사이트)"])], None, "사유별"),
        tcard(["SKU · LOT", "사업자 · 거점", "사유", "#수량", "#원가", "평가손실", "폐기"], [
            ["GRN-500 · 2605-B", "C 푸드 · 화성", "유통기한 기준 미달", "84", "1,500", chip("반영 126,000 · 11-19", "ok"), chip("요청 없음", "mute")],
            ["CLF-150 · 2608-A", "A 코스메틱 · 파스토", "파손", "4", "5,200", chip("미반영", "mute"), chip("승인 대기 정재무→김운영", "warn")],
            ["RUG-GR-160 · —", "B 리빙 · 품고 김포", "3PL 수량 차이 보류", "6", "41,000", chip("대상 아님", "mute"), chip("대상 아님", "mute")],
        ]),
        note("GRN-500 2605-B는 11-19에 평가손실 126,000원(84개 × 1,500원)이 반영됐습니다. 이 LOT을 폐기하면 폐기손실은 장부가(원가 − 기반영 평가손실) 기준으로 잡혀 같은 금액이 두 번 비용이 되지 않습니다.", "info"),
        p("원가·평가액은 원가 조회 권한이 있는 사용자에게만 보입니다. 폐기는 사업자 승인정책의 금액 기준에 따라 승인 후 실행되고, 요청자는 스스로 승인할 수 없습니다."),
    ]),
)
