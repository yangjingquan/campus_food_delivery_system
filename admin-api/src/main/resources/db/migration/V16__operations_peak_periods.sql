CREATE TABLE operations_peak_period (
    config_id BIGINT NOT NULL,
    start_time CHAR(5) NOT NULL,
    end_time CHAR(5) NOT NULL,
    PRIMARY KEY (config_id, start_time),
    FOREIGN KEY (config_id) REFERENCES operations_config(id),
    CHECK (start_time < end_time)
) ENGINE=InnoDB;

-- 将每个历史版本的单一高峰时段迁移为独立记录。
INSERT INTO operations_peak_period(config_id,start_time,end_time)
SELECT id,peak_start,peak_end FROM operations_config WHERE peak_start < peak_end;
