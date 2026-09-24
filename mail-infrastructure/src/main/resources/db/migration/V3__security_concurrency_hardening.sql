alter table mail_message add column processing_token uuid;
alter table mail_message add column processing_started_at timestamptz;
alter table mail_message add constraint fk_mail_message_batch foreign key (batch_id) references mail_batch(id);

alter table mail_attachment add column client_id varchar(120);
update mail_attachment set client_id='legacy' where client_id is null;
alter table mail_attachment alter column client_id set not null;
create index idx_mail_attachment_client_created on mail_attachment(client_id, created_at desc);

create table mail_sensitive_payload (
    message_id uuid primary key references mail_message(id) on delete cascade,
    nonce bytea not null,
    ciphertext bytea not null,
    expires_at timestamptz not null,
    created_at timestamptz not null
);
create index idx_mail_sensitive_payload_expiry on mail_sensitive_payload(expires_at);

alter table mail_message add constraint ck_mail_message_status check (status in ('CREATED','QUEUED','PROCESSING','SENT','DELIVERED','RETRYING','FAILED','CANCELLED'));
alter table mail_batch add constraint ck_mail_batch_status check (status in ('CREATED','QUEUED','PROCESSING','PARTIALLY_COMPLETED','COMPLETED','FAILED','CANCELLED'));
alter table password_reset_challenge add constraint ck_password_reset_challenge_status check (status in ('ACTIVE','CONSUMED','EXPIRED','REVOKED','BLOCKED'));
alter table mail_outbox_event add constraint ck_mail_outbox_status check (status in ('PENDING','PUBLISHING','PUBLISHED','FAILED','DEAD'));
alter table mail_delivery_attempt add constraint ck_mail_delivery_attempt_status check (status in ('STARTED','SUCCEEDED','FAILED'));

create table password_recovery_throttle (
    client_id varchar(120) not null,
    subject_reference varchar(200) not null,
    window_started_at timestamptz not null,
    request_count integer not null default 0,
    last_request_at timestamptz,
    primary key (client_id, subject_reference),
    constraint ck_password_recovery_throttle_count check (request_count >= 0)
);
