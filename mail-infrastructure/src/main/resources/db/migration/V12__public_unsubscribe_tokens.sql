-- Opaque one-use links; only hashes are stored. No public route exposes email or client IDs.
create table mail_unsubscribe_token (
    token_digest char(64) primary key,
    client_id varchar(120) not null references mail_api_client(client_id) on delete cascade,
    email varchar(320) not null,
    issued_at timestamptz not null default current_timestamp,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    constraint ck_mail_unsubscribe_expiry check (expires_at > issued_at),
    constraint ck_mail_unsubscribe_email_normalized check (email = lower(email) and email = btrim(email))
);
create index idx_mail_unsubscribe_expiration on mail_unsubscribe_token(expires_at);
create index idx_mail_unsubscribe_tenant on mail_unsubscribe_token(client_id, issued_at desc);
