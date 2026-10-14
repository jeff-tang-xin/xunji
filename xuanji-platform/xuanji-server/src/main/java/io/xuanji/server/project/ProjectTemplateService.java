package io.xuanji.server.project;

import static io.xuanji.server.project.ProjectSupport.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 项目模板（V20 project_template + V30 stages_json 阶段预设）。
 *
 * <p>从 ProjectService 拆出（2026-10-14 结构治理）：模板 CRUD、节点链校验（历史兼容）、
 * 3 阶段执行路径预设归一化与应用。applyTemplateStages / requireTemplate 为包私有，
 * 供 ProjectService（create / openIteration）同包调用。</p>
 */
@Service
public class ProjectTemplateService {
    private static final Logger log = LoggerFactory.getLogger(ProjectTemplateService.class);

    private final io.xuanji.server.repository.ProjectRepository repo;
    private final ObjectMapper objectMapper;

    public ProjectTemplateService(io.xuanji.server.repository.ProjectRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> templates() {
        List<Map<String, Object>> rows = this.repo.findTemplates();
        for (Map<String, Object> r : rows) {
            r.put("stages", parseJsonMap(objectMapper, r.get("stages_json")));
        }
        return rows;
    }
    public Map<String, Object> templateDetail(String code) {
        Map<String, Object> tpl = requireTemplate(code);
        long templateId = ((Number)tpl.get("id")).longValue();
        ArrayList nodes = new ArrayList();
        for (Map<String, Object> tn : this.repo.findTemplateNodes(templateId)) {
            LinkedHashMap<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("type", tn.get("type"));
            n.put("seq", tn.get("seq"));
            n.put("owner_role", tn.get("owner_role"));
            n.put("agent_name", tn.get("agent_name"));
            n.put("workflow_def_id", tn.get("workflow_def_id"));
            n.put("standards", parseStringList(objectMapper, tn.get("standards")));
            n.put("requirements", parseStringList(objectMapper, tn.get("requirements")));
            n.put("methodology", tn.get("methodology"));
            n.put("deliverable", tn.get("deliverable"));
            n.put("depends_on", parseIntList(objectMapper, tn.get("depends_on")));
            n.put("preconditions", parseStringList(objectMapper, tn.get("preconditions")));
            nodes.add(n);
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", tpl.get("id"));
        result.put("code", tpl.get("code"));
        result.put("name", tpl.get("name"));
        result.put("description", tpl.get("description"));
        result.put("builtin", tpl.get("builtin"));
        result.put("stages", parseJsonMap(objectMapper, tpl.get("stages_json")));
        result.put("nodes", nodes);
        return result;
    }
    @Transactional
    public Map<String, Object> createTemplate(Map<String, Object> body) {
        String code = requireText(body.get("code"), "code");
        if (!TEMPLATE_CODE.matcher(code).matches()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "code 只能由小写字母/数字/下划线/连字符组成且长度 1-32（当前: " + code + "）");
        }
        if (!this.repo.findTemplateByCode(code).isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "code 已被占用: " + code);
        }
        String name = limited(requireText(body.get("name"), "name"), 128, "name");
        String description = limited(blankToNull(body.get("description")), 512, "description");
        List<Map<String, Object>> nodes = normalizeTemplateNodes(body.get("nodes"));
        String stagesJson = normalizeTemplateStages(body.get("stages"));
        long templateId = this.repo.insertTemplate(code, name, description, stagesJson);
        insertTemplateNodes(templateId, nodes);
        log.info("template created: code={} nodes={} stages={}", new Object[]{code, nodes.size(), stagesJson == null ? 0 : stagesJson.length()});
        return templateDetail(code);
    }
    @Transactional
    public Map<String, Object> updateTemplate(String code, Map<String, Object> body) {
        Map<String, Object> tpl = requireTemplate(code);
        String bodyCode = blankToNull(body.get("code"));
        if (bodyCode != null && !bodyCode.equals(code)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "code 不可修改（路径: " + code + "，body: " + bodyCode + "）");
        }
        String name = limited(requireText(body.get("name"), "name"), 128, "name");
        String description = limited(blankToNull(body.get("description")), 512, "description");
        List<Map<String, Object>> nodes = normalizeTemplateNodes(body.get("nodes"));
        String stagesJson = normalizeTemplateStages(body.get("stages"));
        long templateId = ((Number)tpl.get("id")).longValue();
        this.repo.updateTemplate(templateId, name, description, stagesJson);
        if (body.get("nodes") != null) {
            this.repo.deleteTemplateNodes(templateId);
            insertTemplateNodes(templateId, nodes);
        }
        return templateDetail(code);
    }
    @Transactional
    public Map<String, Object> deleteTemplate(String code) {
        Map<String, Object> tpl = requireTemplate(code);
        if (Boolean.TRUE.equals(tpl.get("builtin"))) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "内置模板不可删除: " + code + "（可复制其节点自建模板）");
        }
        this.repo.deleteTemplateByCode(code);
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("deleted", true);
        result.put("code", code);
        return result;
    }
    private String normalizeTemplateStages(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "stages 应为对象");
        }
        Map m = (Map)raw;
        LinkedHashMap out = new LinkedHashMap();
        for (String stage : List.of(STAGE_REQUIREMENT, "DEVELOP", STAGE_OPERATE)) {
            Object s = m.get(stage);
            if (s == null) continue;
            if (!(s instanceof Map)) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "stages." + stage + " 应为对象");
            }
            Map sm = (Map)s;
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            Object wf = sm.get("workflow");
            cap.put("workflow", wf == null ? "" : String.valueOf(wf).trim());
            for (String k : List.of("agents", "servers", "connectors")) {
                cap.put(k, strList(objectMapper, sm.get(k)));
            }
            out.put(stage, cap);
        }
        return out.isEmpty() ? null : toJson(objectMapper, out);
    }
    void applyTemplateStages(String wsId, String templateCode, String username) {
        Map tpl = this.repo.findTemplateByCode(templateCode).stream().findFirst().orElse(null);
        if (tpl == null || tpl.get("stages_json") == null) {
            return;
        }
        Map<String, Object> stages = parseJsonMap(objectMapper, tpl.get("stages_json"));
        String note = "来自模板 " + templateCode;
        for (String stage : List.of(STAGE_REQUIREMENT, "DEVELOP", STAGE_OPERATE)) {
            Map sm;
            Object s = stages.get(stage);
            if (!(s instanceof Map) || (sm = (Map)s).isEmpty()) continue;
            LinkedHashMap<String, Object> cap = new LinkedHashMap<String, Object>();
            for (String k : CAPABILITY_KEYS) {
                if ("workflow".equals(k)) {
                    Object wf = sm.get("workflow");
                    cap.put(k, wf == null ? "" : String.valueOf(wf).trim());
                    continue;
                }
                cap.put(k, strList(objectMapper, sm.get(k)));
            }
            this.repo.upsertStageCapability(wsId, stage, toJson(objectMapper, cap), note, username);
        }
        log.info("template stages applied: ws={} template={}", (Object)wsId, (Object)templateCode);
    }
    Map<String, Object> requireTemplate(String code) {
        return (Map)this.repo.findTemplateByCode(code).stream().findFirst().orElseThrow(() -> new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "模板不存在: " + code));
    }
    private List<Map<String, Object>> normalizeTemplateNodes(Object raw) {
        List list;
        if (raw == null) {
            return new ArrayList<Map<String, Object>>();
        }
        if (!(raw instanceof List) || (list = (List)raw).isEmpty()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "nodes 不能为空（模板至少包含一个节点，或改用 stages 阶段预设）");
        }
        ArrayList<Map<String, Object>> nodes = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); ++i) {
            Object e = list.get(i);
            if (!(e instanceof Map)) {
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "nodes[" + i + "] 必须是对象");
            }
            Map m = (Map)e;
            Map<String, Object> e2 = asStringMap(m);
            String label = "nodes[" + i + "]";
            String type = limited(requireText(e2.get("type"), label + ".type"), 24, label + ".type");
            String ownerRole = limited(requireText(e2.get("owner_role"), label + ".owner_role"), 32, label + ".owner_role");
            int seq = requireSeq(e2.get("seq"), label + ".seq");
            String deliverable = limited(blankToNull(e2.get("deliverable")), 256, label + ".deliverable");
            String agentName = limited(blankToNull(e2.get("agent_name")), 64, label + ".agent_name");
            Long workflowDefId = optionalLong(e2.get("workflow_def_id"), label + ".workflow_def_id");
            String stage = resolveStage(e2.get("stage"), type);
            LinkedHashMap<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("type", type);
            n.put("seq", seq);
            n.put("owner_role", ownerRole);
            n.put("agent_name", agentName);
            n.put("workflow_def_id", workflowDefId);
            n.put("stage", stage);
            n.put("standards", requireStringList(e2.get("standards"), label + ".standards"));
            n.put("requirements", requireStringList(e2.get("requirements"), label + ".requirements"));
            n.put("methodology", blankToNull(e2.get("methodology")));
            n.put("deliverable", deliverable);
            n.put("depends_on", requireIntList(e2.get("depends_on"), label + ".depends_on"));
            n.put("preconditions", requireStringList(e2.get("preconditions"), label + ".preconditions"));
            nodes.add(n);
        }
        HashSet<Integer> seqs = new HashSet<Integer>();
        for (Map map : nodes) {
            int seq = (Integer)map.get("seq");
            if (seqs.add(seq)) continue;
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "seq 重复: " + seq);
        }
        for (int expected = 1; expected <= nodes.size(); ++expected) {
            if (seqs.contains(expected)) continue;
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "seq 必须从 1 起连续，缺少 seq=" + expected + "（当前共 " + nodes.size() + " 个节点）");
        }
        for (Map map : nodes) {
            int seq = (Integer)map.get("seq");
            for (Object depObj : (List)map.get("depends_on")) {
                int dep = (Integer)depObj;
                if (!seqs.contains(dep)) {
                    throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "节点 seq=" + seq + " 的 depends_on 引用了不存在的 seq=" + dep);
                }
                if (dep < seq) continue;
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "节点 seq=" + seq + " 的 depends_on 必须小于自身 seq（引用 seq=" + dep + " 会成环或自依赖）");
            }
        }
        return nodes;
    }
    private void insertTemplateNodes(long templateId, List<Map<String, Object>> nodes) {
        for (Map<String, Object> n : nodes) {
            this.repo.insertTemplateNode(templateId, (String)n.get("type"), (Integer)n.get("seq"), toJson(objectMapper, n.get("standards")), toJson(objectMapper, n.get("requirements")), (String)n.get("methodology"), (String)n.get("deliverable"), toJson(objectMapper, n.get("depends_on")), toJson(objectMapper, n.get("preconditions")), (String)n.get("owner_role"), (String)n.get("agent_name"), (Long)n.get("workflow_def_id"), (String)n.get("stage"));
        }
    }
}
