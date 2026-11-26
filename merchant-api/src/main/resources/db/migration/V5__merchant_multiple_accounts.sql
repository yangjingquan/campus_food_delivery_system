-- merchant_id 上的外键仍需索引。先添加普通索引，再移除现有唯一索引。
ALTER TABLE merchant_identity ADD INDEX idx_merchant_identity_merchant (merchant_id);
ALTER TABLE merchant_identity DROP INDEX merchant_id;
