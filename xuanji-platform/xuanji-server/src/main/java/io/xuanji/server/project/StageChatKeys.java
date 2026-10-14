package io.xuanji.server.project;

/**
 * 阶段会话键生成（唯一出处）：会话粒度 = 项目 + 阶段 + 迭代。
 * 此前该格式在 ProjectChatService 与 StageOrchestrator 各写一份字面量，改一处漏一处即历史断裂。
 */
public final class StageChatKeys {

    private StageChatKeys() {
    }

    /** 阶段会话键：proj-{wsId}-stage-{stage}-{iteration} */
    public static String stageSession(String wsId, String stage, int iteration) {
        return "proj-" + wsId + "-stage-" + stage + "-" + iteration;
    }

    /** 阶段会话键前缀（按项目扫全部阶段会话用） */
    public static String stagePrefix(String wsId) {
        return "proj-" + wsId + "-stage-";
    }
}
