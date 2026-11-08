package com.campusfood.merchant.service;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/** 店铺和商品共用图片格式、内容签名与大小校验。 */
public record UploadedPhoto(byte[] data, String mediaType) {
    static UploadedPhoto from(Map<String, Object> body, String label) {
        String image = Inputs.text(body, "image", label, 1_400_050, false);
        if (image.isEmpty()) return null;

        int comma = image.indexOf(',');
        if (comma < 0) throw Inputs.invalid("请上传 JPEG 或 PNG 图片");
        String type = switch (image.substring(0, comma)) {
            case "data:image/jpeg;base64" -> "image/jpeg";
            case "data:image/png;base64" -> "image/png";
            default -> throw Inputs.invalid("请上传 JPEG 或 PNG 图片");
        };
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(image.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw Inputs.invalid(label + "无法读取");
        }
        if (bytes.length < 8 || bytes.length > 1_048_576) {
            throw Inputs.invalid(label + "须不超过 1 MB");
        }
        byte[] signature = "image/png".equals(type) ? new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10}
            : new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        if (!Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length)) {
            throw Inputs.invalid("图片内容与文件格式不匹配");
        }
        return new UploadedPhoto(bytes, type);
    }
}
