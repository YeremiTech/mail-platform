-- Holds take precedence over automated cleanup. Existing attachments remain governed by legacy orphan rules.
alter table mail_attachment add column legal_hold boolean not null default false;
alter table mail_attachment add column retention_until timestamptz;
create index idx_mail_attachment_cleanup_candidates on mail_attachment(created_at, id)
    where legal_hold = false;
