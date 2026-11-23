package com.campusfood.domain;

import java.util.Arrays;
import java.util.Base64;

/** The JPEG/PNG data URI used by the store and product forms. */
public record InlinePhoto(byte[] data, String mediaType) {
    public static final int MAX_DATA_URI_LENGTH = 1_400_050;
    private static final int MAX_BYTES = 1_048_576;

    public static InlinePhoto parse(String image, String label) {
        if (image.isEmpty()) return null;
        if (image.length() > MAX_DATA_URI_LENGTH) {
            throw new IllegalArgumentException(label + "须不超过 1 MB");
        }

        int comma = image.indexOf(',');
        String type = switch (comma < 0 ? "" : image.substring(0, comma)) {
            case "data:image/jpeg;base64" -> "image/jpeg";
            case "data:image/png;base64" -> "image/png";
            default -> throw new IllegalArgumentException("请上传 JPEG 或 PNG 图片");
        };
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(image.substring(comma + 1));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(label + "无法读取", error);
        }
        if (bytes.length < 8 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException(label + "须不超过 1 MB");
        }
        byte[] signature = "image/png".equals(type) ? new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10}
            : new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        if (!Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length)) {
            throw new IllegalArgumentException("图片内容与文件格式不匹配");
        }
        return new InlinePhoto(bytes, type);
    }
}
