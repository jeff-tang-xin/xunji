package io.xuanji.server.project.orchestrator;

import io.xuanji.server.repository.ProjectRepository;
import io.xuanji.server.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 阶段文档双落（文件系统 + 数据库索引）。
 *
 * <p><b>双落契约</b>：讨论纪要、求助问答、任务产出等过程文档，一份落项目目录
 * （{@code {root_path}/docs/{stage}/iter{n}/{seq}-{kind}-{title}.md}，按阶段/轮次分目录，
 * 项目文件树直接可见），一份落 {@code project_artifact} 索引（file_path 指向相对路径，
 * content_md 存全文，交付物 tab 可见可检索）。</p>
 *
 * <p><b>失败不阻塞</b>：文档落盘是留档增强，写失败只记日志——不能让一场讨论/一次求助
 * 因为磁盘或索引问题被判 FAILED。</p>
 */
@Component
public class StageDocStore {

    private static final Logger log = LoggerFactory.getLogger(StageDocStore.class);

    /** 文件名非法字符（Windows/Unix 通用）与空白。 */
    private static final Pattern UNSAFE = Pattern.compile("[\\\\/:*?\"<>|\\s]+");

    private final WorkspaceRepository workspaceRepo;
    private final ProjectRepository projectRepo;

    public StageDocStore(WorkspaceRepository workspaceRepo, ProjectRepository projectRepo) {
        this.workspaceRepo = workspaceRepo;
        this.projectRepo = projectRepo;
    }

    /**
     * 只落文件（不插索引）：TASK 产出等已有 DELIVERABLE 索引的文档用——
     * 文件落盘后把相对路径填进既有索引的 file_path，避免同一产出两条索引。
     *
     * @return 相对路径；失败返回 null（不抛）
     */
    public String writeFile(String wsId, String stage, int iteration, String kind,
                            int seq, String title, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            String rootPath = String.valueOf(workspaceRepo.findById(wsId).stream().findFirst()
                    .map(w -> w.get("root_path")).orElse(""));
            if (rootPath.isBlank()) {
                log.warn("文档落盘跳过（无 root_path）: ws={}", wsId);
                return null;
            }
            Path root = Paths.get(rootPath).normalize();
            String fileName = seq + "-" + kind + "-" + safeName(title) + ".md";
            // 按类型分目录（2026-10-19 用户反馈：iter 目录里 CHATLOG 与 TASK/DISCUSS 混排难找）——
            // chatlog/task/discuss/decision/consult 各归其位；存量文件路径已落库不受影响
            String rel = "docs/" + stage + "/iter" + iteration + "/" + kind.toLowerCase() + "/" + fileName;
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) {
                log.warn("文档落盘拒绝（路径越界）: ws={} rel={}", wsId, rel);
                return null;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("文档落盘: ws={} {} ({} 字)", wsId, rel, content.length());
            return rel;
        } catch (Exception e) {
            log.warn("文档落盘失败（不阻塞主流程）: ws={} {}/iter{} {} -> {}",
                    wsId, stage, iteration, title, e.getMessage());
            return null;
        }
    }

    /**
     * 双落一份文档（文件 + project_artifact DOC 索引）：讨论纪要、求助问答等
     * 没有既有索引的过程文档用。
     *
     * @param kind      文档类型（DISCUSS/CONSULT，与 stepType 对齐）
     * @param seq       步骤序号（文件名排序用）
     * @param title     文档标题（步骤名，会清洗为安全文件名）
     * @param content   全文（markdown）
     * @param createdBy 产出者（agent 名）
     * @return 相对路径（落库 file_path）；失败返回 null（不抛）
     */
    public String write(String wsId, String stage, int iteration, String kind,
                        int seq, String title, String content, String createdBy) {
        String rel = writeFile(wsId, stage, iteration, kind, seq, title, content);
        if (rel == null) {
            return null;
        }
        try {
            String fileName = rel.substring(rel.lastIndexOf('/') + 1);
            projectRepo.insertArtifactWithStage(wsId, "doc-" + seq, "DOC", fileName, "DOC",
                    rel, content, 1, "ACTIVE", createdBy, stage);
            return rel;
        } catch (Exception e) {
            log.warn("文档索引落库失败（文件已落盘）: ws={} {} -> {}", wsId, rel, e.getMessage());
            return rel;
        }
    }

    /** 标题 → 安全文件名段：非法字符与空白转连字符，限 60 字。 */
    static String safeName(String title) {
        String s = UNSAFE.matcher(title == null ? "" : title.trim()).replaceAll("-");
        while (s.startsWith("-")) {
            s = s.substring(1);
        }
        while (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "untitled" : s.substring(0, Math.min(s.length(), 60));
    }
}
