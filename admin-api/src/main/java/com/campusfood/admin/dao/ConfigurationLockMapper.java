package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.ConfigurationLock;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 在事务中锁定配置入口，序列化并发修改。 */
@Mapper
public interface ConfigurationLockMapper extends BaseMapper<ConfigurationLock> {
    default String lock(String name) {
        List<Object> rows = selectObjs(Wrappers.<ConfigurationLock>lambdaQuery()
                .select(ConfigurationLock::getName).eq(ConfigurationLock::getName, name).last("FOR UPDATE"));
        return rows.isEmpty() ? null : (String) rows.get(0);
    }
}
