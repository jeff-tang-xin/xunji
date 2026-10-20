package io.xuanji.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理台 API 鉴权拦截器：/api/** 全保护，仅放行 /api/auth/login。
 *
 * <p>静态页面不拦截（登录页本身需要可达）；前端未登录时由页面跳转 login.html。
 * App 消费端点（AppKey Bearer）为另一鉴权域，上线时以路径前缀区分放行。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_LOGIN_USER = "loginUser";

    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true; // 静态资源等非 Controller 处理器放行
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path.equals("/api/auth/login")) {
            return true;
        }
        if (path.startsWith("/api/v1/")) {
            return true; // App 开放端点：AppKey 鉴权域（AppController 内校验），不走管理台 token
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token == null && path.endsWith("/events")) {
            // SSE 订阅端点：浏览器 EventSource 无法设置 Authorization header（API 限制），
            // 允许 ?token= 传递（仅 /events 后缀放行，普通接口仍强制 header，避免 token 进访问日志）
            token = request.getParameter("token");
        }
        LoginUser user = tokenService.resolve(token);
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return false;
        }
        request.setAttribute(ATTR_LOGIN_USER, user);
        return true;
    }
}
