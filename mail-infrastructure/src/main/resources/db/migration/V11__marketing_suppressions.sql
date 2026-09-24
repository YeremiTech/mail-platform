-- Client-owned opt-outs. Historical batches remain transactional unless explicitly marked otherwise.
create table mail_recipient_suppression (
    client_id varchar(120) not null references mail_api_client(client_id) on delete cascade,
    email varchar(320) not null,
    reason varchar(24) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (client_id, email),
    constraint ck_mail_suppression_reason check (reason in ('UNSUBSCRIBED','COMPLAINT','BOUNCE','MANUAL')),
    constraint ck_mail_suppression_email_normalized check (email = lower(email) and email = btrim(email))
);
create index idx_mail_suppression_recent on mail_recipient_suppression(client_id,updated_at desc);

-- Fully suppressed campaigns legitimately have zero eligible messages.
alter table mail_batch drop constraint ck_mail_batch_total;
alter table mail_batch add constraint ck_mail_batch_total check (total_recipients >= 0);
alter table mail_batch add column purpose varchar(20) not null default 'TRANSACTIONAL';
alter table mail_batch add constraint ck_mail_batch_purpose check (purpose in ('TRANSACTIONAL','MARKETING'));
alter table mail_campaign_recipient add column suppressed_at timestamptz;
alter table mail_campaign_recipient drop constraint ck_mail_campaign_state;
alter table mail_campaign_recipient add constraint ck_mail_campaign_state
    check (state in ('PENDING','SUBMITTED','CANCELLED','SUPPRESSED'));
create index idx_mail_campaign_recipient_suppressed on mail_campaign_recipient(batch_id) where state='SUPPRESSED';
