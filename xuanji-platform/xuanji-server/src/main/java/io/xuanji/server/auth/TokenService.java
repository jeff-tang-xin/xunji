package io.xuanji.server.auth;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理台会话令牌（P0：内存存储）。
 *
 * <p>重启失效 = 用户重新登录，管理台可接受；P1 如需跨实例共享再落库或换 Redis。
 * token 为 256bit 随机数（SecureRandom 生成 32 字节 → 64 hex），仅内存持有，不落日志。</p>
 */
@Service
public class TokenService {

    private final Map<String, LoginUser> sessions = new ConcurrentHashMap<>();

    private final SecureRandom random = new SecureRandom();

    public String create(LoginUser user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        sessions.put(token, user);
        return token;
    }

    /** 无效/过期返回 null */
    public LoginUser resolve(String token) {
        return token == null ? null : sessions.get(token);
    }

    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}
