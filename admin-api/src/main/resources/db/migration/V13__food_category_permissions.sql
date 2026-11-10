INSERT INTO admin_permission VALUES ('food-category.view','查看食品分类'),('food-category.edit','维护食品分类');
INSERT INTO admin_role_permission VALUES
 ('super','food-category.view'),('super','food-category.edit'),
 ('operations','food-category.view'),('operations','food-category.edit'),
 ('finance','food-category.view'),('readonly','food-category.view');
