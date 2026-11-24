# CS 문의 API (P1, FR-CS-01·05)

채널 문의 연동 전: 채널 문의는 옮겨 적고 답변은 채널에 직접 등록 후 표시한다(자동 고객 전송 없음, DLG-201~206).

| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /cs/inquiries {order_id? \| channel_account_id, order_item_id?, source(CHANNEL·PHONE·EMAIL·CHAT·OTHER), external_inquiry_key?, inquiry_type, title, message, customer_name?, customer_phone?, priority?, assignee_member_id?}` | `order.update` + 판매계정 쓰기 범위 | 채널 문의번호 중복 `409 CS_INQUIRY_DUPLICATE`. 응답기한 자동 |
| `GET /cs/inquiries?business_entity_id&status(ACTIVE·OPEN·…)&inquiry_type&mine&overdue&order_id&q` | `order.read` + 범위 | 진행 중 → 중요도 → 기한 순 |
| `GET /cs/inquiries/{id}` | `order.read` | 메시지 스레드, 주문 요약(상태·송장·클레임 수) |
| `GET /cs/kpi` | `order.read` | 미답변·오늘 마감·기한 초과·처리 중·고객 재문의·채널 등록 대기 |
| `POST /cs/inquiries/{id}/messages {direction(INBOUND·OUTBOUND·NOTE), body, template_id?}` | `order.update` | 답변 → 답변 완료·채널 등록 필요, 고객 문의 → 다시 열림 |
| `POST /cs/inquiries/{id}/messages/{messageId}/channel-posted` | `order.update` | 채널 등록 완료 표시(`409 CS_MESSAGE_NOT_PENDING`) |
| `PATCH /cs/inquiries/{id} {status?, assignee_member_id?, priority?}` (+ If-Match 선택) | `order.update` | 답변 완료는 답변 기록으로만 |
| `POST /cs/inquiries/{id}/claim {claim_id}` | `order.update` | 같은 주문의 클레임 연결 |
| `GET /cs/templates?business_entity_id` · `POST /cs/templates` · `PUT /cs/templates/{id}` | 조회 `order.read` / 저장 `order.update` | 변수 {고객명} {주문번호} {상품} {송장번호} |
| `GET /cs/inquiries/{id}/render?template_id` | `order.read` | `{body, missing_variables}` |

## 영업일·CS 응답기한 (DLG-239~243)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /settings/business-calendar` | 구성원 | 요일별 영업시간(`default_hours` = 설정 전 기본값), 최근·다가오는 휴일, CS 응답기한 정책 |
| `PUT /settings/business-calendar/hours {hours[{weekday 1~7, start "HH:mm", end}]}` | `calendar.manage` | 전체 교체, 영업일 1개 이상 |
| `POST /settings/business-calendar/holidays {holidays[{day, name}]}` · `DELETE /settings/business-calendar/holidays/{id}` | 〃 | `HOLIDAY_DUPLICATE`, 지난 휴일 삭제 `HOLIDAY_IN_PAST` |
| `PUT /settings/business-calendar/cs-sla {mode: CALENDAR_HOURS \| BUSINESS_HOURS, base_hours}` (+ If-Match) | 〃 | 이후 접수·재문의부터 |
| `GET /settings/business-calendar/cs-sla/preview?from&priority` | 구성원 | 기한 미리보기 |
