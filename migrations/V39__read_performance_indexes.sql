-- 대량 데이터 조회 성능(2026-11-24 점검, DLG-251): 손익 원장 최신순 목록, 주문 부분 문자열 검색.
create index financial_entry_recent_idx on financial_entry (workspace_id, business_entity_id, created_at desc, id);

-- 부분 문자열 검색(ilike '%…%')용 trigram 색인. pg_trgm 은 신뢰 확장(PostgreSQL 13+)이라 DB 소유자가 만들 수 있다.
create extension if not exists pg_trgm;
create index sales_order_order_no_trgm on sales_order using gin (order_no gin_trgm_ops);
create index sales_order_external_key_trgm on sales_order using gin (external_order_key gin_trgm_ops);
create index order_item_product_name_trgm on order_item using gin (product_name gin_trgm_ops);

-- RLS 정책이 걸린 표에서는 ilike(누설 방지 아님 연산자)가 정책 조건보다 먼저 평가될 수 없어 trigram 색인을 쓰지 못하고 전체를 훑는다.
-- 주문 검색 ID 찾기만 표 소유자 권한 함수로 실행하되, 운영그룹은 매개변수가 아니라 세션 문맥(app_current_workspace)에서만 읽는다 → 다른 운영그룹 접근 불가.
-- 반환은 주문 ID 뿐이고, 목록 조회(사업자·판매계정 범위 포함)는 호출 측이 RLS 아래에서 다시 한다.
create or replace function app_search_order_ids(pattern text, max_rows integer) returns setof uuid
    language sql stable security definer set search_path = public, pg_temp
as $$
    select ids.id from (
        select x.id from sales_order x where x.workspace_id = app_current_workspace() and x.order_no ilike pattern
        union select x.id from sales_order x where x.workspace_id = app_current_workspace() and x.external_order_key ilike pattern
        union select i.order_id from order_item i where i.workspace_id = app_current_workspace() and i.product_name ilike pattern
    ) ids
    where app_current_workspace() is not null
    limit greatest(1, least(max_rows, 10001))
$$;
