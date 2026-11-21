# ADR-0004 데이터 접근: JPA 대신 명시적 SQL(JdbcClient)

- 상태: **승인됨(2026-11-21 위임 결정 DLG-003)**
- 관련: docs/02 §1 "Spring Data/JPA + 중요한 원장/잠금 SQL은 명시적으로 관리"

## 배경
docs/02 기본안은 JPA 를 쓰되 원장·잠금 SQL 은 명시적으로 관리하는 것이다. Foundation 의 핵심은 모든 쿼리에 운영그룹·범위 조건이 빠짐없이 들어가는 것이며, 이후 재고 예약(`FOR UPDATE`/SKIP LOCKED), 원장 append-only, 배제 제약 등 명시적 SQL 비중이 높다.

## 결정
Foundation 은 `JdbcClient` 와 손으로 쓴 SQL 을 쓴다. 범위 조건이 SQL 에 그대로 보여 리뷰·테스트로 누락을 찾기 쉽고, 지연 로딩으로 범위 밖 연관 객체가 직렬화되는 위험이 없다.

## 기존 기본안과 차이(보고 대상)
JPA 를 쓰지 않은 것은 docs/02 기본안과 다르다. 상품 마스터처럼 단순 CRUD 비중이 큰 모듈(M2)에서 JPA 도입이 유리하면 해당 모듈 ADR 로 다시 판단한다.
