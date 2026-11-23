package com.campusfood.merchant.service;

import java.util.Base64;

/** 为商户端小票预览返回内联二维码图片。 */
final class TicketQr {
    private TicketQr() {
    }

    static String image(String code) {
        byte[] png = com.campusfood.domain.TicketQr.png(code);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }
}
