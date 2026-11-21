-- F9 인증 강화 (docs/11 DLG-011~014)
-- 2단계 인증(TOTP, RFC 6238)은 사용자 단위. 비밀키는 애플리케이션 AES-GCM 으로 암호화 저장(DLG-006).

alter table auth_session add column mfa_verified_at timestamptz;
create index auth_session_user_idx on auth_session (user_id) where revoked_at is null;

create table user_mfa_totp (
    user_id         uuid primary key references user_account (id),
    secret_cipher   bytea not null,
    key_id          text not null,
    confirmed_at    timestamptz,
    -- 재사용 방지: 마지막으로 받아들인 30초 구간 번호보다 큰 구간의 코드만 허용
    last_used_step  bigint not null default 0 check (last_used_step >= 0),
    created_at      timestamptz not null default now()
);

create table user_mfa_recovery_code (
    id          uuid primary key,
    user_id     uuid not null references user_account (id),
    code_hash   bytea not null unique,
    created_at  timestamptz not null default now(),
    used_at     timestamptz
);
create index user_mfa_recovery_code_user_idx on user_mfa_recovery_code (user_id) where used_at is null;
