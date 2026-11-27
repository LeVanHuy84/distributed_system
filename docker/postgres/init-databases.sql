-- 1. Tạo 3 user riêng biệt
CREATE USER order_user WITH PASSWORD 'order_password';
CREATE USER inventory_user WITH PASSWORD 'inventory_password';
CREATE USER payment_user WITH PASSWORD 'payment_password';

-- 2. Tạo 3 logical databases riêng biệt
CREATE DATABASE order_db OWNER order_user;
CREATE DATABASE inventory_db OWNER inventory_user;
CREATE DATABASE payment_db OWNER payment_user;

-- 3. Thu hồi quyền mặc định từ PUBLIC để chặn truy cập chéo DB
REVOKE ALL ON DATABASE order_db FROM PUBLIC;
REVOKE ALL ON DATABASE inventory_db FROM PUBLIC;
REVOKE ALL ON DATABASE payment_db FROM PUBLIC;

GRANT ALL PRIVILEGES ON DATABASE order_db TO order_user;
GRANT ALL PRIVILEGES ON DATABASE inventory_db TO inventory_user;
GRANT ALL PRIVILEGES ON DATABASE payment_db TO payment_user;

-- 4. Cấp quyền sở hữu schema public trong từng DB (bắt buộc với PostgreSQL 15+ cho Flyway)  
\c order_db                                                                                  
ALTER SCHEMA public OWNER TO order_user;                                                     
                                                                                                 
\c inventory_db                                                                              
ALTER SCHEMA public OWNER TO inventory_user;                                                 
                                                                                                 
\c payment_db                                                                                
ALTER SCHEMA public OWNER TO payment_user;