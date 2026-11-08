package com.campusfood.admin.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** configuration_lock 表的条件构造器字段映射。 */
@TableName("configuration_lock")
public class ConfigurationLock {
    @TableId
    private String name;

    public String getName() {
        return name;
    }
}
