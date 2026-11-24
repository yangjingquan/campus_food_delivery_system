package com.campusfood.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** 校验分页排序请求，并仅替换完整列表中原页面对应的区间。 */
public record DisplayOrder(List<String> previous, List<String> requested) {
    public static DisplayOrder from(Object previous, Object requested, int maxIdLength) {
        List<String> before = ids(previous, maxIdLength);
        List<String> after = ids(requested, maxIdLength);
        if (!new HashSet<>(before).equals(new HashSet<>(after))) {
            throw new IllegalArgumentException("排序前后的记录必须一致");
        }
        return new DisplayOrder(before, after);
    }

    /** 返回空值表示该页顺序已变化，客户端需刷新。 */
    public List<String> applyTo(List<String> current) {
        if (Collections.indexOfSubList(current, requested) >= 0) return current;
        int offset = Collections.indexOfSubList(current, previous);
        if (offset < 0) return null;
        var result = new ArrayList<>(current);
        for (int i = 0; i < requested.size(); i++) {
            result.set(offset + i, requested.get(i));
        }
        return result;
    }

    private static List<String> ids(Object value, int maxIdLength) {
        if (!(value instanceof List<?> list) || list.size() < 2 || list.size() > 100) {
            throw new IllegalArgumentException("请选择当前页的 2 至 100 条记录排序");
        }
        var unique = new HashSet<String>();
        var ids = new ArrayList<String>(list.size());
        for (Object entry : list) {
            if (!(entry instanceof String id) || id.isBlank() || id.length() > maxIdLength || !unique.add(id)) {
                throw new IllegalArgumentException("排序列表包含无效或重复的编号");
            }
            ids.add(id);
        }
        return ids;
    }
}
