create role identity     login password 'identity';
create role contract     login password 'contract';
create role pricing      login password 'pricing';
create role operations   login password 'operations';
create role billing      login password 'billing';
create role workflow     login password 'workflow';
create role esign        login password 'esign';
create role notification login password 'notification';
create role audit        login password 'audit';

create database pas_identity     owner identity;
create database pas_contract     owner contract;
create database pas_pricing      owner pricing;
create database pas_operations   owner operations;
create database pas_billing      owner billing;
create database pas_workflow     owner workflow;
create database pas_esign        owner esign;
create database pas_notification owner notification;
create database pas_audit        owner audit;

revoke connect on database pas_identity     from public;
revoke connect on database pas_contract     from public;
revoke connect on database pas_pricing      from public;
revoke connect on database pas_operations   from public;
revoke connect on database pas_billing      from public;
revoke connect on database pas_workflow     from public;
revoke connect on database pas_esign        from public;
revoke connect on database pas_notification from public;
revoke connect on database pas_audit        from public;

grant connect on database pas_identity     to identity;
grant connect on database pas_contract     to contract;
grant connect on database pas_pricing      to pricing;
grant connect on database pas_operations   to operations;
grant connect on database pas_billing      to billing;
grant connect on database pas_workflow     to workflow;
grant connect on database pas_esign        to esign;
grant connect on database pas_notification to notification;
grant connect on database pas_audit        to audit;
