-- M4 O1~O2 주문 수집·중복 판단·표준화·귀속 스냅샷·상품 연결·검증·보류 (docs/04 §1~3, DLG-112~119, T01·T10)
-- 원본(개인정보 포함)은 암호화 보관 + 마스킹 사본. 주문 식별 = 판매계정 + 판매단위 + 원천유형 + 외부주문키.

-- 연동 수집 커서·워터마크(연결 × 작업 유형)
create table integration_sync_state (
    workspace_id    uuid not null,
    connection_id   uuid not null,
    job_type        text not null,
    cursor          text,
    watermark       timestamptz,
    updated_at      timestamptz not null default now(),
    primary key (connection_id, job_type),
    foreign key (workspace_id, connection_id) references integration_connection (workspace_id, id)
);

-- 내부 주문번호 일련번호(운영그룹·일 단위)
create table order_number_counter (
    workspace_id    uuid not null references workspace (id),
    day             date not null,
    last_value      integer not null,
    primary key (workspace_id, day)
);

-- 원본 주문(append-only). 같은 주문의 같은 내용은 한 번만 저장
create table order_raw (
    id                  uuid primary key,
    workspace_id        uuid not null,
    channel_account_id  uuid not null,
    sales_unit_id       uuid,
    connection_id       uuid,
    job_id              uuid,
    source              text not null check (source in ('API', 'FILE', 'MANUAL')),
    source_type         text not null check (source_type in ('ORDER', 'PLATFORM_FULFILLMENT', 'MANUAL')),
    external_order_key  text not null check (length(external_order_key) between 1 and 200),
    payload_hash        text not null,
    payload_cipher      bytea not null,
    payload_key_id      text not null,
    payload_masked      jsonb not null,
    mapping_version     text not null,
    external_updated_at timestamptz,
    received_at         timestamptz not null default now(),
    result              text not null check (result in ('CREATED', 'UPDATED', 'IGNORED_STALE', 'REJECTED')),
    result_detail       text,
    order_id            uuid,
    unique (workspace_id, id),
    unique (channel_account_id, source_type, external_order_key, payload_hash),
    foreign key (workspace_id, channel_account_id) references channel_account (workspace_id, id),
    foreign key (workspace_id, sales_unit_id) references sales_unit (workspace_id, id)
);
create index order_raw_order_idx on order_raw (order_id, received_at desc);
create trigger order_raw_append_only before update or delete on order_raw for each row execute function forbid_mutation();

create table sales_order (
    id                  uuid primary key,
    workspace_id        uuid not null,
    order_no            text not null,
    -- 주문 시각 귀속 스냅샷(DLG-118). 이후 판매계정 귀속이 바뀌어도 재계산하지 않는다
    business_entity_id  uuid not null,
    channel_account_id  uuid not null,
    sales_unit_id       uuid not null,
    source_type         text not null check (source_type in ('ORDER', 'PLATFORM_FULFILLMENT', 'MANUAL')),
    external_order_key  text not null check (length(external_order_key) between 1 and 200),
    ordered_at          timestamptz not null,
    paid_at             timestamptz,
    status              text not null check (status in ('RECEIVED', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'CANCELLED')),
    payment_status      text not null check (payment_status in ('PENDING', 'PAID', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED')),
    external_status     text,
    external_updated_at timestamptz,
    currency            char(3) not null default 'KRW' check (currency = 'KRW'),
    -- 금액: 부가세 포함, 원 단위
    items_amount        numeric(18, 0) not null check (items_amount >= 0),
    seller_discount     numeric(18, 0) not null default 0 check (seller_discount >= 0),
    channel_discount    numeric(18, 0) not null default 0 check (channel_discount >= 0),
    shipping_fee        numeric(18, 0) not null default 0 check (shipping_fee >= 0),
    total_amount        numeric(18, 0) not null check (total_amount >= 0),
    -- 개인정보: 마스킹 표시값 + 암호화 원문(수령인·연락처·주소·메모, 구매자)
    buyer_name_masked     text,
    recipient_name_masked text,
    recipient_phone_masked text,
    address_masked        text,
    pii_cipher          bytea,
    pii_key_id          text,
    on_hold             boolean not null default false,
    hold_reasons        text[] not null default '{}',
    hold_note           text check (hold_note is null or length(hold_note) <= 500),
    held_by             uuid,
    held_at             timestamptz,
    last_raw_id         uuid,
    mapping_version     text not null,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, order_no),
    unique (channel_account_id, sales_unit_id, source_type, external_order_key),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, channel_account_id) references channel_account (workspace_id, id),
    foreign key (workspace_id, sales_unit_id) references sales_unit (workspace_id, id),
    check (on_hold = (cardinality(hold_reasons) > 0))
);
create index sales_order_list_idx on sales_order (workspace_id, ordered_at desc);
create index sales_order_scope_idx on sales_order (workspace_id, business_entity_id, channel_account_id);

-- 귀속 스냅샷·판매단위 소속 검증, 식별·귀속 필드 불변(REV-012)
create function sales_order_guard() returns trigger language plpgsql as $$
declare
    v_be uuid;
    v_ca uuid;
begin
    if tg_op = 'INSERT' then
        v_be := channel_account_business_at(new.channel_account_id, new.ordered_at);
        if v_be is null or v_be <> new.business_entity_id then
            raise exception 'sales_order business_entity must equal channel account attribution at ordered_at' using errcode = '23514';
        end if;
        select channel_account_id into v_ca from sales_unit where id = new.sales_unit_id;
        if v_ca is distinct from new.channel_account_id then
            raise exception 'sales_order sales_unit must belong to channel_account' using errcode = '23514';
        end if;
    else
        if new.business_entity_id <> old.business_entity_id or new.channel_account_id <> old.channel_account_id or new.sales_unit_id <> old.sales_unit_id
           or new.source_type <> old.source_type or new.external_order_key <> old.external_order_key or new.ordered_at <> old.ordered_at
           or new.order_no <> old.order_no or new.workspace_id <> old.workspace_id then
            raise exception 'sales_order identity and attribution are immutable' using errcode = '23514';
        end if;
    end if;
    return new;
end
$$;
create trigger sales_order_guard before insert or update on sales_order for each row execute function sales_order_guard();

create table order_item (
    id                  uuid primary key,
    workspace_id        uuid not null,
    order_id            uuid not null,
    line_no             integer not null check (line_no >= 1),
    external_item_key   text not null check (length(external_item_key) between 1 and 200),
    external_product_id text not null,
    external_option_id  text not null default '',
    product_name        text not null,
    option_name         text,
    listing_id          uuid,
    mapping_id          uuid,
    sku_id              uuid,
    -- 채널상품 1개당 SKU 수량(채널상품 매핑 C3). 출고 수량 = 주문 수량 × 이 값
    sku_qty_per_unit    integer check (sku_qty_per_unit is null or sku_qty_per_unit >= 1),
    mapping_status      text not null check (mapping_status in ('MAPPED', 'UNMAPPED')),
    original_qty        integer not null check (original_qty >= 1),
    canceled_qty        integer not null default 0,
    shipped_qty         integer not null default 0,
    returned_qty        integer not null default 0,
    unit_price          numeric(18, 0) not null check (unit_price >= 0),
    line_amount         numeric(18, 0) not null check (line_amount >= 0),
    seller_discount     numeric(18, 0) not null default 0 check (seller_discount >= 0),
    channel_discount    numeric(18, 0) not null default 0 check (channel_discount >= 0),
    tax_type            text check (tax_type in ('TAXABLE', 'TAX_FREE', 'ZERO_RATED')),
    external_status     text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (order_id, external_item_key),
    unique (order_id, line_no),
    foreign key (workspace_id, order_id) references sales_order (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    -- 주문상품 수량 불변조건(docs/04 §3)
    check (canceled_qty between 0 and original_qty),
    check (shipped_qty between 0 and original_qty - canceled_qty),
    check (returned_qty between 0 and shipped_qty),
    check ((mapping_status = 'MAPPED') = (sku_id is not null and sku_qty_per_unit is not null))
);
create index order_item_listing_idx on order_item (workspace_id, listing_id) where mapping_status = 'UNMAPPED';
create index order_item_unmapped_idx on order_item (workspace_id, mapping_status) where mapping_status = 'UNMAPPED';

-- 주문 변경 이력(append-only)
create table order_event (
    id              uuid primary key,
    workspace_id    uuid not null,
    order_id        uuid not null,
    order_item_id   uuid,
    event_type      text not null check (event_type ~ '^[A-Z][A-Z0-9_]{2,60}$'),
    from_value      text,
    to_value        text,
    detail          jsonb not null default '{}',
    raw_id          uuid,
    actor_type      text not null check (actor_type in ('USER', 'SYSTEM', 'INTEGRATION')),
    actor_member_id uuid,
    occurred_at     timestamptz not null default now(),
    foreign key (workspace_id, order_id) references sales_order (workspace_id, id)
);
create index order_event_order_idx on order_event (order_id, occurred_at desc);
create trigger order_event_append_only before update or delete on order_event for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['integration_sync_state', 'order_number_counter', 'order_raw', 'sales_order', 'order_item', 'order_event']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
