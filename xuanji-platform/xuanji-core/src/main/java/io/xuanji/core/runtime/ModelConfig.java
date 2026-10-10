package io.xuanji.core.runtime;

import java.util.Map;

/**
 * 模型配置（协议双轨，Q5 决议）：按协议而非厂商接入——OPENAI_PROTOCOL 覆盖
 * OpenAI 官方及一切兼容端点（DeepSeek/通义兼容模式/Moonshot/vLLM 等），
 * ANTHROPIC_PROTOCOL 覆盖 Anthropic 官方及兼容端点。新增供应商 = 新增一条配置。
 *
 * @param providerConfigId model_provider_config.id（审计与追溯）
 * @param protocol         OPENAI_PROTOCOL / ANTHROPIC_PROTOCOL
 * @param baseUrl          兼容端点地址
 * @param apiKey           已解密明文（构建模型实例时注入，不落日志）
 * @param model            模型名（如 qwen-plus-latest）
 * @param params           采样参数（temperature/maxTokens 等，可空）
 */
public record ModelConfig(
        long providerConfigId,
        String protocol,
        String baseUrl,
        String apiKey,
        String model,
        Map<String, Object> params) {

    public ModelConfig {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
