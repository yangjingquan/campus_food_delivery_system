package com.campusfood.user;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 保存地址与新建订单共用校验，拒绝缺失、非数值或越界坐标。 */
record AddressCoordinates(BigDecimal latitude, BigDecimal longitude) {
    static AddressCoordinates require(Map<String, Object> body) {
        try {
            var latitude = new BigDecimal(String.valueOf(body.get("latitude")));
            var longitude = new BigDecimal(String.valueOf(body.get("longitude")));
            if (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                    || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new NumberFormatException("坐标超出范围");
            }
            return new AddressCoordinates(latitude, longitude);
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请编辑收餐地址，在地图中设置有效的收餐位置");
        }
    }
}
