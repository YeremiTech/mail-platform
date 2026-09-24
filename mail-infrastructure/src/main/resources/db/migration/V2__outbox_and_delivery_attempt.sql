create table mail_outbox_event (
 id uuid primary key,
 aggregate_id uuid not null,
 event_type varchar(80) not null,
 routing_key varchar(120) not null,
 payload text not null,
 status varchar(30) not null,
 attempts integer not null default 0,
 next_attempt_at timestamptz not null,
 locked_until timestamptz,
 created_at timestamptz not null,
 updated_at timestamptz not null,
 published_at timestamptz,
 last_error text,
 constraint ck_mail_outbox_attempts check (attempts >= 0)
);
create index idx_mail_outbox_ready on mail_outbox_event(status,next_attempt_at,created_at);
create index idx_mail_outbox_aggregate on mail_outbox_event(aggregate_id);

create table mail_delivery_attempt (
 id uuid primary key,
 message_id uuid not null references mail_message(id) on delete cascade,
 attempt_number integer not null,
 provider_key varchar(80) not null,
 status varchar(30) not null,
 started_at timestamptz not null,
 finished_at timestamptz,
 provider_message_id varchar(500),
 error_type varchar(300),
 error_message text,
 constraint ck_mail_delivery_attempt_number check (attempt_number > 0),
 constraint uq_mail_delivery_attempt unique(message_id,attempt_number)
);
create index idx_mail_delivery_attempt_message on mail_delivery_attempt(message_id,attempt_number desc);
create index idx_mail_delivery_attempt_status on mail_delivery_attempt(status,started_at desc);
