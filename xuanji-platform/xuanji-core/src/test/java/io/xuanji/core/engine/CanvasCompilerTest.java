package io.xuanji.core.engine;

import io.xuanji.core.api.NodeKind;
import io.xuanji.core.definition.EdgeDef;
import io.xuanji.core.definition.NodeDef;
import io.xuanji.core.definition.TriggerType;
import io.xuanji.core.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 画布解析 + EL 编译的最小闭环验证（P0 线性链） */
class CanvasCompilerTest {

    private static final String CANVAS = """
            {
              "definitionVersion": 1,
              "meta": {"name": "demo-flow", "description": "三节点标准流程"},
              "triggers": [{"type": "CHAT_INPUT", "config": {}}],
              "nodes": [
                {"id": "start", "kind": "START", "name": "开始",
                 "outputSchema": {"type": "object", "properties": {"content": {"type": "string"}}}},
                {"id": "check", "refId": "basic.validate", "name": "校验",
                 "params": {"rules": ["not_blank"]}},
                {"id": "notify", "refId": "basic.notify", "name": "通知",
                 "capabilityBinding": {"skills": ["skill:report-skill@2"], "mcps": ["mcp:erp-mcp@1"]}},
                {"id": "end", "kind": "END", "name": "结束"}
              ],
              "edges": [
                {"from": "start", "to": "check", "type": "SEQ",
                 "mapping": [{"from": "$.start.output", "to": "$.input"}]},
                {"from": "check", "to": "notify", "type": "SEQ",
                 "mapping": [{"from": "$.check.output.valid", "to": "$.input.valid"}]},
                {"from": "notify", "to": "end", "type": "SEQ",
                 "mapping": [{"from": "$.notify.output", "to": "$.input"}]}
              ]
            }
            """;

    @Test
    void parseFullCanvas() {
        WorkflowDefinition def = CanvasJsonParser.parse(CANVAS);
        assertEquals("demo-flow", def.name());
        assertEquals(1, def.version());
        assertEquals(TriggerType.CHAT_INPUT, def.triggers().get(0).type());
        assertEquals(4, def.nodes().size());

        NodeDef start = def.node("start");
        assertTrue(start.isStart());
        assertEquals(NodeKind.START, start.kind());

        NodeDef notify = def.node("notify");
        assertEquals("basic.notify", notify.refId());
        assertNotNull(notify.capabilityBinding());
        assertEquals(1, notify.capabilityBinding().skills().size());
        assertEquals("report-skill", notify.capabilityBinding().skills().get(0).id());
        assertEquals(2, notify.capabilityBinding().skills().get(0).version());
        assertEquals("erp-mcp", notify.capabilityBinding().mcps().get(0).id());

        assertEquals(3, def.edges().size());
        EdgeDef e = def.edges().get(1);
        assertEquals("$.check.output.valid", e.mapping().get(0).from());
    }

    @Test
    void compileLinearChain() {
        WorkflowDefinition def = CanvasJsonParser.parse(CANVAS);
        String el = new LiteFlowRuleCompiler("node_dispatch").compile(def);
        assertEquals("THEN(node_dispatch.tag(\"start\"), node_dispatch.tag(\"check\"), "
                + "node_dispatch.tag(\"notify\"), node_dispatch.tag(\"end\"))", el);
    }

    @Test
    void rejectMissingStart() {
        String noStart = """
                {"definitionVersion":1, "meta":{"name":"x"},
                 "nodes":[{"id":"a","refId":"basic.validate"},{"id":"end","kind":"END"}],
                 "edges":[{"from":"a","to":"end","type":"SEQ"}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> new LiteFlowRuleCompiler("c").compile(
                CanvasJsonParser.parse(noStart)));
    }

    @Test
    void rejectCondEdgeInP0() {
        String cond = """
                {"definitionVersion":1, "meta":{"name":"x"},
                 "nodes":[{"id":"start","kind":"START"},{"id":"a","refId":"basic.validate"},{"id":"end","kind":"END"}],
                 "edges":[{"from":"start","to":"a","type":"SEQ"},{"from":"a","to":"end","type":"COND"}]}
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new LiteFlowRuleCompiler("c").compile(CanvasJsonParser.parse(cond)));
        assertTrue(ex.getMessage().contains("P1"));
    }

    @Test
    void rejectMultipleOutgoing() {
        String branch = """
                {"definitionVersion":1, "meta":{"name":"x"},
                 "nodes":[{"id":"start","kind":"START"},{"id":"a","refId":"basic.validate"},
                          {"id":"b","refId":"basic.notify"},{"id":"end","kind":"END"}],
                 "edges":[{"from":"start","to":"a","type":"SEQ"},{"from":"a","to":"b","type":"SEQ"},
                          {"from":"a","to":"end","type":"SEQ"},{"from":"b","to":"end","type":"SEQ"}]}
                """;
        assertThrows(IllegalArgumentException.class, () -> new LiteFlowRuleCompiler("c").compile(
                CanvasJsonParser.parse(branch)));
    }

    @Test
    void rejectCycle() {
        String cycle = """
                {"definitionVersion":1, "meta":{"name":"x"},
                 "nodes":[{"id":"start","kind":"START"},{"id":"a","refId":"basic.validate"},{"id":"end","kind":"END"}],
                 "edges":[{"from":"start","to":"a","type":"SEQ"},{"from":"a","to":"start","type":"SEQ"},
                          {"from":"a","to":"end","type":"SEQ"}]}
                """;
        // a 有两条入边（start 与 a 自身），先触发多入边校验——环检测在链行走期
        assertThrows(IllegalArgumentException.class, () -> new LiteFlowRuleCompiler("c").compile(
                CanvasJsonParser.parse(cycle)));
    }
}
