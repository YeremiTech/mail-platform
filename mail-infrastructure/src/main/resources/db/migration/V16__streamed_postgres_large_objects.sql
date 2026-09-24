-- Keep historical bytea rows readable; only new uploads use large objects until optional batch migration.
alter table mail_attachment add column content_oid oid unique;

-- Delete the underlying large object atomically with each attachment metadata deletion.
create function fn_mail_attachment_delete_large_object() returns trigger
language plpgsql as $$
begin
    if old.content_oid is not null then
        perform lo_unlink(old.content_oid);
    end if;
    return old;
end;
$$;
create trigger trg_mail_attachment_unlink_large_object
    after delete on mail_attachment
    for each row execute function fn_mail_attachment_delete_large_object();
