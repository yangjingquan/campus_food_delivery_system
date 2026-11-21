package com.campusfood.user;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/user/v1/home-banners")
public class HomeBannerController {
    private final JdbcTemplate db;

    public HomeBannerController(JdbcTemplate db) {
        this.db = db;
    }

    public record Banner(long id, String name, String linkType, String linkUrl, String imagePath) {}
    private record Picture(byte[] bytes, String mediaType) {}

    @GetMapping
    public Map<String, List<Banner>> list() {
        return Map.of("rows", db.query("""
                SELECT id,name,link_type,link_url,image_version
                FROM home_banner ORDER BY sort_order DESC,id DESC
                """, (row, index) -> {
            long id = row.getLong(1);
            return new Banner(id, row.getString(2), row.getString(3), row.getString(4),
                    "/home-banners/" + id + "/image?v=" + row.getString(5));
        }));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable long id) {
        var image = db.query("SELECT image_data,media_type FROM home_banner WHERE id=?", row -> {
            if (!row.next()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "轮播图不存在");
            return new Picture(row.getBytes(1), row.getString(2));
        }, id);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(image.mediaType())).body(image.bytes());
    }
}
