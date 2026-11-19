package com.campusfood.admin.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusfood.admin.entity.MerchantPrinterSettings;
import org.apache.ibatis.annotations.Mapper;

/** 管理端使用的商户打印机配置表。 */
@Mapper
public interface MerchantPrinterSettingsMapper extends BaseMapper<MerchantPrinterSettings> {
}
