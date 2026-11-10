CREATE TABLE food_category (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(40) NOT NULL UNIQUE
) ENGINE=InnoDB;

INSERT INTO food_category(name)
SELECT DISTINCT category FROM merchant_product;

INSERT IGNORE INTO food_category(name) VALUES ('餐品'),('米饭'),('面食'),('轻食');

-- 商品继续按分类名称对外返回，改名时由数据库同步更新关联商品。
ALTER TABLE merchant_product ADD CONSTRAINT fk_product_food_category
    FOREIGN KEY (category) REFERENCES food_category(name) ON UPDATE CASCADE ON DELETE RESTRICT;
