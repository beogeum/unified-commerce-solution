# 대량 데이터 성능 점검

`backend/src/test/java/com/commerceos/perf/PerformanceSmokeTest.java` (`@Tag("perf")`, 기본 테스트 실행에서 제외).
API 로 만든 실제 주문·출고·원장 행을 서버에서 복제해 키운 뒤, 앱 역할·RLS 아래에서 주요 조회 API 를 3번씩 호출해 가장 빠른 값을 잰다(첫 호출 제외).

```bash
cd backend && mvn test -Dtest=PerformanceSmokeTest -Dperf.exclude=none        # 결과: target/perf-report.txt
# 규모 조절: -Dperf.orders=200000 -Dperf.entries=500000 -Dperf.txns=500000 -Dperf.customers=500, 실행 계획 보기: -Dperf.explain=true
```

## 2026-11-24 결과(개발 컨테이너, PostgreSQL 16 Testcontainers)
데이터: 주문 200,001 · 손익 원장 +499,998 · 재고 원장 +500,000 · 외상 거래처 500 × 청구 10.

| 조회 | 개선 전 | 개선 후 |
|---|---:|---:|
| 주문 목록(첫 페이지 50) | 15 ms | 17 ms |
| 주문 목록(상태 필터) | 14 ms | 11 ms |
| 주문 검색(주문번호) | 558 ms | 21 ms |
| 주문 검색(부분 문자열) | 534 ms | 11 ms |
| 재고 원장(첫 페이지 50) | 11 ms | 11 ms |
| 손익(사업자 전체 기간) | 55 ms | 44 ms |
| 손익 원장(첫 페이지) | 741 ms | 25 ms |
| 판매 리포트 | 10 ms | 6 ms |
| 재고 리포트 | 498 ms | 447 ms (후속) |
| 외상 현황(거래처 500) | 431 ms | 438 ms (후속) |
| 운영 현황 | 162 ms | 161 ms |

## 확인한 원인과 조치(DLG-251)
- 손익 원장 목록: `created_at desc` 정렬에 맞는 색인이 없어 전체 정렬 → `financial_entry_recent_idx`.
- 주문 검색: RLS 정책이 걸린 표에서는 `ilike`(누설 방지 아님)를 정책 조건보다 먼저 평가할 수 없어 trigram 색인을 쓰지 못하고 전체를 훑는다
  (앱 역할 실행 계획: Seq Scan, 20만 행 중 199,999 행 제거, 165 ms/표). → 검색 ID 찾기만 표 소유자 권한 함수 `app_search_order_ids`(운영그룹은 세션 문맥에서만)로, 목록은 RLS·범위 아래에서.
- 후속: 재고 리포트(전체 이력 집계)·외상 현황(거래처별 조회)은 데이터에 비례해 늘어난다 — 요약 열·일괄 계산으로 전환 예정.
