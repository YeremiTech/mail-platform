alter table mail_attachment add column content bytea;
create index idx_mail_attachment_client_id on mail_attachment(client_id, id);
