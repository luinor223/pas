alter table esign.signing_session add column session_ordinal bigint;

update esign.signing_session
set session_ordinal = substring(session_no from 5)::bigint;

alter table esign.signing_session alter column session_ordinal set not null;
alter table esign.signing_session add constraint ux_signing_session_ordinal unique (session_ordinal);
