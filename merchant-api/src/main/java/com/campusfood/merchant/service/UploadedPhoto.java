package com.campusfood.merchant.service;

import com.campusfood.domain.InlinePhoto;
import java.util.Map;

/** 店铺和商品共用图片格式、内容签名与大小校验。 */
public record UploadedPhoto(byte[] data, String mediaType) {
    static UploadedPhoto from(Map<String, Object> body, String label) {
        String image = Inputs.text(body, "image", label, InlinePhoto.MAX_DATA_URI_LENGTH, false);
        try {
            var photo = InlinePhoto.parse(image, label);
            return photo == null ? null : new UploadedPhoto(photo.data(), photo.mediaType());
        } catch (IllegalArgumentException error) {
            throw Inputs.invalid(error.getMessage());
        }
    }
}
