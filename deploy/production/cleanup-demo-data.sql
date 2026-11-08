-- Run once only against a newly created campus_food_delivery database after Flyway V11.
-- The V1 sample merchants and riders are local-development fixtures, not production records.
DELETE FROM rider WHERE (id = 'R01' AND phone = '188****2215') OR (id = 'R02' AND phone = '186****8732');
DELETE FROM merchant WHERE (id = 'M01' AND phone = '138****1021') OR (id = 'M02' AND phone = '139****3518');
