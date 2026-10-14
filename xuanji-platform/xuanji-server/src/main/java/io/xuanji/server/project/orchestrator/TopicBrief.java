package io.xuanji.server.project.orchestrator;

/**
 * 议题包——goal 传达的统一载体（同包不同角色）。
 *
 * <p>每个参与者（任务执行者或讨论发言者）收到的 prompt 都由本对象渲染：
 * 项目 Goal + 阶段 Goal + 本次议题 + 黑板摘要（已完成产出）+ 讨论历史（如有）。
 * goal 统一、视角分工——角色差异只来自 agent 的角色基座 system prompt。</p>
 *
 * <p>不可变值对象；渲染格式稳定，便于留档比对。</p>
 */
public record TopicBrief(
        String projectGoal,
        String stageGoal,
        String topic,
        String boardDigest,
        String history) {

    public TopicBrief {
        projectGoal = projectGoal == null ? "" : projectGoal;
        stageGoal = stageGoal == null ? "" : stageGoal;
        topic = topic == null ? "" : topic;
        boardDigest = boardDigest == null ? "" : boardDigest;
        history = history == null ? "" : history;
    }

    /** 无历史的任务包。 */
    public static TopicBrief task(String projectGoal, String stageGoal, String boardDigest) {
        return new TopicBrief(projectGoal, stageGoal, "", boardDigest, "");
    }

    /** 带议题、无历史的包（讨论发起/任务派发）。 */
    public static TopicBrief of(String projectGoal, String stageGoal, String topic, String boardDigest) {
        return new TopicBrief(projectGoal, stageGoal, topic, boardDigest, "");
    }

    /** 渲染为完整输入文本。 */
    public String render(String stepName, String instruction) {
        StringBuilder sb = new StringBuilder();
        sb.append("【项目目标】").append(projectGoal).append("\n");
        sb.append("【阶段目标】").append(stageGoal).append("\n");
        if (!topic.isBlank()) {
            sb.append("【本次议题】").append(topic).append("\n");
        }
        if (!boardDigest.isBlank()) {
            sb.append("【已完成工作】\n").append(boardDigest).append("\n");
        }
        if (!history.isBlank()) {
            sb.append("【讨论历史】\n").append(history).append("\n");
        }
        sb.append("【你的任务】").append(stepName).append("\n");
        if (instruction != null && !instruction.isBlank()) {
            sb.append("【任务说明】\n").append(instruction).append("\n");
        }
        return sb.toString();
    }
}
