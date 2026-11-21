package com.campusfood.admin.service;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HomeBannerService {
    private static final int MAX_IMAGE_BYTES = 768 * 1024;
    private static final long MAX_IMAGE_PIXELS = 8_000_000;
    private static final Set<String> TAB_PAGES = Set.of("/pages/home/index", "/pages/orders/index", "/pages/mine/index");
    private static final Set<String> PAGES = Set.of(
            "/pages/home/index", "/pages/store/index", "/pages/cart/index", "/pages/confirm/index",
            "/pages/payment/index", "/pages/payment-result/index", "/pages/orders/index", "/pages/order-detail/index",
            "/pages/map/index", "/pages/refund/index", "/pages/aftersale/index", "/pages/addresses/index",
            "/pages/address-edit/index", "/pages/mine/index");

    private final JdbcTemplate db;

    public HomeBannerService(JdbcTemplate db) {
        this.db = db;
    }

    public record Banner(long id, String name, String linkType, String linkUrl, int sortOrder, String imageVersion) {}
    public record Picture(byte[] bytes, String mediaType) {}

    public List<Banner> list() {
        return db.query("""
            SELECT id,name,link_type,link_url,sort_order,image_version
            FROM home_banner ORDER BY sort_order DESC,id DESC
            """, (row, index) -> new Banner(row.getLong(1), row.getString(2), row.getString(3), row.getString(4),
                    row.getInt(5), row.getString(6)));
    }

    public Picture image(long id) {
        return db.query("SELECT image_data,media_type FROM home_banner WHERE id=?", row -> {
            if (!row.next()) throw missing();
            return new Picture(row.getBytes(1), row.getString(2));
        }, id);
    }

    public void save(Long id, String name, String linkType, String linkUrl, int sortOrder, MultipartFile file) {
        String title = name.trim();
        if (title.isEmpty() || title.length() > 80) throw invalid("请输入 1 至 80 字的轮播图名称");
        String destination = validateDestination(linkType, linkUrl);
        if (file == null) {
            if (id == null) throw invalid("请上传轮播图片");
            requireChanged(db.update("""
                UPDATE home_banner SET name=?,link_type=?,link_url=?,sort_order=? WHERE id=?
                """, title, linkType, destination, sortOrder, id));
            return;
        }
        Picture image = readImage(file);
        String revision = UUID.randomUUID().toString();
        if (id == null) {
            db.update("""
                INSERT INTO home_banner(name,link_type,link_url,sort_order,image_data,media_type,image_version)
                VALUES(?,?,?,?,?,?,?)
                """, title, linkType, destination, sortOrder, image.bytes(), image.mediaType(), revision);
        } else {
            requireChanged(db.update("""
                UPDATE home_banner SET name=?,link_type=?,link_url=?,sort_order=?,image_data=?,media_type=?,image_version=?
                WHERE id=?
                """, title, linkType, destination, sortOrder, image.bytes(), image.mediaType(), revision, id));
        }
    }

    public void delete(long id) {
        requireChanged(db.update("DELETE FROM home_banner WHERE id=?", id));
    }

    static String validateDestination(String type, String url) {
        String destination = url.trim();
        if (destination.isEmpty() || destination.length() > 2048
                || destination.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '\\')) {
            throw invalid("请输入有效的跳转地址");
        }
        switch (type) {
            case "page":
                if (!destination.startsWith("/")) destination = "/" + destination;
                int query = destination.indexOf('?');
                String path = query < 0 ? destination : destination.substring(0, query);
                if (!PAGES.contains(path) || destination.indexOf('#') >= 0) {
                    throw invalid("请选择已注册的用户端小程序页面");
                }
                if (query >= 0 && TAB_PAGES.contains(path)) {
                    throw invalid("底部导航页面不能携带跳转参数");
                }
                return destination;
            case "external":
                try {
                    URI uri = URI.create(destination);
                    if ("https".equals(uri.getScheme()) && uri.getHost() != null
                            && uri.getHost().matches("[A-Za-z0-9.-]+") && uri.getRawUserInfo() == null) {
                        return destination;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 地址格式错误，统一返回可直接在表单中显示的提示。
                }
                throw invalid("站外链接必须是有效的 HTTPS 地址");
            default:
                throw invalid("请选择有效的跳转方式");
        }
    }

    private static Picture readImage(MultipartFile file) {
        if (file.isEmpty() || file.getSize() > MAX_IMAGE_BYTES) {
            throw invalid("请上传不超过 768 KB 的 JPG 或 PNG 图片");
        }
        try (var source = new MemoryCacheImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(source);
            if (!readers.hasNext()) throw invalid("请上传有效的 JPG 或 PNG 图片");
            var reader = readers.next();
            try {
                String mediaType = switch (reader.getFormatName().toLowerCase(java.util.Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg" -> "image/jpeg";
                    default -> throw invalid("仅支持 JPG 和 PNG 图片");
                };
                reader.setInput(source);
                // 先读尺寸再解码，避免小文件声明异常大的图像。
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw invalid("图片尺寸过大，请缩小后上传");
                }
                reader.read(0);
                return new Picture(file.getBytes(), mediaType);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException error) {
            throw invalid("图片无法读取，请重新选择 JPG 或 PNG 图片");
        }
    }

    private static void requireChanged(int count) {
        if (count == 0) throw missing();
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "轮播图不存在");
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
