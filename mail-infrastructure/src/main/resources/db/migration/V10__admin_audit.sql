-- Non-sensitive, append-only administrative audit. Never write API keys or mail content here.
create table mail_admin_audit (
    id bigint generated always as identity primary key,
    created_at timestamptz not null default now(),
    actor varchar(60) not null,
    action varchar(64) not null,
    target_client_id varchar(120) not null,
    reference_id uuid,
    fields_changed varchar(300),
    constraint ck_mail_admin_audit_action check (action in (
        'CLIENT_CREATED','CLIENT_UPDATED','CREDENTIAL_ISSUED','CREDENTIAL_REVOKED'))
);
create index idx_mail_admin_audit_created on mail_admin_audit(created_at desc, id desc);
create index idx_mail_admin_audit_client on mail_admin_audit(target_client_id, created_at desc);
