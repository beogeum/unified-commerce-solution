from lib import *

S = {}
RAW = {}
INT = ("최연동", "연동 담당")
ADM = ("김운영", "운영관리자")

# ---------------- 연동 > 파일 연동 ----------------
S["IntegrationFile"] = dict(
    title="파일 연동", nav="integrations", module="연동", tab="파일 연동", user=INT, full=True,
    body=split(
        "\n".join([
            head("파일 연동", "API가 확인되지 않은 연동은 파일로 받습니다. 파일 원본·해시·양식 버전·매핑 규칙 버전을 함께 남겨 언제든 다시 처리할 수 있습니다.", "M2",
                 '<button class="btn pri" type="button">파일 업로드</button>'),
            tcard(["양식", "대상", "#버전", "최근 수신", "#처리 행", "결과"], [
                ["<b>품고 재고 현황</b>", "품고 김포 · B-01", "v3", "11-21 13:10", "214", chip("판단 보류 1", "warn")],
                ["<b>쿠팡 정산서</b>", "쿠팡 · A 코스메틱", "v2", "11-15 09:02", "1,842", chip("성공", "ok")],
                ["<b>파스토 10월 청구서</b>", "파스토 · FASSTO-A01", "v1", "11-05 10:30", "38", chip("대사 차이 2", "warn")],
                ["<b>광고 리포트</b>", "네이버 검색광고 · A·B·C", "v1", "11-02 08:40", "1,120", chip("성공", "ok")],
                ["<b>택배 배송 추적</b>", "계약 택배사", "v1", "—", "—", chip("양식만 등록", "mute")],
            ], ["sel"]),
            p("같은 파일(같은 해시)을 다시 올리면 새로 처리하지 않고 이전 결과를 보여 줍니다. 매핑 규칙을 바꿔 다시 처리하면 새 처리 이력으로 남고, 이미 확정된 원장은 덮어쓰지 않습니다."),
        ]),
        "품고 재고 현황 v3 · 11-21 13:10",
        "\n".join([
            rows_kv([("파일", "pumgo_stock_20261121.xlsx"), ("SHA-256", "7e0c…a91f"), ("업로드", "최연동 · 원본 90일 보관"), ("매핑 규칙", "PUMGO-STOCK v3 (외부 SKU → 내부 SKU 매핑표 v12)"), ("수량 의미", "가용 수량 · 출고분 반영 시점 표기 없음")]),
            '        <div class="note n-warn"><b>RUG-GR-160 1행 판단 보류</b> — 파일 수량 54가 13:00 이후 출고 6건을 반영했는지 알 수 없습니다. 다시 차감하지 않고 판매 제한 + 검토 업무를 만들었습니다.</div>',
            '        <div style="display: flex; gap: 8px; flex-wrap: wrap"><button class="btn" type="button">행별 결과</button><button class="btn" type="button">새 규칙으로 다시 처리</button></div>',
        ])),
)

# ---------------- 연동 > 연동 진단 ----------------
S["IntegrationDiag"] = dict(
    title="연동 진단", nav="integrations", module="연동", tab="연동 진단", user=INT,
    body="\n".join([
        head("연동 진단", "판매계정·화주계정별 인증, 지원 기능 확인, 최근 작업과 전송 대기열 상태입니다. 실채널 API는 권한 확인 전이라 모의 연동으로만 실행됩니다.", "M2",
             '<button class="btn" type="button">지원 기능 다시 확인</button>'),
        kpis([("전송 대기(outbox)", "12건", "가장 오래된 것 40초"), ("재시도 대기", "1건", "F-88241 · 3/5회"), ("검토 필요", "2건", "자동 재시도 중단", "#A11A1A"), ("최근 1시간 오류율", "0.8%")]),
        tcard(["계정", "연동 방식", "인증", "지원 기능 확인", "최근 성공", "#오류(24시간)", "상태"], [
            ["네이버 · A 공식스토어", "mock-channel", chip("모의 자격", "info"), "주문 수집 · 송장 · 문의: 모의 확인 / 실채널 미확인", "14:05", "0", chip("정상", "ok")],
            ["쿠팡 · A 코스메틱", "mock-channel", chip("모의 자격", "info"), "주문 수집: 모의 확인 / 상품 수정: 미확인", "14:04", "3", chip("주의", "warn")],
            ["카페24 · B 기본몰", "mock-channel", chip("모의 자격", "info"), "주문 수집: 모의 확인", "14:03", "0", chip("정상", "ok")],
            ["파스토 · FASSTO-A01", "mock-3pl", chip("모의 자격", "info"), "출고 지시 · 결과 · 재고: 모의 확인", "13:40", "1", chip("재시도 대기", "warn")],
            ["품고 · B-01", "파일", "—", "재고 파일 v3", "13:10", "0", chip("판단 보류 1", "warn")],
        ]),
        tcard(["작업", "대상", "#시도", "마지막 오류(사용자 안내)", "다음 시도", ""], [
            ["출고 지시 전송 F-88241", "파스토", "3/5", "물류사가 일시적으로 응답하지 않습니다.", "14:12", '<button class="btn" type="button">지금 재시도</button>'],
            ["상품 가격 전송 AMP-30", "카페24 자사몰", "—", "이 계정은 상품 수정 지원 기능이 확인되지 않았습니다.", "검토 필요", '<button class="btn" type="button">엑셀로 받기</button>'],
            ["주문 수집 11-21 13:00 구간", "쿠팡", "5/5", "응답 형식이 예상과 다릅니다(원본 보관됨).", "검토 필요", '<button class="btn" type="button">원본 보기</button>'],
        ], None, "재시도 · 검토 필요"),
        p("재시도·재처리는 같은 멱등키로 실행돼 중복 전송되지 않으며, 누가 언제 실행했는지 감사로그에 남습니다. 기술 상세(응답 코드·원문)는 연동 이력에만 보관하고 화면에는 한국어 안내만 표시합니다."),
    ]),
)

# ---------------- 연동 > 택배·광고·기타 ----------------
S["IntegrationOthers"] = dict(
    title="택배·광고·기타 연동", nav="integrations", module="연동", tab="택배·광고·기타", user=INT,
    body="\n".join([
        head("택배 · 광고 · 기타 연동", "판매채널·3PL 외 연동입니다. 모두 실제 권한·지원 기능이 확인되지 않아 '계획' 또는 '파일' 상태입니다.", "M2"),
        tcard(["종류", "대상", "쓰임", "방식", "지원 기능", "상태"], [
            ["택배", "계약 택배사(자체 출고)", "송장 발급 · 배송 추적", "파일(송장 대역 업로드)", chip("미확인", "mute"), chip("계획", "mute")],
            ["광고", "네이버 검색광고", "광고비 · 보고 매출", "파일(리포트)", chip("미확인", "mute"), chip("파일 운영", "info")],
            ["광고", "쿠팡 광고", "광고비", "파일(리포트)", chip("미확인", "mute"), chip("파일 운영", "info")],
            ["알림", "이메일", "업무·승인 알림", "내부 발송", chip("내부 기능", "ok"), chip("사용 중", "ok")],
            ["알림", "메신저 알림톡", "고객 안내", "—", chip("미확인", "mute"), chip("계획", "mute")],
            ["회계", "회계 프로그램", "매출·매입 전표", "파일(내보내기)", chip("미확인", "mute"), chip("계획", "mute")],
        ]),
        note("확인되지 않은 연동은 '지원'으로 표시하지 않습니다. 실제 연동은 사용자 계정별 자격·권한·버전을 확인한 뒤 연동 진단에서 지원 기능을 켭니다.", "info"),
    ]),
)

# ---------------- 설정 > 운영그룹 ----------------
def toggle(on):
    return f'<span class="tg{" on" if on else ""}" role="img" aria-label="{"켜짐" if on else "꺼짐"}"></span>'

S["WorkspaceSettings"] = dict(
    title="운영그룹 설정", nav="settings", settings="운영그룹", user=ADM,
    body="\n".join([
        head("운영그룹 설정", "운영그룹 전체에 적용되는 기본값입니다. 사업자별 설정은 이 값을 바꿀 수 있는 항목만 바꿉니다.", "M1"),
        section("기본 정보", rows_kv([("운영그룹 이름", "데모 운영그룹"), ("기본 통화", "KRW(원) — 다른 통화는 지원하지 않음"), ("시간대", "Asia/Seoul (+09:00)"), ("금액 표시", "원 단위 · 천 단위 쉼표"), ("원 미만 처리", "부가세: 포함금액 ÷ 11 원 미만 절사 · 그 외 반올림 · 배부 잔액은 최대 행에")]), "ws-1"),
        section("보안", rows_kv([("로그인 세션 만료", "12시간 · 비활동 2시간 · 비밀번호 변경 시 전체 로그아웃"), ("동시 로그인", "허용 · 세션 목록에서 강제 종료"), ("비밀번호 정책", "2종 조합 10자 이상 또는 3종 조합 8자 이상 · 연속·반복 문자 금지"), ("2단계 인증(OTP 앱)", toggle(True) + " 최고관리자·운영관리자 필수 · 그 외 선택")]), "ws-2"),
        section("개인정보 · 데이터", rows_kv([("주문자 개인정보", "배송완료 90일 후 마스킹 · 원본 분리 보관 후 법정 기간(5년/3년) 경과 시 파기"), ("연동 원본 payload 보관", "90일 · 암호화"), ("내보내기", "권한자만 · 사유 입력 · 감사로그"), ("감사로그 보관", "5년 · 수정·삭제 불가")]), "ws-3"),
        '      <div style="display: flex; gap: 8px"><button class="btn pri" type="button">변경 저장</button></div>',
        p("운영그룹 설정 변경은 슈퍼관리자만 가능하고, 변경 전후 값이 변경이력에 남습니다."),
    ]),
)

# ---------------- 설정 > 화주계정 ----------------
S["ShipperAccounts"] = dict(
    title="화주계정", nav="settings", settings="화주계정", user=ADM,
    body="\n".join([
        head("화주계정", "3PL에 등록된 사업자의 화주계정입니다. 자격증명은 암호화 저장되고 화면에는 마스킹되어 보입니다.", "M1",
             '<button class="btn pri" type="button">화주계정 추가</button>'),
        tcard(["화주계정", "물류사업자", "사업자", "출고거점", "자격증명", "연동 방식", "지원 기능", "상태"], [
            ["<b>FASSTO-A01</b>", "파스토", "A 코스메틱", "파스토 용인", "API 키 ****7c2e · 11-01 등록", "mock-3pl", "3개 모의 확인 · 2개 미확인", chip("운영", "ok")],
            ["<b>B-01</b>", "품고", "B 리빙", "품고 김포", "없음(파일)", "파일", "재고 파일 v3", chip("운영", "ok")],
        ]),
        note("화주계정 하나는 한 사업자에만 속합니다. 다른 사업자의 재고를 같은 화주계정으로 받으면 소유권이 섞이므로 등록할 수 없습니다.", "info"),
        p("자격증명 교체는 운영관리자 이상, 원문은 누구에게도 다시 보이지 않습니다. 교체·연결 테스트는 감사로그에 남습니다."),
    ]),
)

# ---------------- 설정 > 정산계좌 ----------------
S["BankAccounts"] = dict(
    title="정산계좌", nav="settings", settings="정산계좌", user=("김운영", "운영관리자 · 계좌 원문 권한 없음"),
    body="\n".join([
        head("정산계좌", "사업자별 정산 입금 계좌입니다. 판매계정마다 어떤 계좌로 정산받는지 연결합니다.", "M1",
             '<button class="btn pri" type="button">계좌 추가</button>'),
        tcard(["사업자", "은행", "계좌번호", "예금주", "연결된 판매계정", "기본", "상태"], [
            ["A 코스메틱", "기업", "***-******-01-234", "(주)에이코스메틱", "네이버 · 쿠팡 · 카페24", chip("기본", "info"), chip("확인됨", "ok")],
            ["B 리빙", "국민", "***-**-****-567", "(주)비리빙", "카페24 기본몰", chip("기본", "info"), chip("확인됨", "ok")],
            ["C 푸드", "농협", "***-****-****-89", "씨푸드(개인)", "스마트스토어", chip("기본", "info"), chip("변경 승인 대기", "warn")],
        ]),
        note("C 푸드 계좌 변경 요청(김운영 · 11-20)이 승인 대기 중입니다. 승인 전까지 기존 계좌가 유지되고, 요청자는 스스로 승인할 수 없습니다.", "warn"),
        p("계좌번호는 암호화 저장되고 원문 보기는 '계좌 원문 조회' 권한자만 사유를 입력해 볼 수 있습니다. 원문 보기·변경 모두 감사로그에 남습니다."),
    ]),
)

# ---------------- 설정 > 승인정책 ----------------
S["ApprovalPolicy"] = dict(
    title="승인정책", nav="settings", settings="승인정책", user=ADM,
    body="\n".join([
        head("승인정책", "민감하거나 되돌리기 어려운 작업에 승인 단계를 둡니다. 금액 기준은 사업자별로 정합니다.", "M1"),
        tcard(["작업", "승인 조건", "승인자", "자기 승인", "영향도 미리보기"], [
            ["최저판매가 변경", "항상", "운영관리자", "불가", "예상 공헌이익 변화"],
            ["재고 조정(실사 차이 포함)", "원가 기준 100,000원 이상", "재무 + 운영관리자", "불가", "원장 변동 · 금액"],
            ["재고 폐기", "항상", "운영관리자", "불가", "폐기손실 · 기반영 평가손실"],
            ["정산 조정", "100,000원 이상", "운영관리자", "불가", "정산액 · 손익 영향"],
            ["수동 환불", "항상", "운영관리자", "불가", "환불액 · 재고 영향"],
            ["권한 변경", "권한 확대 시", "슈퍼관리자", "불가", "허용 권한 · 범위 변화"],
            ["개인정보 포함 내보내기", "항상", "운영관리자", "불가", "건수 · 항목"],
            ["월 마감 해제", "항상", "슈퍼관리자", "불가", "영향 원장 · 재마감 필요"],
        ]),
        p("승인 요청·승인·반려는 모두 감사로그에 남고, 승인 후 실행이 실패해 재시도할 때도 같은 승인 건으로 추적됩니다. 금액 기준이 없는 사업자는 '항상 승인'으로 동작합니다."),
    ]),
)

# ---------------- 설정 > 알림정책 ----------------
S["NotificationPolicy"] = dict(
    title="알림정책", nav="settings", settings="알림정책", user=ADM,
    body="\n".join([
        head("알림정책", "어떤 일이 생기면 누구에게 어떻게 알릴지 정합니다. 개인 설정은 여기서 허용한 범위 안에서만 바꿀 수 있습니다.", "M1"),
        tcard(["이벤트", "받는 사람(역할)", "앱 내", "이메일", "묶음", "야간(22–08시)"], [
            ["출고 마감 1시간 전 미처리", "물류관리자", toggle(True), toggle(True), "즉시", "보내지 않음"],
            ["3PL 재고 판단 불가", "물류관리자 · 운영관리자", toggle(True), toggle(True), "즉시", "보내지 않음"],
            ["승인 요청", "해당 승인자", toggle(True), toggle(True), "즉시", "다음 날 08시"],
            ["연동 검토 필요", "연동 담당", toggle(True), toggle(False), "30분 묶음", "보내지 않음"],
            ["CS 응답 기한 초과", "CS 담당", toggle(True), toggle(False), "즉시", "보내지 않음"],
            ["정산 차이 발생", "재무", toggle(True), toggle(True), "하루 1회 09시", "—"],
        ]),
        p("알림에는 개인정보·원가를 넣지 않고 화면 링크만 담습니다. 받는 사람은 해당 사업자·계정 접근 범위가 있는 사용자로 한정됩니다."),
    ]),
)

# ---------------- 설정 > 원가·마감 정책 ----------------
S["CostClosePolicy"] = dict(
    title="원가·마감 정책", nav="settings", settings="원가·마감 정책", user=ADM, biz=["A 코스메틱"],
    body="\n".join([
        head("원가 · 마감 정책 — A 코스메틱", "원가 계산 방식과 월 마감 규칙입니다. 방식 변경은 마감된 기간에 소급되지 않고 다음 기간부터 적용됩니다.", "M5"),
        section("원가", rows_kv([("원가 방식", "이동평균(v1 기준 방식)"), ("부대비용", "운반비·통관비는 입고 원가에 포함"), ("원 미만", "반올림"), ("부자재 원가", "출고 건별 포장 BOM 버전 고정 · 3PL 출고는 청구액"), ("원가 미등록 SKU", "손익 '계산 불가'로 표시 · 0원으로 계산하지 않음")]), "cc-1"),
        section("예상 · 실제 비용", rows_kv([("물류비", "출고 시 예상 → 청구서 대사 후 실제로 대체(합산하지 않음)"), ("수수료", "주문 시 예상 → 정산서 확정 후 실제로 대체"), ("차이 허용 범위", "건별 100원 미만 자동 확정 · 초과 시 검토 업무")]), "cc-2"),
        section("월 마감", rows_kv([("마감 일정", "익월 5영업일 · 재무 마감 → 운영관리자 확인"), ("마감 후 변경", "기존 원장 수정 불가 · 다음 달 조정으로 반영"), ("마감 해제", "슈퍼관리자 승인 · 사유 필수 · 재마감 필요"), ("마감 전 점검", "미대사 정산 · 미확정 물류비 · 원가 미등록 · 실사 차이")]), "cc-3"),
        '      <div style="display: flex; gap: 8px"><button class="btn pri" type="button">변경 요청(다음 기간 적용)</button></div>',
    ]),
)

# ---------------- 상태 모음(Empty/Loading/Error/권한) ----------------
def state_card(title, inner):
    return f'<section class="card" style="padding: 16px; display: flex; flex-direction: column; gap: 10px; min-width: 0"><h2 style="margin: 0; font-size: 15px">{title}</h2>{inner}</section>'

skel = '<div style="height: 12px; border-radius: 6px; background: #ECEEF2"></div>'
S["StatesKit"] = dict(
    title="화면 상태 모음", nav="home", user=ADM,
    body="\n".join([
        head("화면 상태 모음", "모든 목록·상세 화면이 따르는 빈 화면 · 불러오는 중 · 오류 · 권한 없음 · 확인 대화상자 규칙입니다.", "공통"),
        '      <div class="grid2">',
        "        " + state_card("빈 화면 — 데이터 없음", '<p style="margin: 0; font-size: 14px">아직 등록된 공급처가 없습니다.</p><p class="ctx" style="margin: 0">공급처를 추가하면 발주를 만들 수 있습니다.</p><div><button class="btn pri" type="button">공급처 추가</button></div>'),
        "        " + state_card("빈 화면 — 검색 결과 없음", '<p style="margin: 0; font-size: 14px">\'AMP-31\'과 일치하는 SKU가 없습니다.</p><p class="ctx" style="margin: 0">필터 3개가 적용되어 있습니다.</p><div><button class="btn" type="button">필터 지우기</button></div>'),
        "        " + state_card("불러오는 중", '<div role="status" aria-live="polite" class="ctx">주문을 불러오는 중입니다…</div>' + skel * 4),
        "        " + state_card("오류", '<div class="note n-err" role="alert"><b>주문 목록을 불러오지 못했습니다.</b> 잠시 후 다시 시도해 주세요. 문제가 계속되면 오류번호 E-7F3A2를 알려 주세요.</div><div><button class="btn" type="button">다시 시도</button></div><p class="ctx" style="margin: 0">기술 상세는 화면에 보이지 않고 내부 로그에만 남습니다.</p>'),
        "        " + state_card("권한 없음", '<p style="margin: 0; font-size: 14px">이 화면을 볼 권한이 없습니다.</p><p class="ctx" style="margin: 0">필요한 권한: 원가 조회. 운영관리자에게 요청하세요.</p><div><button class="btn" type="button">권한 요청</button></div>'),
        "        " + state_card("찾을 수 없음", '<p style="margin: 0; font-size: 14px">요청한 주문을 찾을 수 없습니다.</p><p class="ctx" style="margin: 0">삭제됐거나 접근 범위 밖일 수 있습니다. (다른 운영그룹·제한된 사업자는 존재 여부도 알려 주지 않습니다.)</p>'),
        "        " + state_card("확인 대화상자 — 영향도 미리보기", '<div style="border: 1px solid #CDD2D9; border-radius: 10px; padding: 14px; display: flex; flex-direction: column; gap: 8px"><b>판매상태 18개를 \'판매중지\'로 바꿀까요?</b><div class="row"><span>대상</span><span>쿠팡 · A 코스메틱 18개</span></div><div class="row"><span>채널 반영</span><span>비동기 · 지원 기능 확인 계정만</span></div><div class="row"><span>되돌리기</span><span>다시 \'판매중\'으로 일괄 변경</span></div><div style="display: flex; gap: 8px"><button class="btn pri" type="button">18개 변경</button><button class="btn" type="button">취소</button></div></div>'),
        "        " + state_card("동시 수정 · 중복 제출", '<div class="note n-warn">다른 사용자(한상품)가 먼저 이 상품을 수정했습니다. 최신 내용을 확인한 뒤 다시 저장해 주세요.</div><div><button class="btn" type="button">최신 내용 보기</button></div><p class="ctx" style="margin: 0">저장 버튼은 요청 중 비활성화되고, 같은 요청은 멱등키로 한 번만 처리됩니다.</p>'),
        "      </div>",
    ]),
)

# ---------------- 로그인 (raw) ----------------
RAW["Login"] = ("로그인", "", '''<div style="min-height: 900px; display: flex; align-items: center; justify-content: center; padding: 24px; box-sizing: border-box; font-family: 'IBM Plex Sans KR', system-ui, sans-serif; color: #15191F">
  <div style="display: flex; flex-wrap: wrap; gap: 24px; justify-content: center; width: 100%; max-width: 1340px">
    <main class="card" style="flex: 1 1 360px; max-width: 420px; padding: 28px; display: flex; flex-direction: column; gap: 14px; box-sizing: border-box">
      <div style="display: flex; align-items: center; gap: 10px"><div aria-hidden="true" style="width: 32px; height: 32px; border-radius: 8px; background: #15191F; color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700">C</div><h1 style="margin: 0; font-size: 20px">커머스 운영 OS 로그인</h1></div>
      <label class="fld">이메일<input class="inp" type="email" value="ops@example.com" autocomplete="username"></label>
      <label class="fld">비밀번호<input class="inp" type="password" value="••••••••••" autocomplete="current-password"></label>
      <div class="note n-err" role="alert">이메일 또는 비밀번호가 맞지 않습니다. 5회 틀리면 15분 동안 로그인할 수 없습니다(남은 횟수 3회).</div>
      <button class="btn pri" type="button">로그인</button>
      <a href="#" style="font-size: 13px">비밀번호를 잊으셨나요?</a>
      <span class="chip c-mute" style="align-self: flex-start">F1 시안 · Foundation 인증 API 구현됨</span>
    </main>
    <section class="card" aria-labelledby="mfa-h" style="flex: 1 1 360px; max-width: 420px; padding: 28px; display: flex; flex-direction: column; gap: 12px; box-sizing: border-box">
      <h2 id="mfa-h" style="margin: 0; font-size: 18px">2단계 인증</h2>
      <p class="ctx" style="margin: 0">OTP 앱에 표시된 6자리 숫자를 입력하세요. 최고관리자·운영관리자는 반드시 등록해야 합니다.</p>
      <label class="fld">인증 코드<input class="inp" inputmode="numeric" autocomplete="one-time-code" value="482 913"></label>
      <button class="btn pri" type="button">확인</button>
      <a href="Login.dc.html" style="font-size: 13px">OTP 앱을 쓸 수 없나요? 복구 코드 입력</a>
    </section>
    <section class="card" aria-labelledby="ws-h" style="flex: 1 1 360px; max-width: 420px; padding: 28px; display: flex; flex-direction: column; gap: 12px; box-sizing: border-box">
      <h2 id="ws-h" style="margin: 0; font-size: 18px">운영그룹 선택</h2>
      <p class="ctx" style="margin: 0">로그인 후 들어갈 운영그룹을 고릅니다. 세션은 선택한 운영그룹에만 묶이며, 다른 운영그룹 데이터는 보이지 않습니다.</p>
      <a class="card" href="Main.dc.html" style="padding: 12px; text-decoration: none; color: inherit; display: flex; justify-content: space-between; align-items: center; border-color: #2350C8"><span><b>데모 운영그룹</b><br><span class="ctx">사업자 3 · 운영관리자</span></span><span class="chip c-info">마지막 사용</span></a>
      <a class="card" href="#" style="padding: 12px; text-decoration: none; color: inherit; display: flex; justify-content: space-between; align-items: center"><span><b>테스트 운영그룹</b><br><span class="ctx">사업자 1 · 조회 전용</span></span></a>
      <p class="ctx" style="margin: 0">운영그룹을 바꾸면 새 세션이 발급되고 이전 운영그룹 세션은 유지됩니다.</p>
    </section>
  </div>
</div>''', 1440, 900)

# ---------------- 모바일 홈 (raw, 390×844) ----------------
RAW["MobileHome"] = ("모바일 홈", ".mt a{flex:1;display:flex;flex-direction:column;align-items:center;gap:2px;font-size:11px;color:#59616C;text-decoration:none;padding:8px 0}.mt a.on{color:#163A99;font-weight:600}", '''<div style="min-height: 844px; display: flex; flex-direction: column; font-family: 'IBM Plex Sans KR', system-ui, sans-serif; color: #15191F; background: #F4F5F7">
  <header style="background: #fff; border-bottom: 1px solid #E3E6EA; padding: 12px 16px; display: flex; justify-content: space-between; align-items: center; gap: 8px">
    <div><div class="ctx">데모 운영그룹 · 전체 사업자</div><h1 style="margin: 0; font-size: 18px">오늘</h1></div>
    <button class="btn" type="button" aria-label="알림 5건">알림 5</button>
  </header>
  <main style="flex: 1 1 auto; padding: 16px; display: flex; flex-direction: column; gap: 12px">
    <section aria-label="요약" style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px">
      <div class="card kpi"><span class="ctx">오늘 주문</span><b>1,284</b></div>
      <div class="card kpi"><span class="ctx">마감 전 미처리</span><b style="color: #8A4300">47</b></div>
      <div class="card kpi"><span class="ctx">업무함</span><b style="color: #A11A1A">7</b></div>
      <div class="card kpi"><span class="ctx">CS 미답변</span><b>14</b></div>
    </section>
    <section class="card" aria-labelledby="m-h" style="padding: 14px; display: flex; flex-direction: column; gap: 8px">
      <h2 id="m-h" style="margin: 0; font-size: 15px">지금 볼 일</h2>
      <a href="WorkQueue.dc.html" class="row" style="text-decoration: none; color: inherit"><span>품고 재고 판단 불가 · RUG-GR-160</span><span class="chip c-err">긴급</span></a>
      <a href="OrderHolds.dc.html" class="row" style="text-decoration: none; color: inherit"><span>주소 보류 1건 · 16:00 마감</span><span class="chip c-warn">2시간</span></a>
      <a href="ApprovalPolicy.dc.html" class="row" style="text-decoration: none; color: inherit"><span>승인 요청 2건</span><span class="chip c-info">대기</span></a>
      <a href="IntegrationDiag.dc.html" class="row" style="text-decoration: none; color: inherit"><span>파스토 전송 재시도 대기</span><span class="chip c-warn">3/5</span></a>
    </section>
    <p class="ctx" style="margin: 0">모바일은 확인·승인·간단 처리 위주입니다. 대량 작업과 내보내기는 PC에서만 제공합니다. <span class="chip c-mute">M7 시안</span></p>
  </main>
  <nav class="mt" aria-label="하단 메뉴" style="display: flex; background: #fff; border-top: 1px solid #E3E6EA">
    <a class="on" href="MobileHome.dc.html" aria-current="page">홈</a><a href="WorkQueue.dc.html">업무함</a><a href="Orders.dc.html">주문</a><a href="WmsMobile.dc.html">WMS</a><a href="BusinessEntities.dc.html">더보기</a>
  </nav>
</div>''', 390, 844)
