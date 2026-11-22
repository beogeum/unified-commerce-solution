-- M4 주문 개인정보 보관·분리보관·파기(DLG-120) + O3 주문 구성 확정(BOM 버전 고정)·사은품(GWP) 지급(docs/03 §3, T12·T13)

-- ── 개인정보 보관 ─────────────────────────────────────────────
-- 사업자별 재정의(없으면 설정 기본값: 운영 열람 90일, 법정 보존 5년)
create table order_privacy_policy (
    workspace_id        uuid not null,
    business_entity_id  uuid primary key,
    active_days         integer not null check (active_days between 30 and 3650),
    retention_years     integer not null check (retention_years between 1 and 10),
    updated_by          uuid not null,
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);

alter table sales_order
    add column closed_at timestamptz,
    add column pii_state text not null default 'ACTIVE' check (pii_state in ('ACTIVE', 'ARCHIVED', 'PURGED')),
    add column pii_archived_at timestamptz,
    add column pii_purged_at timestamptz,
    add check (pii_state <> 'PURGED' or pii_cipher is null);

-- 원본 주문: 파기 때 암호문만 지울 수 있다(나머지는 계속 불변)
alter table order_raw alter column payload_cipher drop not null, alter column payload_key_id drop not null;
alter table order_raw add column payload_purged_at timestamptz;
drop trigger order_raw_append_only on order_raw;
create function order_raw_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'order_raw is append-only' using errcode = '55000';
    end if;
    if new.payload_cipher is null and old.payload_cipher is not null and new.payload_purged_at is not null
       and (to_jsonb(new) - 'payload_cipher' - 'payload_key_id' - 'payload_purged_at') = (to_jsonb(old) - 'payload_cipher' - 'payload_key_id' - 'payload_purged_at') then
        return new;
    end if;
    raise exception 'order_raw is append-only (only personal data purge is allowed)' using errcode = '55000';
end
$$;
create trigger order_raw_guard before update or delete on order_raw for each row execute function order_raw_guard();

-- ── O3 주문 구성 확정·사은품 ───────────────────────────────────
alter table order_item
    add column is_gift boolean not null default false,
    add column gift_source text check (gift_source in ('CHANNEL', 'INTERNAL')),
    add column gwp_campaign_id uuid,
    add column channel_campaign_ref text check (channel_campaign_ref is null or length(channel_campaign_ref) <= 100),
    add check (is_gift = (gift_source is not null)),
    add check (gift_source is distinct from 'INTERNAL' or (gwp_campaign_id is not null and line_amount = 0));
create index order_item_gwp_idx on order_item (workspace_id, gwp_campaign_id) where gwp_campaign_id is not null;

alter table sales_order
    add column composition_status text not null default 'PENDING' check (composition_status in ('PENDING', 'FIXED')),
    add column composed_at timestamptz,
    add column gwp_hold boolean not null default false,
    add column gwp_result jsonb;

-- 주문상품 1단위를 출고하는 데 드는 재고 SKU 구성(생성 후 불변). 가상묶음은 주문 시각 BOM 버전으로 고정
create table order_item_component (
    id                      uuid primary key,
    workspace_id            uuid not null,
    order_id                uuid not null,
    order_item_id           uuid not null,
    component_sku_id        uuid not null,
    quantity_per_unit       integer not null check (quantity_per_unit >= 1),
    source                  text not null check (source in ('DIRECT', 'BOM', 'GIFT')),
    bom_id                  uuid,
    bom_version             integer,
    split_shipment_allowed  boolean not null default false,
    created_at              timestamptz not null default now(),
    unique (workspace_id, id),
    unique (order_item_id, component_sku_id),
    foreign key (workspace_id, order_id) references sales_order (workspace_id, id),
    foreign key (workspace_id, order_item_id) references order_item (workspace_id, id),
    foreign key (workspace_id, component_sku_id) references sku (workspace_id, id),
    check ((source = 'BOM') = (bom_id is not null and bom_version is not null))
);
create index order_item_component_order_idx on order_item_component (order_id);
create trigger order_item_component_append_only before update or delete on order_item_component for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['order_privacy_policy', 'order_item_component']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
