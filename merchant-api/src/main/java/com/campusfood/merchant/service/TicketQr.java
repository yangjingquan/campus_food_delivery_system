package com.campusfood.merchant.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;

/** 领取码固定为 22 个 ASCII 字符，使用 QR Code 版本 2、纠错等级 L。 */
final class TicketQr {
    private static final int SIZE = 25;
    private static final int DATA_BYTES = 34;
    private static final int ECC_BYTES = 10;
    private static final int FORMAT_L_MASK_0 = 0x77c4;

    private final boolean[][] modules = new boolean[SIZE][SIZE];
    private final boolean[][] reserved = new boolean[SIZE][SIZE];

    static String image(String code) {
        TicketQr qr = new TicketQr();
        qr.patterns();
        qr.place(codewords(code));
        return qr.png();
    }

    private void patterns() {
        for (int i = 0; i < SIZE; i++) {
            fixed(6, i, i % 2 == 0);
            fixed(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(SIZE - 4, 3);
        finder(3, SIZE - 4);
        for (int y = -2; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                fixed(18 + x, 18 + y, Math.max(Math.abs(x), Math.abs(y)) != 1);
            }
        }
        for (int i = 0; i < 15; i++) {
            boolean dark = ((FORMAT_L_MASK_0 >>> i) & 1) != 0;
            if (i < 6) fixed(8, i, dark);
            else if (i < 8) fixed(8, i + 1, dark);
            else if (i == 8) fixed(7, 8, dark);
            else fixed(14 - i, 8, dark);
            if (i < 8) fixed(SIZE - 1 - i, 8, dark);
            else fixed(8, SIZE - 15 + i, dark);
        }
        fixed(8, SIZE - 8, true);
    }

    private void finder(int centerX, int centerY) {
        for (int y = -4; y <= 4; y++) {
            for (int x = -4; x <= 4; x++) {
                int distance = Math.max(Math.abs(x), Math.abs(y));
                int column = centerX + x;
                int row = centerY + y;
                if (column >= 0 && column < SIZE && row >= 0 && row < SIZE) {
                    fixed(column, row, distance != 2 && distance != 4);
                }
            }
        }
    }

    private void fixed(int column, int row, boolean dark) {
        modules[row][column] = dark;
        reserved[row][column] = true;
    }

    private void place(int[] bytes) {
        int bit = 0;
        boolean upwards = true;
        for (int right = SIZE - 1; right > 0; right -= 2) {
            if (right == 6) right = 5;
            for (int rowIndex = 0; rowIndex < SIZE; rowIndex++) {
                int row = upwards ? SIZE - 1 - rowIndex : rowIndex;
                for (int column = right; column >= right - 1; column--) {
                    if (reserved[row][column]) continue;
                    boolean dark = bit < bytes.length * 8 && ((bytes[bit / 8] >>> (7 - bit % 8)) & 1) != 0;
                    modules[row][column] = dark ^ ((row + column) % 2 == 0);
                    bit++;
                }
            }
            upwards = !upwards;
        }
    }

    private static int[] codewords(String code) {
        if (!code.matches("[A-Za-z0-9_-]{22}")) throw new IllegalArgumentException("领取码格式无效");
        int[] words = new int[DATA_BYTES + ECC_BYTES];
        // 字节模式头、长度、内容和 4 位结束符恰好占用 24 个码字。
        words[0] = 0x40 | (code.length() >>> 4);
        int offset = 1;
        int carry = (code.length() & 0x0f) << 4;
        for (byte value : code.getBytes(StandardCharsets.US_ASCII)) {
            words[offset++] = carry | (value >>> 4);
            carry = (value & 0x0f) << 4;
        }
        words[offset++] = carry;
        for (int i = offset; i < DATA_BYTES; i++) {
            words[i] = (i - offset) % 2 == 0 ? 0xec : 0x11;
        }
        // Reed–Solomon 生成多项式的十个低位系数，运算域为 GF(256)。
        int[] generator = {216, 194, 159, 111, 199, 94, 95, 113, 157, 193};
        int[] division = words.clone();
        for (int i = 0; i < DATA_BYTES; i++) {
            for (int j = 0; j < ECC_BYTES; j++) {
                division[i + j + 1] ^= multiply(division[i], generator[j]);
            }
        }
        System.arraycopy(division, DATA_BYTES, words, DATA_BYTES, ECC_BYTES);
        return words;
    }

    private static int multiply(int left, int right) {
        int result = 0;
        for (int i = 0; i < 8; i++) {
            if ((right & 1) != 0) result ^= left;
            right >>>= 1;
            left <<= 1;
            if ((left & 0x100) != 0) left ^= 0x11d;
        }
        return result;
    }

    private String png() {
        int scale = 6;
        int margin = 4;
        int width = (SIZE + margin * 2) * scale;
        BufferedImage image = new BufferedImage(width, width, BufferedImage.TYPE_BYTE_BINARY);
        for (int row = 0; row < width; row++) {
            for (int column = 0; column < width; column++) {
                int y = row / scale - margin;
                int x = column / scale - margin;
                boolean dark = x >= 0 && x < SIZE && y >= 0 && y < SIZE && modules[y][x];
                image.setRGB(column, row, dark ? 0xff000000 : 0xffffffff);
            }
        }
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("无法生成领取二维码", e);
        }
    }
}
