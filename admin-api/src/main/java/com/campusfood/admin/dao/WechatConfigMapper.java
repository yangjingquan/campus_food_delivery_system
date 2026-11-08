package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.WechatConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 微信配置版本的锁定与历史版本查询。 */
@Mapper
public interface WechatConfigMapper extends BaseMapper<WechatConfig> {
    default Map<String, Object> lockCurrentVersion() {
        List<Map<String, Object>> rows = selectMaps(Wrappers.<WechatConfig>lambdaQuery()
                .orderByDesc(WechatConfig::getId).last("LIMIT 1 FOR UPDATE"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    default List<Map<String, Object>> findVersion(String id) {
        return selectMaps(Wrappers.<WechatConfig>lambdaQuery().eq(WechatConfig::getId, id));
    }
}
