alter table mail_batch add column next_drain_at timestamptz;
create index idx_mail_batch_next_drain on mail_batch(next_drain_at) where status <> 'CANCELLED';
