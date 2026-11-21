-- M2 C5 GWP(사은품) 캠페인 (FR-CAT-07, docs/13 §3, docs/03 §3, T13)
-- 판정 엔진은 애플리케이션(GwpEvaluator). 주문별 지급 기록(gwp_award)과 선착순 차감은 M4 주문에서 추가한다.

create table gwp_campaign (
    id                      uuid primary key,
    workspace_id            uuid not null,
    business_entity_id      uuid not null,
    name                    text not null check (length(btrim(name)) between 1 and 100),
    starts_at               timestamptz not null,
    ends_at                 timestamptz not null,
    status                  text not null default 'DRAFT' check (status in ('DRAFT', 'ACTIVE', 'PAUSED', 'ENDED')),
    priority                integer not null default 100 check (priority between 0 and 10000),
    stack_group             text check (stack_group is null or stack_group ~ '^[A-Za-z0-9_-]{1,40}$'),
    -- 같은 중복 그룹 안에서: 누적 / 그룹당 하나(우선순위 높은 것) / 최고 우선순위 하나만(전체)
    stack_policy            text not null default 'ONE_PER_GROUP' check (stack_policy in ('CUMULATIVE', 'ONE_PER_GROUP', 'HIGHEST_PRIORITY_ONLY')),
    min_order_amount        numeric(18, 0) check (min_order_amount is null or min_order_amount >= 0),
    required_brand_id       uuid,
    -- 조건 결합: 기본 AND(docs/13 §3)
    condition_mode          text not null default 'ALL' check (condition_mode in ('ALL', 'ANY')),
    max_per_order           integer not null default 1 check (max_per_order between 1 and 100),
    total_limit             integer check (total_limit is null or total_limit >= 1),
    out_of_stock_policy     text not null default 'SUBSTITUTE' check (out_of_stock_policy in ('STOP', 'SUBSTITUTE', 'PROCEED_WITHOUT', 'HOLD_ORDER')),
    return_policy           text not null default 'REEVALUATE' check (return_policy in ('REEVALUATE', 'NO_RECOVERY')),
    -- 채널 자체 사은품과 겹칠 때: 기본 내부 지급 생략(중복 지급 금지, T13)
    channel_overlap_policy  text not null default 'SKIP_INTERNAL' check (channel_overlap_policy in ('SKIP_INTERNAL', 'ALLOW_BOTH')),
    channel_campaign_ref    text check (channel_campaign_ref is null or length(channel_campaign_ref) <= 100),
    note                    text check (note is null or length(note) <= 500),
    created_by              uuid,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    version                 integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, required_brand_id) references brand (workspace_id, id),
    check (ends_at > starts_at)
);
create index gwp_campaign_lookup_idx on gwp_campaign (workspace_id, business_entity_id, status, starts_at, ends_at);

-- 대상 판매계정(없으면 사업자의 모든 판매계정)
create table gwp_campaign_account (
    workspace_id        uuid not null,
    campaign_id         uuid not null,
    channel_account_id  uuid not null,
    primary key (campaign_id, channel_account_id),
    foreign key (workspace_id, campaign_id) references gwp_campaign (workspace_id, id),
    foreign key (workspace_id, channel_account_id) references channel_account (workspace_id, id)
);

-- 조건 SKU(최소 수량)·제외 SKU
create table gwp_campaign_sku (
    workspace_id   uuid not null,
    campaign_id    uuid not null,
    sku_id         uuid not null,
    role           text not null check (role in ('REQUIRED', 'EXCLUDED')),
    min_quantity   integer not null default 1 check (min_quantity >= 1),
    primary key (campaign_id, sku_id, role),
    foreign key (workspace_id, campaign_id) references gwp_campaign (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id)
);

-- 사은품: priority 0 = 기본, 1.. = 대체 순서
create table gwp_reward (
    workspace_id   uuid not null,
    campaign_id    uuid not null,
    gift_sku_id    uuid not null,
    quantity       integer not null default 1 check (quantity between 1 and 100),
    priority       integer not null check (priority between 0 and 20),
    primary key (campaign_id, gift_sku_id),
    unique (campaign_id, priority),
    foreign key (workspace_id, campaign_id) references gwp_campaign (workspace_id, id),
    foreign key (workspace_id, gift_sku_id) references sku (workspace_id, id)
);

do $$
declare
    t text;
begin
    foreach t in array array['gwp_campaign', 'gwp_campaign_account', 'gwp_campaign_sku', 'gwp_reward']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
