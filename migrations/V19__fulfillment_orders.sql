-- M4 O4 출고 배정·출고요청 (docs/04 §1·§4·§9, CHG-009, DLG-114, T11)
-- 출고지시 = (주문, 거점) 단위. 확보(I3)는 출고지시마다 하나의 수요(FULFILLMENT_ORDER, 출고지시 id)로 묶고, ALLOCATED = 출고지시 결합.

alter table fulfillment_node add column routing_priority integer not null default 100 check (routing_priority between 0 and 10000);

alter table sales_order add column allocation_status text not null default 'PENDING'
    check (allocation_status in ('PENDING', 'ALLOCATED', 'FAILED', 'NOT_REQUIRED'));

create table fulfillment_order (
    id                  uuid primary key,
    workspace_id        uuid not null,
    fo_no               text not null,
    order_id            uuid not null,
    -- 재고 소유 = 주문 사업자(교차 소유 출고 금지, DLG-034)
    business_entity_id  uuid not null,
    channel_account_id  uuid not null,
    node_id             uuid not null,
    status              text not null check (status in ('ALLOCATED', 'REQUESTED', 'ACCEPTED', 'PICKING', 'PACKING', 'SHIPPED', 'FAILED',
                                                        'CANCEL_REQUESTED', 'CANCELLED')),
    -- 물류사 전송 멱등 키(재전송·타임아웃 사후조회에 같은 값을 쓴다)
    dispatch_key        text not null,
    dispatch_mode       text check (dispatch_mode in ('INTERNAL', 'API', 'MANUAL')),
    connection_id       uuid,
    external_ref        text check (external_ref is null or length(external_ref) <= 200),
    attempts            integer not null default 0,
    last_error_code     text,
    last_error_message  text,
    requested_at        timestamptz,
    accepted_at         timestamptz,
    cancel_reason       text check (cancel_reason is null or length(cancel_reason) <= 500),
    cancelled_at        timestamptz,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, fo_no),
    unique (workspace_id, dispatch_key),
    foreign key (workspace_id, order_id) references sales_order (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id)
);
create index fulfillment_order_list_idx on fulfillment_order (workspace_id, created_at desc);
create index fulfillment_order_order_idx on fulfillment_order (order_id);

create table fulfillment_order_line (
    workspace_id    uuid not null,
    fo_id           uuid not null,
    line_no         integer not null,
    order_item_id   uuid not null,
    sku_id          uuid not null,
    quantity        integer not null check (quantity >= 1),
    reservation_id  uuid,
    primary key (fo_id, line_no),
    unique (fo_id, order_item_id, sku_id),
    foreign key (workspace_id, fo_id) references fulfillment_order (workspace_id, id),
    foreign key (workspace_id, order_item_id) references order_item (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id)
);

create table fulfillment_event (
    id              uuid primary key,
    workspace_id    uuid not null,
    fo_id           uuid not null,
    event_type      text not null check (event_type ~ '^[A-Z][A-Z0-9_]{2,60}$'),
    from_status     text,
    to_status       text,
    external_at     timestamptz,
    detail          jsonb not null default '{}',
    actor_type      text not null check (actor_type in ('USER', 'SYSTEM', 'INTEGRATION')),
    actor_member_id uuid,
    occurred_at     timestamptz not null default now(),
    foreign key (workspace_id, fo_id) references fulfillment_order (workspace_id, id)
);
create index fulfillment_event_fo_idx on fulfillment_event (fo_id, occurred_at desc);
create trigger fulfillment_event_append_only before update or delete on fulfillment_event for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['fulfillment_order', 'fulfillment_order_line', 'fulfillment_event']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
