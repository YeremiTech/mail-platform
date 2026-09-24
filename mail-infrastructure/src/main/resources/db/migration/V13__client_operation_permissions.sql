alter table mail_api_client
    add column permissions varchar(1000) not null default '*';

alter table mail_api_client
    add constraint ck_mail_api_client_permissions
    check (length(btrim(permissions)) between 1 and 1000);
