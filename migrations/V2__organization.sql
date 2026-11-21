-- =====================================================================
-- V2: 사업자·계좌·판매채널·판매계정(귀속 이력)·판매단위·물류사업자·화주계정
-- FR-ORG-01~04, FR-ORG-07, ARCH-003, docs/03 §2
-- =====================================================================

create table business_entity (
    id                        uuid primary key,
    workspace_id              uuid not null references workspace (id),
    display_name              text not null check (length(display_name) between 1 and 100),
    legal_name                text not null check (length(legal_name) between 1 and 200),
    legal_type                text not null check (legal_type in ('CORPORATION', 'INDIVIDUAL')),
    -- 사업자등록번호 10자리(하이픈 제외). 형식·검증번호는 애플리케이션에서도 검증
    business_registration_no  char(10) not null check (business_registration_no ~ '^[0-9]{10}$'),
    tax_type                  text not null check (tax_type in ('GENERAL', 'SIMPLIFIED', 'EXEMPT')),
    status                    text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at                timestamptz not null default now(),
    updated_at                timestamptz not null default now(),
    version                   integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, business_registration_no)
);
create index business_entity_ws_idx on business_entity (workspace_id, status);

-- 사업자 계좌. 계좌번호는 AES-GCM 암호문 + 끝 4자리 + 중복 탐지용 HMAC 만 저장.
create table bank_account (
    id                     uuid primary key,
    workspace_id           uuid not null,
    business_entity_id     uuid not null,
    bank_code              text not null check (bank_code ~ '^[0-9]{3}$'),
    account_holder         text not null check (length(account_holder) between 1 and 100),
    account_number_cipher  bytea not null,
    account_number_key_id  text not null,
    account_number_last4   text not null check (account_number_last4 ~ '^[0-9]{1,4}$'),
    account_number_hmac    bytea not null,
    purpose                text not null default 'SETTLEMENT' check (purpose in ('SETTLEMENT')),
    is_default             boolean not null default false,
    status                 text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    version                integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    unique (business_entity_id, account_number_hmac)
);
-- 사업자당 기본 정산계좌는 최대 1개 (FR-ORG-01)
create unique index bank_account_one_default on bank_account (business_entity_id) where is_default and status = 'ACTIVE';

-- 판매채널 기준표(전역). 연동 가능 여부를 뜻하지 않는다(docs/06 머리말).
create table sales_channel (
    code                text primary key check (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    name_ko             text not null,
    channel_group       text not null,
    kind                text not null check (kind in ('OWN_MALL', 'MARKETPLACE', 'PLATFORM_FULFILLMENT', 'B2B_RETAIL', 'SOCIAL_COMMERCE')),
    rollout_tier        text not null check (rollout_tier in ('PRIORITY', 'EXTENSION', 'LATER')),
    supports_sub_units  boolean not null default false,
    sub_unit_label_ko   text,
    sort_order          integer not null
);

insert into sales_channel (code, name_ko, channel_group, kind, rollout_tier, supports_sub_units, sub_unit_label_ko, sort_order) values
 ('CAFE24',                 '카페24',               'CAFE24',  'OWN_MALL',             'PRIORITY',  true,  '쇼핑몰(shop_no)', 10),
 ('NAVER_SMARTSTORE',       '네이버 스마트스토어',  'NAVER',   'MARKETPLACE',          'PRIORITY',  false, null, 20),
 ('COUPANG_MARKETPLACE',    '쿠팡 마켓플레이스',    'COUPANG', 'MARKETPLACE',          'PRIORITY',  false, null, 30),
 ('COUPANG_ROCKET_GROWTH',  '쿠팡 로켓그로스',      'COUPANG', 'PLATFORM_FULFILLMENT', 'PRIORITY',  false, null, 40),
 ('COUPANG_SUPPLIER_HUB',   '쿠팡 직매입(서플라이허브)', 'COUPANG', 'B2B_RETAIL',     'PRIORITY',  false, null, 50),
 ('GMARKET',                'G마켓',                'ESM',     'MARKETPLACE',          'PRIORITY',  false, null, 60),
 ('AUCTION',                '옥션',                 'ESM',     'MARKETPLACE',          'PRIORITY',  false, null, 70),
 ('ELEVENST',               '11번가',               'ELEVENST','MARKETPLACE',          'PRIORITY',  false, null, 80),
 ('KAKAO_TALKSTORE',        '카카오톡스토어',       'KAKAO',   'SOCIAL_COMMERCE',      'EXTENSION', false, null, 100),
 ('KAKAO_GIFT',             '카카오 선물하기',      'KAKAO',   'SOCIAL_COMMERCE',      'EXTENSION', false, null, 110),
 ('TOSS_SHOPPING',          '토스쇼핑',             'TOSS',    'MARKETPLACE',          'EXTENSION', false, null, 120),
 ('LOTTEON',                '롯데ON',               'LOTTE',   'MARKETPLACE',          'EXTENSION', false, null, 130),
 ('SSG',                    'SSG.COM',              'SSG',     'MARKETPLACE',          'EXTENSION', false, null, 140),
 ('MUSINSA',                '무신사',               'MUSINSA', 'MARKETPLACE',          'EXTENSION', false, null, 150),
 ('ABLY',                   '에이블리',             'ABLY',    'MARKETPLACE',          'EXTENSION', false, null, 160),
 ('ZIGZAG',                 '지그재그',             'ZIGZAG',  'MARKETPLACE',          'EXTENSION', false, null, 170),
 ('TWENTYNINE_CM',          '29CM',                 'MUSINSA', 'MARKETPLACE',          'EXTENSION', false, null, 180),
 ('WCONCEPT',               'W컨셉',                'WCONCEPT','MARKETPLACE',          'EXTENSION', false, null, 190),
 ('OHOUSE',                 '오늘의집',             'OHOUSE',  'MARKETPLACE',          'EXTENSION', false, null, 200),
 ('QUEENIT',                '퀸잇',                 'QUEENIT', 'MARKETPLACE',          'EXTENSION', false, null, 210),
 ('KURLY',                  '컬리',                 'KURLY',   'MARKETPLACE',          'EXTENSION', false, null, 220),
 ('OLIVEYOUNG',             '올리브영',             'OLIVEYOUNG','MARKETPLACE',        'EXTENSION', false, null, 230),
 ('ALIEXPRESS',             '알리익스프레스',       'ALIBABA', 'MARKETPLACE',          'LATER',     false, null, 240),
 ('TEMU',                   'Temu',                 'TEMU',    'MARKETPLACE',          'LATER',     false, null, 250),
 ('MANUAL',                 '수동·B2B 주문',        'INTERNAL','B2B_RETAIL',           'PRIORITY',  false, null, 900);

create table channel_account (
    id                    uuid primary key,
    workspace_id          uuid not null references workspace (id),
    -- 현재 귀속 사업자(파생값). 이력의 진실원은 channel_account_attribution
    business_entity_id    uuid not null,
    sales_channel_code    text not null references sales_channel (code),
    display_name          text not null check (length(display_name) between 1 and 100),
    -- 판매자 ID / mall_id / vendor ID 등 채널 측 계정 식별자(미확인 시 null 허용)
    external_account_key  text check (external_account_key is null or length(external_account_key) between 1 and 200),
    status                text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    version               integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
-- 같은 운영그룹 안에서 같은 채널의 같은 외부 계정을 중복 등록하지 않는다
create unique index channel_account_external_key_uq
    on channel_account (workspace_id, sales_channel_code, external_account_key)
    where external_account_key is not null and status <> 'CLOSED';
create index channel_account_be_idx on channel_account (workspace_id, business_entity_id, status);

-- 판매계정 사업자 귀속 이력 (FR-ORG-07). 기간 중복을 DB 배제 제약으로 금지.
create table channel_account_attribution (
    id                   uuid primary key,
    workspace_id         uuid not null,
    channel_account_id   uuid not null,
    business_entity_id   uuid not null,
    valid_from           timestamptz not null,
    valid_to             timestamptz,
    reason               text,
    created_at           timestamptz not null default now(),
    created_by           uuid,
    check (valid_to is null or valid_to > valid_from),
    foreign key (workspace_id, channel_account_id) references channel_account (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    exclude using gist (
        channel_account_id with =,
        tstzrange(valid_from, coalesce(valid_to, 'infinity'::timestamptz), '[)') with &&
    )
);

-- 특정 시각의 판매계정 귀속 사업자. 주문 등 거래 객체의 귀속 스냅샷 계산에 사용(REV-012)
create function channel_account_business_at(p_channel_account_id uuid, p_at timestamptz)
returns uuid language sql stable as $$
    select business_entity_id from channel_account_attribution
     where channel_account_id = p_channel_account_id
       and valid_from <= p_at and (valid_to is null or valid_to > p_at)
$$;

create table sales_unit (
    id                   uuid primary key,
    workspace_id         uuid not null,
    channel_account_id   uuid not null,
    -- shop_no 등 채널 측 하위 단위 키. 기본 단위는 'DEFAULT'. 생성 후 변경 불가
    external_unit_key    text not null check (length(external_unit_key) between 1 and 100),
    display_name         text not null check (length(display_name) between 1 and 100),
    is_default           boolean not null default false,
    status               text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    version              integer not null default 1,
    unique (workspace_id, id),
    unique (channel_account_id, external_unit_key),
    foreign key (workspace_id, channel_account_id) references channel_account (workspace_id, id)
);
-- 모든 판매계정은 기본 판매단위를 정확히 1개 가진다(ARCH-003). 최대 1개는 DB, 최소 1개는 생성 트랜잭션에서 보장
create unique index sales_unit_one_default on sales_unit (channel_account_id) where is_default;

-- 외부 키·이름 불변 보호: external_unit_key, is_default 변경 금지
create function sales_unit_guard_immutable() returns trigger language plpgsql as $$
begin
    if new.external_unit_key <> old.external_unit_key or new.is_default <> old.is_default
       or new.channel_account_id <> old.channel_account_id or new.workspace_id <> old.workspace_id then
        raise exception 'sales_unit identity columns are immutable' using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger sales_unit_immutable before update on sales_unit for each row execute function sales_unit_guard_immutable();

-- 물류사업자 기준표(전역). 연동 지원 여부를 뜻하지 않는다(docs/06 §4).
create table logistics_provider (
    code        text primary key check (code ~ '^[A-Z][A-Z0-9_]{1,62}$'),
    name_ko     text not null,
    kind        text not null check (kind in ('SELF_WMS', 'THIRD_PARTY', 'PLATFORM')),
    sort_order  integer not null
);
insert into logistics_provider (code, name_ko, kind, sort_order) values
 ('SELF_WMS',              '자체 WMS·자체창고',      'SELF_WMS',    10),
 ('FASSTO',                '파스토',                 'THIRD_PARTY', 20),
 ('POOMGO',                '품고',                   'THIRD_PARTY', 30),
 ('CJ_LOGISTICS_PARTNER',  'CJ 물류 파트너',         'THIRD_PARTY', 40),
 ('OURBOX',                '아워박스',               'THIRD_PARTY', 50),
 ('TEKTAKA_ARGO',          '테크타카(아르고)',       'THIRD_PARTY', 60),
 ('GENERIC_3PL',           '기타 3PL',               'THIRD_PARTY', 70),
 ('NAVER_N_DELIVERY',      '네이버 N배송 제휴 물류', 'PLATFORM',    80),
 ('COUPANG_ROCKET_GROWTH', '쿠팡 로켓그로스 물류',   'PLATFORM',    90);

-- 화주계정: 물류사업자와의 계약 주체 사업자. 재고 소유자·판매자와 다를 수 있다(docs/02 §4)
create table shipper_account (
    id                     uuid primary key,
    workspace_id           uuid not null references workspace (id),
    business_entity_id     uuid not null,
    logistics_provider_code text not null references logistics_provider (code),
    display_name           text not null check (length(display_name) between 1 and 100),
    external_shipper_key   text check (external_shipper_key is null or length(external_shipper_key) between 1 and 200),
    status                 text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),
    version                integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create unique index shipper_account_external_key_uq
    on shipper_account (workspace_id, logistics_provider_code, external_shipper_key)
    where external_shipper_key is not null and status <> 'CLOSED';
