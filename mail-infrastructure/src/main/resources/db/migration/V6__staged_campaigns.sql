-- Large campaigns are staged before the queue is populated, keeping HTTP processing bounded.
create table mail_campaign_recipient (
    batch_id uuid not null references mail_batch(id) on delete cascade,
    position integer not null,
    email varchar(320) not null,
    display_name varchar(200),
    variables_json text not null default '{}',
    state varchar(20) not null default 'PENDING',
    created_at timestamptz not null default now(),
    submitted_at timestamptz,
    primary key (batch_id, position),
    constraint ck_mail_campaign_position check (position >= 0),
    constraint ck_mail_campaign_state check (state in ('PENDING','SUBMITTED','CANCELLED'))
);
create index idx_mail_campaign_recipient_pending on mail_campaign_recipient(batch_id, position) where state='PENDING';

create table mail_campaign_attachment (
    batch_id uuid not null references mail_batch(id) on delete cascade,
    attachment_id uuid not null references mail_attachment(id) on delete restrict,
    primary key (batch_id, attachment_id)
);
create index idx_mail_campaign_attachment_attachment on mail_campaign_attachment(attachment_id);
