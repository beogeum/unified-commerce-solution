-- M2 C1 상품·옵션·SKU (FR-CAT-01·02·08, docs/03 §3, docs/13 §1)
-- 상품·SKU 는 운영그룹 공통 마스터. 사업자별 원가·가격·재고정책은 C2(business_sku_policy)에서 분리한다(CONF-004).
-- 외부 SKU·상품번호는 여기서 식별자로 쓰지 않는다(채널상품 매핑은 C3).

insert into permission (code, category, name_ko, sensitive) values
 ('catalog.read',   'CATALOG', '상품·SKU 조회', false),
 ('catalog.manage', 'CATALOG', '상품·SKU·브랜드 등록·변경', false);

-- 기존 운영그룹의 시스템 역할에도 반영(새 운영그룹은 SystemRoles 템플릿이 부여)
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'catalog.read' from role r where r.is_system
on conflict do nothing;
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'catalog.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'OPS_ADMIN', 'MD')
on conflict do nothing;

create table brand (
    id            uuid primary key,
    workspace_id  uuid not null references workspace (id),
    name          text not null check (length(btrim(name)) between 1 and 100),
    status        text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    version       integer not null default 1,
    unique (workspace_id, id)
);
create unique index brand_name_uq on brand (workspace_id, lower(btrim(name)));

create table category (
    id            uuid primary key,
    workspace_id  uuid not null references workspace (id),
    parent_id     uuid,
    name          text not null check (length(btrim(name)) between 1 and 100),
    sort_order    integer not null default 0,
    status        text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at    timestamptz not null default now(),
    version       integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, parent_id) references category (workspace_id, id),
    check (parent_id is null or parent_id <> id)
);
create unique index category_name_uq on category (workspace_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'), lower(btrim(name)));

create table product (
    id              uuid primary key,
    workspace_id    uuid not null references workspace (id),
    product_code    text not null check (product_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    name            text not null check (length(btrim(name)) between 1 and 200),
    internal_name   text check (internal_name is null or length(internal_name) <= 200),
    brand_id        uuid,
    category_id     uuid,
    -- 과세유형: 과세 / 면세 / 영세율
    tax_type        text check (tax_type in ('TAXABLE', 'TAX_FREE', 'ZERO_RATED')),
    status          text not null default 'DRAFT' check (status in ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    manufacturer    text check (manufacturer is null or length(manufacturer) <= 100),
    origin_country  text check (origin_country is null or length(origin_country) <= 60),
    description     text check (description is null or length(description) <= 20000),
    tags            text[] not null default '{}',
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    version         integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, brand_id) references brand (workspace_id, id),
    foreign key (workspace_id, category_id) references category (workspace_id, id),
    -- 게시(판매중) 필수: 브랜드·카테고리·과세유형. 초안은 불완전 허용(docs/13 §1)
    check (status <> 'ACTIVE' or (brand_id is not null and category_id is not null and tax_type is not null))
);
create unique index product_code_uq on product (workspace_id, lower(product_code));
create index product_list_idx on product (workspace_id, status, id desc);

create table product_option (
    id            uuid primary key,
    workspace_id  uuid not null,
    product_id    uuid not null,
    name          text not null check (length(btrim(name)) between 1 and 50),
    sort_order    integer not null default 0,
    created_at    timestamptz not null default now(),
    unique (workspace_id, id),
    unique (workspace_id, product_id, id),
    foreign key (workspace_id, product_id) references product (workspace_id, id)
);
create unique index product_option_name_uq on product_option (product_id, lower(btrim(name)));

create table product_option_value (
    id            uuid primary key,
    workspace_id  uuid not null,
    option_id     uuid not null,
    value         text not null check (length(btrim(value)) between 1 and 100),
    sort_order    integer not null default 0,
    created_at    timestamptz not null default now(),
    unique (workspace_id, id),
    unique (workspace_id, option_id, id),
    foreign key (workspace_id, option_id) references product_option (workspace_id, id)
);
create unique index product_option_value_uq on product_option_value (option_id, lower(btrim(value)));

create table sku (
    id                       uuid primary key,
    workspace_id             uuid not null,
    product_id               uuid not null,
    sku_code                 text not null check (sku_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    name                     text check (name is null or length(name) <= 200),
    sku_type                 text not null check (sku_type in ('NORMAL', 'BUNDLE', 'KIT', 'MATERIAL', 'GIFT', 'SAMPLE', 'SERVICE', 'DIGITAL')),
    sellable                 boolean not null,
    purchasable              boolean not null,
    inventory_managed        boolean not null,
    barcode                  text check (barcode is null or barcode ~ '^[0-9A-Za-z-]{4,40}$'),
    weight_g                 numeric(12, 3) check (weight_g is null or weight_g >= 0),
    length_mm                numeric(10, 1) check (length_mm is null or length_mm >= 0),
    width_mm                 numeric(10, 1) check (width_mm is null or width_mm >= 0),
    height_mm                numeric(10, 1) check (height_mm is null or height_mm >= 0),
    lot_managed              boolean not null default false,
    expiry_managed           boolean not null default false,
    min_remaining_shelf_days integer check (min_remaining_shelf_days is null or min_remaining_shelf_days >= 0),
    -- 같은 상품 안의 옵션값 조합(정렬된 옵션값 ID). 옵션 없는 상품은 null
    option_signature         text,
    status                   text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    version                  integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, product_id) references product (workspace_id, id),
    -- 가상묶음·서비스·디지털은 자체 재고가 없다(가상묶음은 부품 재고로 계산, docs/03 §3)
    check (sku_type not in ('BUNDLE', 'SERVICE', 'DIGITAL') or not inventory_managed),
    -- 실물 세트·부자재는 재고관리 대상
    check (sku_type not in ('KIT', 'MATERIAL') or inventory_managed),
    -- 부자재는 고객에게 판매하지 않는다
    check (sku_type <> 'MATERIAL' or not sellable),
    check (not expiry_managed or lot_managed),
    check (not lot_managed or inventory_managed),
    check (min_remaining_shelf_days is null or expiry_managed)
);
create unique index sku_code_uq on sku (workspace_id, lower(sku_code));
create unique index sku_option_combination_uq on sku (workspace_id, product_id, option_signature)
    where option_signature is not null and status <> 'ARCHIVED';
create index sku_product_idx on sku (workspace_id, product_id);
create index sku_barcode_idx on sku (workspace_id, barcode) where barcode is not null;
create index sku_list_idx on sku (workspace_id, status, id desc);

create table sku_option_value (
    workspace_id     uuid not null,
    sku_id           uuid not null,
    option_id        uuid not null,
    option_value_id  uuid not null,
    primary key (sku_id, option_id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    foreign key (workspace_id, option_id, option_value_id) references product_option_value (workspace_id, option_id, id)
);

-- 식별 열 불변: 코드·상품 소속은 등록 후 바꿀 수 없다(다르면 새로 등록, DEC-F18 과 같은 원칙)
create function catalog_guard_identity() returns trigger language plpgsql as $$
begin
    if tg_table_name = 'product' then
        if new.product_code <> old.product_code or new.workspace_id <> old.workspace_id then
            raise exception 'product identity columns are immutable' using errcode = 'check_violation';
        end if;
    elsif tg_table_name = 'sku' then
        if new.sku_code <> old.sku_code or new.product_id <> old.product_id or new.workspace_id <> old.workspace_id then
            raise exception 'sku identity columns are immutable' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end $$;
create trigger product_identity_immutable before update on product for each row execute function catalog_guard_identity();
create trigger sku_identity_immutable before update on sku for each row execute function catalog_guard_identity();

-- RLS(F8 V6 와 같은 정책). 새 운영그룹 테이블은 반드시 여기서 정책을 건다.
do $$
declare
    t text;
begin
    foreach t in array array['brand', 'category', 'product', 'product_option', 'product_option_value', 'sku', 'sku_option_value']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
