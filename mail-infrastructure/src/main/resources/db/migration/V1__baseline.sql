create table mail_batch (
 id uuid primary key, client_id varchar(120) not null, subject varchar(300) not null, template_key varchar(200) not null, total_recipients integer not null, status varchar(30) not null, created_at timestamptz not null, updated_at timestamptz not null, constraint ck_mail_batch_total check (total_recipients > 0)
);
create index idx_mail_batch_client_created on mail_batch(client_id,created_at desc);

create table mail_message (
 id uuid primary key, client_id varchar(120) not null, idempotency_key varchar(200), subject varchar(300) not null, template_key varchar(200) not null, variables_json text not null, recipients_json text not null, attachment_ids_json text not null, priority varchar(30) not null, status varchar(30) not null, batch_id uuid, created_at timestamptz not null, updated_at timestamptz not null, last_error text, provider_message_id varchar(500)
);
create unique index uq_mail_message_client_idempotency on mail_message(client_id,idempotency_key) where idempotency_key is not null;
create index idx_mail_message_status_created on mail_message(status,created_at);
create index idx_mail_message_batch on mail_message(batch_id);

create table password_reset_challenge (
 id uuid primary key, client_id varchar(120) not null, subject_reference varchar(200) not null, email varchar(320) not null, otp_hash varchar(64) not null, status varchar(30) not null, failed_attempts integer not null default 0, max_attempts integer not null default 5, expires_at timestamptz not null, created_at timestamptz not null, consumed_at timestamptz, constraint ck_password_reset_attempts check (failed_attempts >= 0 and max_attempts > 0)
);
create index idx_password_reset_subject on password_reset_challenge(client_id,subject_reference,status);
create index idx_password_reset_expiry on password_reset_challenge(expires_at);

create table password_reset_grant (
 id uuid primary key, challenge_id uuid not null references password_reset_challenge(id), client_id varchar(120) not null, subject_reference varchar(200) not null, token_hash varchar(64) not null, expires_at timestamptz not null, created_at timestamptz not null, consumed_at timestamptz
);
create index idx_password_reset_grant_expiry on password_reset_grant(expires_at);

create table mail_attachment (
 id uuid primary key, filename varchar(255) not null, content_type varchar(200) not null, size_bytes bigint not null, storage_key varchar(500) not null unique, checksum_sha256 varchar(64) not null, created_at timestamptz not null, constraint ck_mail_attachment_size check (size_bytes >= 0)
);
create index idx_mail_attachment_created on mail_attachment(created_at);
