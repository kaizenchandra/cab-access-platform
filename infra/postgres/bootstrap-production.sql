-- Run once through an approved private database session as RDS bootstrap administrator.
-- psql variables owner_password/app_password must be supplied without shell history exposure.
CREATE ROLE cab_owner LOGIN PASSWORD :'owner_password' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE cab_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOBYPASSRLS;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT
ALL
ON SCHEMA public TO cab_owner;
GRANT CONNECT
ON DATABASE cabaccess TO cab_owner,cab_app;
