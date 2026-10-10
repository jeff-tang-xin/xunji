package io.xuanji.core.runtime;

/**
 * 解析后的技能内容（Anthropic Agent Skills 形态的平台落地）。
 *
 * <p>Skill 是「知识/方法论/操作指引包」，区别于 Tool（可调用函数）与 MCP（外部协议工具）：
 * 它不产生一次函数调用，而是把指令注入 Agent 的 system prompt，让 Agent 按技能指引行事。</p>
 *
 * <p>由 {@code skill_repo} 资产解析而来：{@code name}/{@code description} 对应 SKILL.md 的
 * frontmatter，{@code content} 对应 SKILL.md 正文（渐进式披露的 P0 简化——引用即整体注入）。</p>
 *
 * @param name        技能名（SKILL.md frontmatter.name）
 * @param description 一句话说明（何时使用，frontmatter.description）
 * @param content     技能正文（SKILL.md body，markdown 操作指引）
 */
public record SkillDef(String name, String description, String content) {
}