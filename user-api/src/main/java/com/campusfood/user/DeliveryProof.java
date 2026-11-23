package com.campusfood.user;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class DeliveryProof {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000;

    private DeliveryProof() {}

    static String mediaType(byte[] photo) {
        if (photo == null || photo.length == 0 || photo.length > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传 4 MB 以内的送达照片");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(photo))) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if ("jpeg".equals(format) || "png".equals(format)) {
                        reader.setInput(input, true, true);
                        if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "照片分辨率过高，请重新拍摄");
                        }
                        reader.read(0).flush();
                        return "image/" + format;
                    }
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取送达照片，请重新拍摄");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "送达照片只支持 JPEG 或 PNG");
    }
}
