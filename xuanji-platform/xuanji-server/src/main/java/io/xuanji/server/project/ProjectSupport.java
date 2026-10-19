package io.xuanji.server.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 项目域共享静态工具：参数校验、JSON 解析、阶段常量与阶段推导。
 * 从 ProjectService 拆出（2026-10-14 结构治理），供 ProjectService / ProjectTemplateService 复用；
 * JSON 类方法以 ObjectMapper 首参注入，避免静态持有 Spring bean。
 */
public final class ProjectSupport {

    private ProjectSupport() {
    }

    /** 模板 code 规则（与前端 TEMPLATE_CODE_RE 一致） */
    public static final Pattern TEMPLATE_CODE = Pattern.compile("[a-z0-9_-]{1,32}");
    public static final List<String> STAGE_ORDER = List.of("REQUIREMENT", "DEVELOP", "OPERATE");
    public static final Map<String, String> STAGE_LABEL = Map.of("REQUIREMENT", "需求", "DEVELOP", "开发", "OPERATE", "运维");
    public static final String STAGE_OPERATE = "OPERATE";
    public static final String STAGE_REQUIREMENT = "REQUIREMENT";
    public static final List<String> CAPABILITY_KEYS = List.of("tools", "skills", "mcps", "connectors", "artifacts", "urls", "agents", "workflow", "servers");
    public static final Map<String, String> TYPE_STAGE = Map.ofEntries(Map.entry("DISCUSS", "REQUIREMENT"), Map.entry("INITIATION", "REQUIREMENT"), Map.entry("PROJECT_DOC", "REQUIREMENT"), Map.entry("PRD", "REQUIREMENT"), Map.entry("PROTOTYPE", "REQUIREMENT"), Map.entry("UI_DESIGN", "REQUIREMENT"), Map.entry("ARCHITECTURE", "DEVELOP"), Map.entry("DESIGN", "DEVELOP"), Map.entry("TEST_CASE", "DEVELOP"), Map.entry("DEVELOP", "DEVELOP"), Map.entry("SMOKE", "DEVELOP"), Map.entry("SUBMIT", "DEVELOP"), Map.entry("TEST", "DEVELOP"), Map.entry("REVIEW", "DEVELOP"), Map.entry("RELEASE", "OPERATE"), Map.entry("DEPLOY", "OPERATE"), Map.entry("OPS", "OPERATE"), Map.entry("BUGFIX", "OPERATE"), Map.entry("MONITOR", "OPERATE"), Map.entry("DB", "OPERATE"), Map.entry("DATABASE", "OPERATE"));
    public static Long optionalLong(Object v, String label) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            Number num = (Number)v;
            long lv = num.longValue();
            if (lv <= 0L) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是正整数（当前: " + lv + "）");
            }
            return lv;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            long lv = Long.parseLong(s);
            if (lv <= 0L) {
                throw new NumberFormatException();
            }
            return lv;
        }
        catch (NumberFormatException ex) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是正整数（当前: " + s + "）");
        }
    }
    public static Map<String, Object> asStringMap(Map<?, ?> m) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }
    public static String requireText(Object v, String label) {
        String s = blankToNull(v);
        if (s == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " is required");
        }
        return s;
    }
    public static String limited(String value, int max, String label) {
        if (value != null && value.length() > max) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 超长（" + value.length() + " > " + max + "）");
        }
        return value;
    }
    public static int requireSeq(Object v, String label) {
        Integer seq = toInteger(v);
        if (seq == null || seq < 1) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是从 1 开始的整数（当前: " + String.valueOf(v) + "）");
        }
        return seq;
    }
    public static Integer toInteger(Object v) {
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return null;
    }
    public static List<String> requireStringList(Object v, String label) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是字符串数组");
        }
        List list = (List)v;
        ArrayList<String> result = new ArrayList<String>();
        for (Object o : list) {
            String s = blankToNull(o);
            if (s == null) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 含空项（第 " + (result.size() + 1) + " 项）");
            }
            result.add(s);
        }
        return result;
    }
    public static List<Integer> requireIntList(Object v, String label) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 必须是 seq 整数数组");
        }
        List list = (List)v;
        ArrayList<Integer> result = new ArrayList<Integer>();
        for (Object o : list) {
            Integer i = toInteger(o);
            if (i == null || i < 1) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, label + " 含非法 seq: " + String.valueOf(o));
            }
            result.add(i);
        }
        return result;
    }
    public static String resolveStage(Object rawStage, Object rawType) {
        String s;
        String string = s = rawStage == null ? "" : String.valueOf(rawStage).trim().toUpperCase();
        if (STAGE_ORDER.contains(s)) {
            return s;
        }
        String t = rawType == null ? "" : String.valueOf(rawType).trim().toUpperCase();
        return TYPE_STAGE.getOrDefault(t, "DEVELOP");
    }
    public static String stageLabel(String stage) {
        return STAGE_LABEL.getOrDefault(stage, stage);
    }
    public static Map<String, Object> parseScopes(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<?, ?> map = (Map<?, ?>)raw;
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }
    public static String blankToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
    public static List<String> mapSeqToIds(String wsId, List<Integer> seqs) {
        ArrayList<String> ids = new ArrayList<String>();
        for (Integer seq : seqs) {
            ids.add(depId(wsId, seq));
        }
        return ids;
    }
    public static List<Integer> parseIntList(ObjectMapper om, Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            Object parsed = om.readValue(json.toString(), Object.class);
            if (parsed instanceof List) {
                List list = (List)parsed;
                ArrayList<Integer> result = new ArrayList<Integer>();
                for (Object o : list) {
                    if (o instanceof Number) {
                        Number num = (Number)o;
                        result.add(num.intValue());
                        continue;
                    }
                    if (o == null) continue;
                    try {
                        result.add(Integer.parseInt(o.toString().trim()));
                    }
                    catch (NumberFormatException numberFormatException) {}
                }
                return result;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return List.of();
    }
    public static String depId(String wsId, int seq) {
        return wsId + "_n" + seq;
    }
    public static List<String> parseStringList(ObjectMapper om, Object json) {
        if (json == null) {
            return List.of();
        }
        try {
            Object parsed = om.readValue(json.toString(), Object.class);
            if (parsed instanceof List) {
                List list = (List)parsed;
                ArrayList<String> result = new ArrayList<String>();
                for (Object o : list) {
                    result.add(o == null ? null : o.toString());
                }
                return result;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return List.of();
    }
    public static String toJson(ObjectMapper om, Object value) {
        if (value == null) {
            return null;
        }
        try {
            return om.writeValueAsString(value);
        }
        catch (Exception e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "JSON 序列化失败");
        }
    }
    public static Integer parseVersion(Object v) {
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return null;
    }
    public static Map<String, Object> parseJsonMap(ObjectMapper om, Object raw) {
        if (raw == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (raw instanceof Map) {
            Map map = (Map)raw;
            return new LinkedHashMap<String, Object>(map);
        }
        String text = raw.toString().trim();
        if (text.isEmpty() || "null".equals(text)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = om.readValue(text, Object.class);
            if (parsed instanceof Map) {
                Map m = (Map)parsed;
                return new LinkedHashMap<String, Object>(m);
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return new LinkedHashMap<String, Object>();
    }
    public static List<Object> objList(ObjectMapper om, Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List) {
            List list = (List)raw;
            return list;
        }
        try {
            Object parsed = om.readValue(raw.toString(), Object.class);
            if (parsed instanceof List) {
                List list = (List)parsed;
                return list;
            }
        }
        catch (Exception exception) {
            // empty catch block
        }
        return List.of();
    }
    public static List<Map<String, Object>> mapList(ObjectMapper om, Object raw) {
        ArrayList<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object o : objList(om, raw)) {
            if (!(o instanceof Map)) continue;
            Map m = (Map)o;
            result.add(new LinkedHashMap(m));
        }
        return result;
    }
    public static List<String> strList(ObjectMapper om, Object raw) {
        ArrayList<String> result = new ArrayList<String>();
        for (Object o : objList(om, raw)) {
            result.add(o == null ? null : o.toString());
        }
        return result;
    }
    public static String strOr(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? def : s;
    }
    public static int intOr(Object v, int def) {
        if (v instanceof Number) {
            Number n = (Number)v;
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString().trim());
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        return def;
    }
}
