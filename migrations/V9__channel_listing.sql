-- M2 C3 채널상품·매핑 (FR-CAT-03, docs/03 §2 '판매상품', docs/01 상품 > 채널상품·연결, docs/13 §1)
-- 채널상품 식별: 판매단위 + 외부상품ID + 외부옵션ID. 외부 ID 를 PK 로 쓰지 않는다(CLAUDE.md).
-- 매핑은 유효기간 이력으로 보존하고 겹치지 않는다. 기존 매핑의 SKU 는 바꾸지 않고 닫은 뒤 새로 연다.

create table channel_listing (
    id                    uuid primary key,
    workspace_id          uuid not null,
    channel_account_id    uuid not null,
    sales_unit_id         uuid not null,
    external_product_id   text not null check (length(external_product_id) between 1 and 100),
    -- 옵션이 없는 상품은 빈 문자열(유일성 판단에 null 을 쓰지 않기 위해)
    external_option_id    text not null default '' check (length(external_option_id) <= 100),
    external_sku_code     text check (external_sku_code is null or length(external_sku_code) <= 100),
    title                 text not null check (length(btrim(title)) between 1 and 300),
    option_text           text check (option_text is null or length(option_text) <= 300),
    barcode               text check (barcode is null or length(barcode) <= 40),
    -- 채널 표시값(참고). 정산·손익의 근거가 아니다
    channel_price         numeric(18, 0) check (channel_price is null or channel_price >= 0),
    channel_stock         integer check (channel_stock is null or channel_stock >= 0),
    sale_status           text not null default 'UNKNOWN' check (sale_status in ('ON_SALE', 'SUSPENDED', 'SOLD_OUT', 'UNKNOWN')),
    source                text not null check (source in ('MANUAL', 'FILE', 'API')),
    source_ref            text,
    last_synced_at        timestamptz,
    status                text not null default 'ACTIVE' check (status in ('ACTIVE', 'REPLACED', 'REMOVED')),
    -- 외부 ID 가 바뀐 경우: 이전 채널상품 → 새 채널상품
    replaced_by_id        uuid,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    version               integer not null default 1,
    unique (workspace_id, id),
    unique (sales_unit_id, external_product_id, external_option_id),
    foreign key (workspace_id, sales_unit_id) references sales_unit (workspace_id, id),
    foreign key (workspace_id, channel_account_id) references channel_account (workspace_id, id),
    foreign key (workspace_id, replaced_by_id) references channel_listing (workspace_id, id),
    check ((status = 'REPLACED') = (replaced_by_id is not null))
);
create index channel_listing_account_idx on channel_listing (workspace_id, channel_account_id, status);
create index channel_listing_barcode_idx on channel_listing (workspace_id, barcode) where barcode is not null;

-- 판매단위가 그 판매계정 소속인지 DB 에서 확인
create function channel_listing_guard() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from sales_unit su where su.id = new.sales_unit_id and su.channel_account_id = new.channel_account_id) then
        raise exception 'sales_unit does not belong to channel_account' using errcode = 'check_violation';
    end if;
    if tg_op = 'UPDATE' and (new.sales_unit_id <> old.sales_unit_id or new.channel_account_id <> old.channel_account_id
        or new.external_product_id <> old.external_product_id or new.external_option_id <> old.external_option_id) then
        raise exception 'channel_listing identity columns are immutable' using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger channel_listing_identity before insert or update on channel_listing for each row execute function channel_listing_guard();

create table channel_listing_mapping (
    id              uuid primary key,
    workspace_id    uuid not null,
    listing_id      uuid not null,
    sku_id          uuid not null,
    -- 채널상품 1개 = SKU n개(예: 그래놀라 ×2). 여러 SKU 구성은 묶음 SKU(C4)로 표현
    quantity        integer not null default 1 check (quantity between 1 and 1000),
    valid_from      timestamptz not null,
    valid_to        timestamptz,
    match_basis     text not null check (match_basis in ('MANUAL', 'BARCODE', 'EXTERNAL_SKU_CODE', 'NAME', 'FILE', 'ID_CHANGE')),
    reason          text check (reason is null or length(reason) <= 500),
    created_by      uuid,
    created_at      timestamptz not null default now(),
    closed_by       uuid,
    foreign key (workspace_id, listing_id) references channel_listing (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    check (valid_to is null or valid_to > valid_from),
    exclude using gist (
        listing_id with =,
        tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') with &&
    )
);
create unique index channel_listing_mapping_open_uq on channel_listing_mapping (listing_id) where valid_to is null;
create index channel_listing_mapping_sku_idx on channel_listing_mapping (workspace_id, sku_id);

-- 이력 보존: 열린 매핑을 닫는 것(valid_to·closed_by 설정)만 허용, 삭제 금지
create function channel_listing_mapping_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'channel_listing_mapping is append-only' using errcode = 'check_violation';
    end if;
    if new.listing_id <> old.listing_id or new.sku_id <> old.sku_id or new.quantity <> old.quantity or new.valid_from <> old.valid_from
       or old.valid_to is not null then
        raise exception 'channel_listing_mapping is append-only (only closing an open mapping is allowed)' using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger channel_listing_mapping_append_only before update or delete on channel_listing_mapping
    for each row execute function channel_listing_mapping_guard();

do $$
declare
    t text;
begin
    foreach t in array array['channel_listing', 'channel_listing_mapping']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
