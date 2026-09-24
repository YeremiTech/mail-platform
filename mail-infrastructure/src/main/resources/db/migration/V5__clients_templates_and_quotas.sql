-- Persistent tenant credentials and version-pinned, tenant-owned templates.
-- Existing INTERNAL_API_KEYS clients are deliberately not migrated automatically: plaintext secrets must never enter SQL.
create table mail_api_client (
    client_id varchar(120) primary key,
    display_name varchar(200) not null,
    enabled boolean not null default true,
    requests_per_minute integer not null default 120,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_mail_api_client_requests check (requests_per_minute between 1 and 10000)
);

create table mail_api_credential (
    id uuid primary key,
    client_id varchar(120) not null references mail_api_client(client_id) on delete cascade,
    secret_mac char(64) not null unique,
    created_at timestamptz not null default now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    last_used_at timestamptz,
    constraint ck_mail_api_credential_expiry check (expires_at is null or expires_at > created_at)
);
create index idx_mail_api_credential_client on mail_api_credential(client_id, created_at desc);

create table mail_api_request_window (
    client_id varchar(120) not null references mail_api_client(client_id) on delete cascade,
    window_started_at timestamptz not null,
    request_count integer not null default 0,
    primary key (client_id, window_started_at),
    constraint ck_mail_api_request_count check (request_count >= 0)
);
create index idx_mail_api_request_window_expiry on mail_api_request_window(window_started_at);

create table mail_template (
    client_id varchar(120) not null references mail_api_client(client_id) on delete cascade,
    slug varchar(80) not null,
    last_version integer not null default 1,
    published_version integer,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (client_id, slug),
    constraint ck_mail_template_version check (last_version >= 1 and (published_version is null or published_version between 1 and last_version))
);
create table mail_template_version (
    client_id varchar(120) not null,
    slug varchar(80) not null,
    version integer not null,
    html_body text not null,
    text_body text not null,
    required_variables text not null default '',
    created_at timestamptz not null default now(),
    primary key (client_id, slug, version),
    foreign key (client_id, slug) references mail_template(client_id, slug) on delete cascade,
    constraint ck_mail_template_version_positive check (version >= 1),
    constraint ck_mail_template_html_limit check (octet_length(html_body) between 1 and 131072),
    constraint ck_mail_template_text_limit check (octet_length(text_body) between 1 and 65536)
);
create index idx_mail_template_published on mail_template(client_id, updated_at desc);

alter table mail_message add column scheduled_at timestamptz;
create index idx_mail_message_client_status on mail_message(client_id, status, created_at desc);

-- Explicit ownership of attachment references enables safe orphan cleanup without guessing from JSON text.
create table mail_message_attachment (
    message_id uuid not null references mail_message(id) on delete cascade,
    attachment_id uuid not null references mail_attachment(id) on delete restrict,
    primary key (message_id, attachment_id)
);
create index idx_mail_message_attachment_attachment on mail_message_attachment(attachment_id);
insert into mail_message_attachment(message_id, attachment_id)
select distinct m.id, a.id
from mail_message m
cross join lateral jsonb_array_elements_text(m.attachment_ids_json::jsonb) attached(id)
join mail_attachment a on a.id = attached.id::uuid
on conflict do nothing;
