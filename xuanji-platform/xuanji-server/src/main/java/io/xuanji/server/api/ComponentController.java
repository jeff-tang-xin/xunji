package io.xuanji.server.api;

import io.xuanji.core.registry.ComponentRegistry;
import io.xuanji.core.registry.RegisteredComponent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 画布面板数据源：已注册节点清单（前端 01 页「组件面板」） */
@RestController
@RequestMapping("/api/components")
public class ComponentController {

    @Autowired
    private ComponentRegistry registry;

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.listEnabled().stream().map(this::toView).toList();
    }

    private Map<String, Object> toView(RegisteredComponent c) {
        var m = c.metadata();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("nodeId", m.id());
        view.put("name", m.name());
        view.put("kind", m.kind().name());
        view.put("category", m.category());
        view.put("description", m.description());
        view.put("inputSchema", m.inputSchema() == null ? null : m.inputSchema().document());
        view.put("outputSchema", m.outputSchema() == null ? null : m.outputSchema().document());
        view.put("paramSchema", m.paramSchema() == null ? null : m.paramSchema().document());
        view.put("capabilities", m.capabilities().stream().map(Enum::name).toList());
        view.put("pluginModule", c.pluginModule());
        return view;
    }
}
