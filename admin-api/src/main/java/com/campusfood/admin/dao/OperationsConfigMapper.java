package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.OperationsConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 运营配置版本的条件查询。 */
@Mapper
public interface OperationsConfigMapper extends BaseMapper<OperationsConfig> {
    default Long lockLatestVersion() {
        List<Object> rows = selectObjs(Wrappers.<OperationsConfig>lambdaQuery()
                .select(OperationsConfig::getId).orderByDesc(OperationsConfig::getId).last("LIMIT 1 FOR UPDATE"));
        return rows.isEmpty() ? null : ((Number) rows.get(0)).longValue();
    }
}
