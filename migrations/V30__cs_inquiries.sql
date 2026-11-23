-- P1 CS 문의 통합함(FR-CS-01·05, docs/01 §CS, docs/13 §8, DLG-116, DLG-201~206)
-- 채널 문의 API 는 실계정 확인 전 미연동: 담당자가 채널 문의를 옮겨 적거나 전화·메일 문의를 기록하고, 답변은 기록 후 채널에 직접 등록(등록 완료 표시).

alter table claim_number_counter drop constraint claim_number_counter_kind_check;
alter table claim_number_counter add constraint claim_number_counter_kind_check
    check (kind in ('CLAIM', 'RETURN_RECEIPT', 'WMS_WAVE', 'PURCHASE_ORDER', 'PO_RECEIPT', 'MANUAL_ORDER', 'CS_INQUIRY'));

create table cs_inquiry (
    id                    uuid primary key,
    workspace_id          uuid not null,
    inquiry_no            text not null,
    business_entity_id    uuid not null,
    channel_account_id    uuid not null,
    order_id              uuid,
    order_item_id         uuid,
    claim_id              uuid,
    source                text not null check (source in ('CHANNEL', 'PHONE', 'EMAIL', 'CHAT', 'OTHER')),
    external_inquiry_key  text check (external_inquiry_key is null or length(external_inquiry_key) <= 200),
    inquiry_type          text not null check (inquiry_type in ('DELIVERY', 'PRODUCT', 'CANCEL', 'RETURN', 'EXCHANGE', 'PAYMENT', 'ADDRESS_CHANGE', 'OTHER')),
    title                 text not null check (length(title) between 1 and 200),
    status                text not null check (status in ('OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER', 'ANSWERED', 'CLOSED')),
    priority              text not null default 'NORMAL' check (priority in ('URGENT', 'HIGH', 'NORMAL', 'LOW')),
    assignee_member_id    uuid,
    due_at                timestamptz not null,
    customer_reply        boolean not null default false,
    -- 주문 없는 문의의 고객 정보(암호화·마스킹). 주문 문의는 주문의 구매자 정보를 쓴다
    customer_cipher       bytea,
    customer_key_id       text,
    customer_name_masked  text,
    first_response_at     timestamptz,
    answered_at           timestamptz,
    closed_at             timestamptz,
    created_by            uuid,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    version               integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, inquiry_no),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, order_id) references sales_order (workspace_id, id)
);
-- 같은 판매계정의 같은 채널 문의번호는 한 번만
create unique index cs_inquiry_external_uq on cs_inquiry (channel_account_id, external_inquiry_key) where external_inquiry_key is not null;
create index cs_inquiry_queue_idx on cs_inquiry (workspace_id, status, due_at);

-- 메시지 스레드(추가 전용). 답변(OUTBOUND)은 채널 등록 상태만 바꿀 수 있다.
create table cs_message (
    id                  uuid primary key,
    workspace_id        uuid not null,
    inquiry_id          uuid not null,
    direction           text not null check (direction in ('INBOUND', 'OUTBOUND', 'NOTE', 'SYSTEM')),
    body                text not null check (length(body) between 1 and 5000),
    template_id         uuid,
    channel_status      text check (channel_status in ('MANUAL_REQUIRED', 'MANUAL_DONE')),
    channel_marked_by   uuid,
    channel_marked_at   timestamptz,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    foreign key (workspace_id, inquiry_id) references cs_inquiry (workspace_id, id),
    check ((direction = 'OUTBOUND') = (channel_status is not null))
);
create index cs_message_inquiry_idx on cs_message (inquiry_id, created_at);
create or replace function cs_message_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'cs_message is append-only' using errcode = 'P0001';
    end if;
    if new.inquiry_id <> old.inquiry_id or new.direction <> old.direction or new.body <> old.body or new.created_at <> old.created_at
       or new.created_by is distinct from old.created_by or old.channel_status <> 'MANUAL_REQUIRED' or new.channel_status <> 'MANUAL_DONE' then
        raise exception 'cs_message content is immutable' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger cs_message_guard before update or delete on cs_message for each row execute function cs_message_guard();

-- 답변 템플릿(사업자 공통 또는 사업자별). 변수: {고객명} {주문번호} {상품} {송장번호}
create table cs_template (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid,
    name                text not null check (length(name) between 1 and 100),
    inquiry_type        text check (inquiry_type is null or inquiry_type in ('DELIVERY', 'PRODUCT', 'CANCEL', 'RETURN', 'EXCHANGE', 'PAYMENT', 'ADDRESS_CHANGE', 'OTHER')),
    body                text not null check (length(body) between 1 and 5000),
    status              text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_by          uuid,
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id)
);

do $$
declare
    t text;
begin
    foreach t in array array['cs_inquiry', 'cs_message', 'cs_template']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
