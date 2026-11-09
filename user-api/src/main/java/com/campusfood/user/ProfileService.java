package com.campusfood.user;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private static final int MAX_AVATAR_BYTES = 512 * 1024;
    private static final int MAX_AVATAR_DIMENSION = 2048;
    private final JdbcTemplate db;

    public record Profile(long id, String displayName, boolean hasAvatar) {}
    public record Avatar(byte[] data, String mediaType) {}

    public ProfileService(JdbcTemplate db) {
        this.db = db;
    }

    public Profile get(long userId) {
        return db.queryForObject("""
            SELECT id, display_name, avatar_media_type IS NOT NULL AS has_avatar
            FROM user_profile WHERE id=?
            """, (rs, row) -> new Profile(rs.getLong("id"), rs.getString("display_name"),
                rs.getBoolean("has_avatar")), userId);
    }

    @Transactional
    public Profile update(long userId, String displayName, MultipartFile file) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 32
                || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 1–32 个字符的昵称");
        }
        if (file == null) {
            db.update("UPDATE user_profile SET display_name=? WHERE id=?", name, userId);
        } else {
            Avatar avatar = readAvatar(file);
            db.update("UPDATE user_profile SET display_name=?, avatar_data=?, avatar_media_type=? WHERE id=?",
                name, avatar.data(), avatar.mediaType(), userId);
        }
        return get(userId);
    }

    public Avatar avatar(long userId) {
        Avatar avatar = db.query("""
            SELECT avatar_data, avatar_media_type FROM user_profile
            WHERE id=? AND avatar_media_type IS NOT NULL
            """, rs -> rs.next() ? new Avatar(rs.getBytes(1), rs.getString(2)) : null, userId);
        if (avatar == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚未设置头像");
        }
        return avatar;
    }

    private static Avatar readAvatar(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_AVATAR_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像大小须在 512 KB 以内");
        }
        try {
            byte[] data = file.getBytes();
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    var reader = readers.next();
                    try {
                        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                        if (("png".equals(format) || "jpeg".equals(format))) {
                            reader.setInput(input, true, true);
                            if (reader.getWidth(0) > MAX_AVATAR_DIMENSION || reader.getHeight(0) > MAX_AVATAR_DIMENSION) {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像尺寸须在 2048 × 2048 像素以内");
                            }
                            reader.read(0);
                            return new Avatar(data, "image/" + format);
                        }
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取头像图片");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 JPEG 或 PNG 头像");
    }
}
