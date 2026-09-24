alter table mail_api_client add column daily_transactional_limit integer not null default 5000;
alter table mail_api_client add column daily_bulk_limit integer not null default 10000;
alter table mail_api_client add constraint ck_mail_client_daily_standard check (daily_transactional_limit between 1 and 1000000);
alter table mail_api_client add constraint ck_mail_client_daily_bulk check (daily_bulk_limit between 1 and 1000000);
create table mail_api_daily_usage (
    client_id varchar(120) not null references mail_api_client(client_id) on delete cascade,
    usage_day date not null,
    channel varchar(20) not null,
    accepted_count integer not null default 0,
    primary key(client_id, usage_day, channel),
    constraint ck_mail_daily_channel check (channel in ('STANDARD','BULK')),
    constraint ck_mail_daily_count check (accepted_count >= 0)
);
create index idx_mail_api_daily_usage_day on mail_api_daily_usage(usage_day);
