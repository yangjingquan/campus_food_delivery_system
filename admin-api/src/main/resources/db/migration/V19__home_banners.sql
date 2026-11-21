CREATE TABLE home_banner (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    link_type VARCHAR(16) NOT NULL,
    link_url VARCHAR(2048) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    image_data MEDIUMBLOB NOT NULL,
    media_type VARCHAR(20) NOT NULL,
    image_version CHAR(36) NOT NULL,
    INDEX idx_home_banner_display (sort_order DESC, id DESC)
) ENGINE=InnoDB;

INSERT INTO admin_permission VALUES ('home-banner.view','查看首页轮播图'),('home-banner.edit','维护首页轮播图');
INSERT INTO admin_role_permission VALUES
 ('super','home-banner.view'),('super','home-banner.edit'),
 ('operations','home-banner.view'),('operations','home-banner.edit');
