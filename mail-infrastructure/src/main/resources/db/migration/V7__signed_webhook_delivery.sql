alter table mail_api_client add column webhook_allowed_host varchar(253);

create table mail_webhook_subscription (
    client_id varchar(120) primary key references mail_api_client(client_id) on delete cascade,
    endpoint text not null,
    nonce bytea not null,
    encrypted_secret bytea not null,
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_mail_webhook_endpoint_limit check (octet_length(endpoint) between 12 and 2048)
);

create table mail_webhook_delivery (
    id uuid primary key default gen_random_uuid(),
    message_id uuid not null references mail_message(id) on delete cascade,
    client_id varchar(120) not null references mail_api_client(client_id) on delete cascade,
    event_type varchar(40) not null,
    occurred_at timestamptz not null,
    status varchar(20) not null default 'PENDING',
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    locked_until timestamptz,
    last_status_code integer,
    last_error varchar(500),
    delivered_at timestamptz,
    constraint ck_mail_webhook_state check (status in ('PENDING','DELIVERING','RETRYING','DELIVERED','DEAD','SKIPPED')),
    constraint ck_mail_webhook_attempts check (attempts between 0 and 8),
    constraint uq_mail_webhook_message_event unique (message_id, event_type)
);
create index idx_mail_webhook_ready on mail_webhook_delivery(status,next_attempt_at);
create index idx_mail_webhook_client_created on mail_webhook_delivery(client_id,occurred_at desc);

create or replace function fn_enqueue_mail_webhook()
returns trigger language plpgsql as $$
begin
    if old.status is distinct from new.status and new.status in ('SENT','FAILED','CANCELLED') then
        insert into mail_webhook_delivery(message_id,client_id,event_type,occurred_at)
        select new.id,new.client_id,'mail.' || lower(new.status),new.updated_at
        from mail_webhook_subscription s
        where s.client_id=new.client_id and s.enabled=true
        on conflict (message_id,event_type) do nothing;
    end if;
    return new;
end;
$$;
create trigger trg_mail_message_webhook after update of status on mail_message
for each row execute function fn_enqueue_mail_webhook();
