-- Finish the V16 transition so runtime code never needs to materialize legacy BYTEA content.
-- Each attachment is limited to 15 MB by the application, and conversion occurs inside PostgreSQL.
do $$
declare
    attachment record;
    migrated_oid oid;
begin
    for attachment in
        select id, content
        from mail_attachment
        where content_oid is null and content is not null
        order by created_at, id
    loop
        migrated_oid := lo_from_bytea(0, attachment.content);
        update mail_attachment
        set content_oid = migrated_oid,
            storage_key = 'lo:' || migrated_oid::text
        where id = attachment.id;
    end loop;
end $$;

do $$
begin
    if exists (select 1 from mail_attachment where content_oid is null) then
        raise exception 'cannot complete attachment migration: attachment without content_oid';
    end if;
end $$;

alter table mail_attachment alter column content_oid set not null;
alter table mail_attachment drop column content;
