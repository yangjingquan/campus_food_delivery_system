CREATE TABLE configuration_lock (name VARCHAR(32) PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO configuration_lock(name) VALUES ('operations'),('wechat');
