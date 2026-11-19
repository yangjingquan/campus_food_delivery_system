package com.campusfood.merchant.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;

/** 自动选择二维码版本，同时支持历史订单编号和固定长度的骑手领取码。 */
final class TicketQr {
    private TicketQr() {
    }

    static String image(String code) {
        try {
            var matrix = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 240, 240,
                    Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4));
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < matrix.getHeight(); y++) {
                for (int x = 0; x < matrix.getWidth(); x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
                }
            }
            try (var out = new ByteArrayOutputStream()) {
                ImageIO.write(image, "png", out);
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法生成小票二维码");
        }
    }
}
