package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.campusfood.admin.entity.WechatSecret;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/** 微信敏感材料的锁定查询与密钥指纹更新。 */
@Mapper
public interface WechatSecretMapper extends BaseMapper<WechatSecret> {
    default List<Map<String, Object>> lockUntagged() {
        return selectMaps(Wrappers.<WechatSecret>lambdaQuery()
                .select(WechatSecret::getId, WechatSecret::getPurpose, WechatSecret::getNonce, WechatSecret::getCiphertext)
                .isNull(WechatSecret::getKeyFingerprint).last("FOR UPDATE"));
    }

    default int tagKey(String fingerprint, Object secretId) {
        return update(null, Wrappers.<WechatSecret>lambdaUpdate()
                .set(WechatSecret::getKeyFingerprint, fingerprint).eq(WechatSecret::getId, secretId));
    }

    default Integer countMismatched(String fingerprint) {
        return Math.toIntExact(selectCount(Wrappers.<WechatSecret>lambdaQuery()
                .isNull(WechatSecret::getKeyFingerprint).or().ne(WechatSecret::getKeyFingerprint, fingerprint)));
    }
}
