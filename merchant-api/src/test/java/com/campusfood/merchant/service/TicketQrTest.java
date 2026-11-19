package com.campusfood.merchant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

class TicketQrTest {
    @Test
    void decodesBothLegacyOrderIdsAndExistingPickupTokens() throws Exception {
        for (String value : new String[] {
                "O-12345678-1234-1234-1234-123456789012", "202611191234AB0001", "abcdefghijklmnopqrstuv"
        }) {
            var bytes = Base64.getDecoder().decode(TicketQr.image(value).split(",", 2)[1]);
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            int width = image.getWidth();
            int height = image.getHeight();
            int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
            var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, rgb)));
            assertEquals(value, new QRCodeReader().decode(bitmap).getText());
        }
    }
}
