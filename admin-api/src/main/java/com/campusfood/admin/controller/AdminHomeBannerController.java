package com.campusfood.admin.controller;

import java.util.List;

import com.campusfood.admin.service.AdminService;
import com.campusfood.admin.service.HomeBannerService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/v1/home-banners")
public class AdminHomeBannerController {
    private final AdminService admins;
    private final HomeBannerService banners;

    public AdminHomeBannerController(AdminService admins, HomeBannerService banners) {
        this.admins = admins;
        this.banners = banners;
    }

    @GetMapping
    public List<HomeBannerService.Banner> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        admins.requirePermission(auth, "home-banner.view");
        return banners.list();
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        admins.requirePermission(auth, "home-banner.view");
        var image = banners.image(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(image.mediaType())).body(image.bytes());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> create(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam String name, @RequestParam String linkType, @RequestParam String linkUrl,
            @RequestParam int sortOrder, @RequestParam MultipartFile image) {
        return save(auth, null, name, linkType, linkUrl, sortOrder, image);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> update(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id, @RequestParam String name, @RequestParam String linkType,
            @RequestParam String linkUrl, @RequestParam int sortOrder,
            @RequestParam(required = false) MultipartFile image) {
        return save(auth, id, name, linkType, linkUrl, sortOrder, image);
    }

    private ResponseEntity<Void> save(String auth, Long id, String name, String linkType, String linkUrl,
            int sortOrder, MultipartFile image) {
        admins.requirePermission(auth, "home-banner.edit");
        banners.save(id, name, linkType, linkUrl, sortOrder, image);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        admins.requirePermission(auth, "home-banner.edit");
        banners.delete(id);
        return ResponseEntity.noContent().build();
    }
}
