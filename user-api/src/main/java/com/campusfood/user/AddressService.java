package com.campusfood.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AddressService {
    private final JdbcTemplate db;
    public AddressService(JdbcTemplate db) { this.db = db; }

    public List<Map<String, Object>> list(long userId) {
        return db.queryForList("SELECT id,receiver_name AS name,phone,building,room,is_default AS isDefault FROM user_address WHERE user_id=? ORDER BY is_default DESC,updated_at DESC", userId);
    }

    @Transactional
    public Map<String, Object> create(long userId, Map<String, Object> body) {
        Address address = validate(body);
        String id = UUID.randomUUID().toString();
        boolean makeDefault = address.makeDefault() || count(userId) == 0;
        if (makeDefault) db.update("UPDATE user_address SET is_default=FALSE WHERE user_id=?", userId);
        db.update("INSERT INTO user_address(id,user_id,receiver_name,phone,building,room,is_default) VALUES(?,?,?,?,?,?,?)", id, userId, address.name(), address.phone(), address.building(), address.room(), makeDefault);
        return byId(userId, id);
    }

    @Transactional
    public Map<String, Object> update(long userId, String id, Map<String, Object> body) {
        require(userId, id);
        Address address = validate(body);
        boolean makeDefault = address.makeDefault();
        if (makeDefault) db.update("UPDATE user_address SET is_default=FALSE WHERE user_id=?", userId);
        db.update("UPDATE user_address SET receiver_name=?,phone=?,building=?,room=?,is_default=? WHERE id=? AND user_id=?", address.name(), address.phone(), address.building(), address.room(), makeDefault, id, userId);
        return byId(userId, id);
    }

    @Transactional
    public void delete(long userId, String id) {
        Map<String, Object> row = require(userId, id);
        db.update("DELETE FROM user_address WHERE id=? AND user_id=?", id, userId);
        if (Boolean.TRUE.equals(row.get("is_default"))) {
            db.update("UPDATE user_address SET is_default=TRUE WHERE id=(SELECT id FROM (SELECT id FROM user_address WHERE user_id=? ORDER BY updated_at DESC LIMIT 1) candidate)", userId);
        }
    }

    public Map<String, Object> require(long userId, String id) {
        var rows = db.queryForList("SELECT id,user_id,receiver_name AS name,phone,building,room,is_default AS isDefault FROM user_address WHERE id=? AND user_id=?", id, userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "收餐地址不存在");
        return rows.get(0);
    }

    private Map<String, Object> byId(long userId, String id) { return require(userId, id); }
    private int count(long userId) { Integer n = db.queryForObject("SELECT COUNT(*) FROM user_address WHERE user_id=?", Integer.class, userId); return n == null ? 0 : n; }

    private static Address validate(Map<String, Object> body) {
        String name = text(body, "name");
        String phone = text(body, "phone");
        String building = text(body, "building");
        String room = text(body, "room");
        if (name.isBlank() || name.length() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 1 至 20 个字的联系人");
        if (!phone.matches("1[3-9][0-9]{9}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的 11 位手机号");
        if (building.isBlank() || building.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择或填写宿舍楼");
        if (room.isBlank() || room.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写寝室门牌");
        return new Address(name, phone, building, room, Boolean.TRUE.equals(body.get("isDefault")));
    }

    private static String text(Map<String, Object> body, String key) { return body.get(key) == null ? "" : body.get(key).toString().trim(); }
    private record Address(String name, String phone, String building, String room, boolean makeDefault) {}
}
