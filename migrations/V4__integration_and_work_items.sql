-- =====================================================================
-- V4: 연동 연결·자격증명·지원 기능 + 업무함 기본
-- docs/06 §1 §5 §6, FR-ORG-04, FR-OPS-01/02, DEC-F08/F09
-- =====================================================================

-- 지원 기능 카탈로그(전역). 대상 유형별 기능 목록이며 '지원한다'는 뜻이 아니다.
create table capability_feature (
    code         text primary key check (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    target_type  text not null check (target_type in ('CHANNEL_ACCOUNT', 'SHIPPER_ACCOUNT')),
    name_ko      text not null,
    sort_order   integer not null
);
insert into capability_feature (code, target_type, name_ko, sort_order) values
 ('PRODUCT',              'CHANNEL_ACCOUNT', '상품',             10),
 ('INVENTORY_PUSH',       'CHANNEL_ACCOUNT', '채널 재고 전송',   20),
 ('ORDER',                'CHANNEL_ACCOUNT', '주문',             30),
 ('SHIPMENT_DISPATCH',    'CHANNEL_ACCOUNT', '발송처리·송장 전송', 40),
 ('CLAIM_CANCEL',         'CHANNEL_ACCOUNT', '취소 클레임',      50),
 ('CLAIM_RETURN',         'CHANNEL_ACCOUNT', '반품 클레임',      60),
 ('CLAIM_EXCHANGE',       'CHANNEL_ACCOUNT', '교환 클레임',      70),
 ('INQUIRY',              'CHANNEL_ACCOUNT', '문의',             80),
 ('SETTLEMENT',           'CHANNEL_ACCOUNT', '정산',             90),
 ('INVENTORY_QUERY',      'SHIPPER_ACCOUNT', '재고 조회',        110),
 ('INVENTORY_SEMANTIC',   'SHIPPER_ACCOUNT', '재고 원본 의미',   120),
 ('INBOUND_REQUEST',      'SHIPPER_ACCOUNT', '입고요청',         130),
 ('INBOUND_STATUS',       'SHIPPER_ACCOUNT', '입고상태',         140),
 ('OUTBOUND_REQUEST',     'SHIPPER_ACCOUNT', '출고요청',         150),
 ('OUTBOUND_CANCEL',      'SHIPPER_ACCOUNT', '출고취소',         160),
 ('OUTBOUND_STATUS',      'SHIPPER_ACCOUNT', '출고상태',         170),
 ('TRACKING',             'SHIPPER_ACCOUNT', '송장',             180),
 ('RETURN_REQUEST',       'SHIPPER_ACCOUNT', '반품요청',         190),
 ('RETURN_INSPECTION',    'SHIPPER_ACCOUNT', '반품검수 결과',    200),
 ('LOGISTICS_BILLING',    'SHIPPER_ACCOUNT', '물류비/청구',      210),
 ('INVENTORY_ADJUSTMENT', 'SHIPPER_ACCOUNT', '재고조정',         220),
 ('LOT_EXPIRY',           'SHIPPER_ACCOUNT', 'LOT·유통기한',     230),
 ('FILE_EXCHANGE',        'SHIPPER_ACCOUNT', '파일출력·수신',    240),
 ('WEBHOOK',              'SHIPPER_ACCOUNT', 'Webhook',          250);

create table integration_connection (
    id                            uuid primary key,
    workspace_id                  uuid not null references workspace (id),
    target_type                   text not null check (target_type in ('CHANNEL_ACCOUNT', 'SHIPPER_ACCOUNT')),
    channel_account_id            uuid,
    shipper_account_id            uuid,
    connector_code                text not null,
    is_mock                       boolean not null,
    display_name                  text not null check (length(display_name) between 1 and 100),
    api_version                   text,
    auth_type                     text not null check (auth_type in ('API_KEY', 'OAUTH2', 'HMAC_SIGNATURE', 'FILE_ONLY', 'MANUAL')),
    status                        text not null default 'PENDING_SETUP'
                                  check (status in ('PENDING_SETUP', 'ACTIVE', 'DELAYED', 'ERROR', 'AUTH_EXPIRED', 'PAUSED')),
    developer_registration_status text not null default 'UNKNOWN'
                                  check (developer_registration_status in ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED', 'UNKNOWN')),
    credential_expires_at         timestamptz,
    rate_limit_note               text,
    last_tested_at                timestamptz,
    last_success_at               timestamptz,
    last_error_code               text,
    last_error_at                 timestamptz,
    consecutive_failures          integer not null default 0,
    created_at                    timestamptz not null default now(),
    updated_at                    timestamptz not null default now(),
    version                       integer not null default 1,
    unique (workspace_id, id),
    check ((target_type = 'CHANNEL_ACCOUNT' and channel_account_id is not null and shipper_account_id is null)
        or (target_type = 'SHIPPER_ACCOUNT' and shipper_account_id is not null and channel_account_id is null)),
    foreign key (workspace_id, channel_account_id) references channel_account (workspace_id, id),
    foreign key (workspace_id, shipper_account_id) references shipper_account (workspace_id, id)
);
create index integration_connection_ca_idx on integration_connection (channel_account_id);
create index integration_connection_sa_idx on integration_connection (shipper_account_id);

-- 자격증명: 암호문만 저장. 응답·로그로 내보내지 않는다(docs/08 §5)
create table integration_credential (
    id              uuid primary key,
    workspace_id    uuid not null,
    connection_id   uuid not null,
    secret_cipher   bytea not null,
    key_id          text not null,
    fingerprint     text not null,
    status          text not null check (status in ('ACTIVE', 'ROTATED', 'REVOKED')),
    expires_at      timestamptz,
    created_at      timestamptz not null default now(),
    created_by      uuid,
    rotated_at      timestamptz,
    foreign key (workspace_id, connection_id) references integration_connection (workspace_id, id)
);
create unique index integration_credential_one_active on integration_credential (connection_id) where status = 'ACTIVE';

-- 연결별 지원 기능 (docs/06 §1). 기본 UNVERIFIED/NOT_VERIFIED
create table integration_capability (
    workspace_id          uuid not null,
    connection_id         uuid not null,
    feature_code          text not null references capability_feature (code),
    operation             text not null check (operation in ('READ', 'WRITE', 'DELETE')),
    support_level         text not null default 'UNVERIFIED'
                          check (support_level in ('OFFICIAL_DOCUMENTED', 'PERMISSION_REQUIRED', 'PARTIAL', 'FILE', 'MANUAL', 'UNVERIFIED', 'UNSUPPORTED')),
    account_verification  text not null default 'NOT_VERIFIED' check (account_verification in ('NOT_VERIFIED', 'VERIFIED', 'DENIED')),
    evidence_ref          text,
    evidence_file_id      uuid,
    note                  text,
    verified_at           timestamptz,
    verified_by           uuid,
    capability_version    integer not null default 1,
    updated_at            timestamptz not null default now(),
    primary key (connection_id, feature_code, operation),
    foreign key (workspace_id, connection_id) references integration_connection (workspace_id, id),
    foreign key (workspace_id, evidence_file_id) references stored_file (workspace_id, id),
    check (account_verification <> 'VERIFIED' or (verified_at is not null and verified_by is not null))
);

-- 업무함 기본 (FR-OPS-01/02). 같은 근본 원인은 dedupe_key 로 하나의 열린 항목에 묶는다
create table work_item (
    id                     uuid primary key,
    workspace_id           uuid not null references workspace (id),
    business_entity_id     uuid,
    channel_account_id     uuid,
    connection_id          uuid,
    item_type              text not null,
    severity               text not null check (severity in ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    status                 text not null default 'OPEN' check (status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD', 'DONE', 'IGNORED')),
    title_ko               text not null,
    cause_ko               text,
    recommended_action_ko  text,
    dedupe_key             text not null,
    occurrence_count       integer not null default 1 check (occurrence_count >= 1),
    first_occurred_at      timestamptz not null,
    last_occurred_at       timestamptz not null,
    assignee_member_id     uuid,
    due_at                 timestamptz,
    snoozed_until          timestamptz,
    related                jsonb not null default '{}'::jsonb,
    resolved_at            timestamptz,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    version                integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, assignee_member_id) references workspace_member (workspace_id, id)
);
create unique index work_item_open_dedupe on work_item (workspace_id, dedupe_key)
    where status in ('OPEN', 'IN_PROGRESS', 'ON_HOLD');
create index work_item_list_idx on work_item (workspace_id, status, last_occurred_at desc);
