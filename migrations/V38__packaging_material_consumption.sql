-- 포장 부자재 출고 차감(FR-CAT-06·CHG-011·DLG-073 → DLG-246~249). 자체 원장 거점에 포장 프로필을 지정하면
-- 출고 확정 때 그 시각 유효 포장 BOM(버전 고정) × 박스 수만큼 부자재 재고를 차감하고, 부자재 원가는 이 차감 기록에서만 잡는다(MATERIAL 규칙과 중복 없음).
alter table fulfillment_node add column packaging_profile_code text check (packaging_profile_code is null or packaging_profile_code ~ '^[A-Z0-9_]{1,40}$');

create table fo_material_consumption (
    id                  uuid primary key,
    workspace_id        uuid not null,
    fo_id               uuid not null,
    business_entity_id  uuid not null,
    node_id             uuid not null,
    bom_id              uuid not null,
    bom_version         integer not null,
    sku_id              uuid not null,
    parcels             integer not null check (parcels >= 1),
    required_qty        integer not null check (required_qty >= 1),
    consumed_qty        integer not null check (consumed_qty >= 0),
    shortfall_qty       integer not null check (shortfall_qty >= 0),
    unit_cost           numeric(18, 4),
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    unique (fo_id, sku_id),
    foreign key (workspace_id, fo_id) references fulfillment_order (workspace_id, id),
    foreign key (workspace_id, bom_id) references bom (workspace_id, id),
    check (consumed_qty + shortfall_qty = required_qty)
);
create trigger fo_material_consumption_append_only before update or delete on fo_material_consumption for each row execute function forbid_mutation();
alter table fo_material_consumption enable row level security;
create policy workspace_isolation on fo_material_consumption using (app_rls_bypass() or workspace_id = app_current_workspace())
    with check (app_rls_bypass() or workspace_id = app_current_workspace());
