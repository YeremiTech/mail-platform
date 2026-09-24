-- Existing v0.9 tenant permissions are intentionally unchanged; only new SQL inserts get a safer default.
alter table mail_api_client alter column permissions set default 'EMAIL_SEND,EMAIL_READ';
