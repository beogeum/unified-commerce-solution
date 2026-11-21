-- M2 C4 BOM(묶음·세트·조립·포장·프로모션·제조) (FR-CAT-05·06, docs/03 §3, docs/13 §2)
-- 버전·적용기간 관리, 게시 후 불변, 같은 대상·유형의 게시 버전 기간은 겹치지 않는다. 순환 검사는 애플리케이션(그래프 잠금 하에)에서 한다.

create table bom (
    id                       uuid primary key,
    workspace_id             uuid not null,
    bom_type                 text not null check (bom_type in ('SALES_BUNDLE', 'ASSEMBLY', 'PACKAGING', 'PROMOTION', 'MANUFACTURING')),
    -- 포장 BOM 은 SKU 가 아니라 포장 프로필(예: DEFAULT)에 붙는다. 그 외는 상위 SKU 필수
    parent_sku_id            uuid,
    packaging_profile_code   text check (packaging_profile_code is null or packaging_profile_code ~ '^[A-Z0-9_]{1,40}$'),
    version                  integer not null check (version >= 1),
    status                   text not null default 'DRAFT' check (status in ('DRAFT', 'ACTIVE', 'RETIRED')),
    valid_from               timestamptz,
    valid_to                 timestamptz,
    -- 가상묶음 부품을 여러 거점에서 나눠 출고할 수 있는지(기본 불가, docs/13 §2)
    split_shipment_allowed   boolean not null default false,
    note                     text check (note is null or length(note) <= 500),
    created_by               uuid,
    created_at               timestamptz not null default now(),
    published_by             uuid,
    published_at             timestamptz,
    version_lock             integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, parent_sku_id) references sku (workspace_id, id),
    check ((bom_type = 'PACKAGING') = (packaging_profile_code is not null)),
    check ((bom_type = 'PACKAGING') = (parent_sku_id is null)),
    check (status = 'DRAFT' or valid_from is not null),
    check (valid_to is null or valid_from is null or valid_to > valid_from),
    exclude using gist (
        workspace_id with =,
        (coalesce(parent_sku_id::text, packaging_profile_code)) with =,
        bom_type with =,
        tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') with &&
    ) where (status <> 'DRAFT')
);
create unique index bom_version_uq on bom (workspace_id, bom_type, coalesce(parent_sku_id::text, packaging_profile_code), version);
create index bom_parent_idx on bom (workspace_id, parent_sku_id);

create table bom_component (
    workspace_id      uuid not null,
    bom_id            uuid not null,
    component_sku_id  uuid not null,
    quantity          integer not null check (quantity between 1 and 10000),
    sort_order        integer not null default 0,
    primary key (bom_id, component_sku_id),
    foreign key (workspace_id, bom_id) references bom (workspace_id, id),
    foreign key (workspace_id, component_sku_id) references sku (workspace_id, id)
);
create index bom_component_sku_idx on bom_component (workspace_id, component_sku_id);

-- 게시된 BOM 은 구성·대상·유형·버전을 바꿀 수 없다(과거 주문 스냅샷 보호). 상태 진행과 종료 시각만 허용
create function bom_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        if old.status <> 'DRAFT' then
            raise exception 'published bom is immutable' using errcode = 'check_violation';
        end if;
        return old;
    end if;
    if old.status <> 'DRAFT' and (new.bom_type <> old.bom_type or new.parent_sku_id is distinct from old.parent_sku_id
        or new.packaging_profile_code is distinct from old.packaging_profile_code or new.version <> old.version
        or new.valid_from <> old.valid_from or new.split_shipment_allowed <> old.split_shipment_allowed
        or (old.valid_to is not null and new.valid_to is distinct from old.valid_to)
        or (old.status = 'RETIRED' and new.status <> 'RETIRED') or new.status = 'DRAFT') then
        raise exception 'published bom is immutable' using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger bom_immutable before update or delete on bom for each row execute function bom_guard();

create function bom_component_guard() returns trigger language plpgsql as $$
declare
    st text;
begin
    select status into st from bom where id = coalesce(new.bom_id, old.bom_id);
    if st <> 'DRAFT' then
        raise exception 'published bom components are immutable' using errcode = 'check_violation';
    end if;
    return coalesce(new, old);
end $$;
create trigger bom_component_immutable before insert or update or delete on bom_component
    for each row execute function bom_component_guard();

do $$
declare
    t text;
begin
    foreach t in array array['bom', 'bom_component']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
