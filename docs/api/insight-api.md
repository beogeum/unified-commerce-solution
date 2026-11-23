# 운영 리포트·인사이트 API (M6 1차)

읽기·추천 전용. 모든 응답은 사용자 사업자·판매계정·거점 접근 범위 안에서 계산한다. 비율은 % 문자열(소수 2자리), 분모 0 이면 `null`(계산불가).

## 1. 운영 리포트 (DLG-178)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /analytics/inventory?business_entity_id&cover_days(14)&slow_days(90)&expiry_days(60)` | `inventory.read` + 거점 범위 | 사업자 × SKU: 판매가능·비가용·확보·가용·28일 판매·일평균·재고일수·마지막 출고·가까운 유통기한·현재 원가·장부가·위험 표시, `totals` |
| `GET /analytics/sales?business_entity_id&from&to&group_by(channel_account·business_entity·sku)` | `order.read` + 판매계정 범위 | 주문·주문 수량·취소·출고·반품·교환·순판매수량·주문금액(판매자 할인 후, 부가세 포함)·취소율(분모 주문 수량)·반품율·교환율(분모 출고 수량), 합계(SKU 묶음은 주문 수 중복 제거) |
| `GET /analytics/logistics?business_entity_id&from&to` | `order.read` + 거점 범위(물류비는 `cost.read`) | 거점별 출고지시·송장·분할출고 주문·평균 리드타임·SLA 준수·출고 배송비·송장당·예상 비중 |

## 2. 인사이트 카드 (DLG-179~182)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `GET /insights?business_entity_id&status(OPEN·ACTIONED·DISMISSED·RESOLVED·ALL)&rule` | 규칙별(`profit.read`·`inventory.read`·`settlement.read`) | 중요도 → 최근 감지 순 |
| `GET /insights/{id}` | 〃 + 범위 | `metric`(비교기간 포함), `causes`(원인 후보·%p), `evidence`(확실성·제안·필터), `unconfirmed` |
| `GET /insights/last-run` | — | 마지막 분석 시각·감지·새로 열림·해결 |
| `POST /insights/refresh` | 위 조회 권한 중 하나 | 운영그룹 전체 계산(동시 계산 직렬화), `{detected, opened, resolved}` |
| `POST /insights/{id}/work-item` | `work_item.update` | 업무 `INSIGHT_REVIEW`, 카드 `ACTIONED`. 열린 카드만(`409 INSIGHT_NOT_OPEN`) |
| `POST /insights/{id}/dismiss {reason}` | `work_item.update` | 사유 필수, 해결될 때까지 유지 |

규칙: `MARGIN_DROP`, `STOCKOUT_RISK`, `SLOW_MOVING`, `EXPIRY_RISK`, `SETTLEMENT_MISSING`, `UNDEPOSITED`. 임계값 설정 `commerce.insight.*`(margin-drop-pp 3, min-net-sales 50000, window-days 30, cover-days 7, slow-days 90, expiry-days 60, settlement-missing-days 30, undeposited-grace-days 7, cron `0 17 6 * * *`).

## 3. 손익 시뮬레이션 (DLG-207~209)
| 메서드·경로 | 권한 | 비고 |
|---|---|---|
| `POST /simulations/profit {business_entity_id, channel_account_id?, sku_id?, from?, to?, price_change_pct?, elasticity?, extra_demand_pct?, commission_rate_pp?, free_shipping?, shipping_cost_change_pct?, stop_gifts?, gift_cost_change_pct?}` | `profit.read` + 사업자 범위 | `{base, simulated, contribution_delta, volume_factor, breakeven_volume_change_pct, assumptions, unconfirmed}`. 순매출 없으면 `SIMULATION_NO_SALES` |
| `POST /simulations/profit/work-item` (같은 본문 + note?) | `work_item.update` | 검토 업무 `SIMULATION_REVIEW` |

## 4. 상품정보 품질 점검 (DLG-212·213)
| `GET /catalog/quality?business_entity_id` | `catalog.read`(원가 점검은 `cost.read`) | `{counts, issues[{code, severity, title, business_entity_id, sku_id, sku_code, listing_id, detail, fix}]}` |
|---|---|---|

