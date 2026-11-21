-- =====================================================================
-- V1: 운영그룹·사용자·구성원·역할·권한·접근 범위·세션 (FR-ORG-05/06, docs/08)
-- 원칙
--  * 모든 테넌트 데이터는 workspace_id 를 가진다.
--  * 자식 테이블은 (workspace_id, parent_id) 복합 FK 로 부모를 참조하여
--    다른 운영그룹 객체를 참조하는 행을 DB 수준에서 만들 수 없게 한다(불변조건 1).
--  * 내부 PK 는 애플리케이션에서 생성한 UUIDv7. 외부 식별자는 별도 컬럼.
-- =====================================================================
create extension if not exists btree_gist;
create extension if not exists citext;

create table workspace (
    id           uuid primary key,
    code         text not null unique check (code ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    name         text not null check (length(name) between 1 and 200),
    status       text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED')),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    version      integer not null default 1
);

-- 사용자는 운영그룹과 무관한 전역 신원. 운영그룹 소속은 workspace_member.
create table user_account (
    id                    uuid primary key,
    email                 citext not null unique,
    display_name          text not null check (length(display_name) between 1 and 100),
    password_hash         text not null,
    status                text not null default 'ACTIVE' check (status in ('ACTIVE', 'LOCKED', 'DISABLED')),
    failed_login_count    integer not null default 0 check (failed_login_count >= 0),
    locked_until          timestamptz,
    password_changed_at   timestamptz not null default now(),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    version               integer not null default 1
);

create table workspace_member (
    id                     uuid primary key,
    workspace_id           uuid not null references workspace (id),
    user_id                uuid not null references user_account (id),
    status                 text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'REMOVED')),
    -- 권한/범위가 바뀔 때마다 증가. 캐시 키·세션 재평가에 사용(docs/08 §4)
    access_policy_version  integer not null default 1,
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    version                integer not null default 1,
    unique (workspace_id, user_id),
    unique (workspace_id, id)
);

-- 권한 카탈로그(전역). 역할 템플릿과 무관하게 코드로 정의한다.
create table permission (
    code         text primary key check (code ~ '^[a-z_]+(\.[a-z_]+)+$'),
    category     text not null,
    name_ko      text not null,
    sensitive    boolean not null default false
);

create table role (
    id            uuid primary key,
    workspace_id  uuid not null references workspace (id),
    code          text not null check (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    name_ko       text not null,
    is_system     boolean not null default false,
    created_at    timestamptz not null default now(),
    unique (workspace_id, code),
    unique (workspace_id, id)
);

create table role_permission (
    workspace_id     uuid not null,
    role_id          uuid not null,
    permission_code  text not null references permission (code),
    primary key (role_id, permission_code),
    foreign key (workspace_id, role_id) references role (workspace_id, id) on delete cascade
);

create table member_role (
    workspace_id  uuid not null,
    member_id     uuid not null,
    role_id       uuid not null,
    granted_at    timestamptz not null default now(),
    granted_by    uuid,
    primary key (member_id, role_id),
    foreign key (workspace_id, member_id) references workspace_member (workspace_id, id),
    foreign key (workspace_id, role_id) references role (workspace_id, id)
);

-- 사용자별 권한 예외. DENY 가 역할 권한보다 우선(docs/08 §2-4).
create table member_permission_override (
    workspace_id     uuid not null,
    member_id        uuid not null,
    permission_code  text not null references permission (code),
    effect           text not null check (effect in ('ALLOW', 'DENY')),
    reason           text,
    created_at       timestamptz not null default now(),
    created_by       uuid,
    primary key (member_id, permission_code),
    foreign key (workspace_id, member_id) references workspace_member (workspace_id, id)
);

-- 사용자별 접근 범위 설정: 차원 × 접근종류마다 ALL/SELECTED (docs/08 §2-3, DEC-F02/F03)
create table member_scope_setting (
    workspace_id      uuid not null,
    member_id         uuid not null,
    dimension         text not null check (dimension in ('BUSINESS_ENTITY', 'CHANNEL_ACCOUNT', 'FULFILLMENT_NODE')),
    access_kind       text not null check (access_kind in ('READ', 'WRITE', 'EXPORT')),
    mode              text not null check (mode in ('ALL', 'SELECTED')),
    auto_include_new  boolean not null default true,
    primary key (member_id, dimension, access_kind),
    foreign key (workspace_id, member_id) references workspace_member (workspace_id, id)
);

-- 범위 항목: SELECTED 의 ALLOW 목록과, 모드와 무관하게 우선하는 DENY 목록.
-- target_id 는 차원별로 다른 테이블을 가리키므로 FK 대신 애플리케이션이 같은 운영그룹 소속을 검증한다.
create table member_scope_entry (
    workspace_id  uuid not null,
    member_id     uuid not null,
    dimension     text not null check (dimension in ('BUSINESS_ENTITY', 'CHANNEL_ACCOUNT', 'FULFILLMENT_NODE')),
    access_kind   text not null check (access_kind in ('READ', 'WRITE', 'EXPORT')),
    target_id     uuid not null,
    effect        text not null check (effect in ('ALLOW', 'DENY')),
    created_at    timestamptz not null default now(),
    created_by    uuid,
    primary key (member_id, dimension, access_kind, target_id),
    foreign key (workspace_id, member_id) references workspace_member (workspace_id, id)
);
create index member_scope_entry_target_idx on member_scope_entry (workspace_id, dimension, target_id);

-- 불투명 접근 토큰 세션. 토큰 원문은 저장하지 않고 SHA-256 해시만 저장.
create table auth_session (
    id             uuid primary key,
    token_hash     bytea not null unique,
    user_id        uuid not null references user_account (id),
    workspace_id   uuid not null,
    member_id      uuid not null,
    created_at     timestamptz not null default now(),
    expires_at     timestamptz not null,
    last_seen_at   timestamptz not null default now(),
    revoked_at     timestamptz,
    client_ip      text,
    user_agent     text,
    foreign key (workspace_id, member_id) references workspace_member (workspace_id, id)
);
create index auth_session_member_idx on auth_session (member_id) where revoked_at is null;

-- ---------------------------------------------------------------------
-- 권한 카탈로그 시드 (docs/08 §3 예시 + Foundation 필요 권한). 매트릭스는 docs/permission-matrix.md
-- ---------------------------------------------------------------------
insert into permission (code, category, name_ko, sensitive) values
 ('access.manage',                  'IDENTITY',    '사용자·권한 관리', true),
 ('member.read',                    'IDENTITY',    '사용자 목록 조회', false),
 ('business_entity.read',           'ORGANIZATION','사업자 조회', false),
 ('business_entity.manage',         'ORGANIZATION','사업자 등록·변경', false),
 ('bank_account.read',              'ORGANIZATION','사업자 계좌번호 원문 조회', true),
 ('bank_account.manage',            'ORGANIZATION','사업자 계좌 등록·변경', true),
 ('channel_account.read',           'CHANNEL',     '판매계정·판매단위 조회', false),
 ('channel_account.manage',         'CHANNEL',     '판매계정·판매단위 등록·변경', false),
 ('channel_account.reassign',       'CHANNEL',     '판매계정 사업자 귀속 변경', true),
 ('logistics_account.read',         'LOGISTICS',   '물류사·화주계정 조회', false),
 ('logistics_account.manage',       'LOGISTICS',   '화주계정 등록·변경', false),
 ('integration.read',               'INTEGRATION', '연동 상태·지원 기능 조회', false),
 ('integration.manage',             'INTEGRATION', '연동 연결 등록·변경·일시중지', false),
 ('integration.credential.manage',  'INTEGRATION', '연동 인증정보 등록·교체', true),
 ('integration.capability.verify',  'INTEGRATION', '지원 기능 검증 결과 기록', true),
 ('integration.sync',               'INTEGRATION', '연동 수집·점검 실행', false),
 ('work_item.read',                 'OPERATIONS',  '업무함 조회', false),
 ('work_item.update',               'OPERATIONS',  '업무 처리·배정', false),
 ('audit.read',                     'OPERATIONS',  '감사이력 조회', true),
 ('export.create',                  'OPERATIONS',  '데이터 내보내기', true),
 ('file.upload',                    'OPERATIONS',  '파일 업로드', false),
 ('file.read',                      'OPERATIONS',  '업로드 파일 조회·다운로드', false),
 ('order.read',                     'ORDER',       '주문 조회', false),
 ('order.update',                   'ORDER',       '주문 변경', false),
 ('claim.refund',                   'CLAIM',       '환불 실행', true),
 ('inventory.adjust',               'INVENTORY',   '재고 조정', true),
 ('inventory.dispose',              'INVENTORY',   '재고 폐기', true),
 ('pii.read',                       'PRIVACY',     '고객 개인정보 원문 조회', true),
 ('cost.read',                      'FINANCE',     '원가·비용 조회', true),
 ('settlement.read',                'FINANCE',     '정산 조회', true),
 ('profit.read',                    'FINANCE',     '손익 조회', true),
 ('period.close',                   'FINANCE',     '월 마감·재개방', true);
