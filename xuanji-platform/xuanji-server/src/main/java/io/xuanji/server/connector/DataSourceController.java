package io.xuanji.server.connector;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源配置端点（JDBC 数据源——连接器查询形态的配置管理）。
 * 只做 HTTP 映射，业务在 {@link DataSourceService}（C4 分层）。
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(body);
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body) {
        return service.test(body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        service.delete(id);
        return Map.of("deleted", id);
    }
}