package io.xuanji.server.config;

import io.xuanji.server.auth.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前端托管（2026-10-08 架构调整：不再前后端分离）。
 *
 * <p>原型页面（React18+AntD5 UMD）位于 classpath:/static/，同源调 /api/**。
 * SPA 路由兜底：非 /api、非静态资源的路径回退 index.html（P0 页面为独立
 * html 文件，此兜底为后续 SPA 化预留）。</p>
 *
 * <p>鉴权：/api/** 全部经 AuthInterceptor（Bearer token），仅放行 /api/auth/login；
 * 静态页面不拦截，未登录由前端跳转 login.html。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}

