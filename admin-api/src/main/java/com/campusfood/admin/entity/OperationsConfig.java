package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 运营配置表中用于版本锁定的字段。 */
@TableName("operations_config")
public class OperationsConfig {
    @TableId
    private Long id;

    public Long getId() {
        return id;
    }
}
