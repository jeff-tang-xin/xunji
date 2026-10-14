package io.xuanji.server.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：把 {@link ResponseStatusException} 的中文原因放进响应体。
 *
 * <p><b>为什么必须做</b>：Spring 默认 {@code server.error.include-message=never}，reason 不进响应体，
 * 前端只拿得到 {@code {"status":400,"error":"Bad Request","path":"/api/..."}}。线上实测后果很实在——
 * 推进阶段被门拦下时后端写的是「当前阶段执行未完成（run=RUNNING），不能推进」这类可执行的原因，
 * 但用户界面只显示「Bad Request」四个字：既不知道该等什么，也无从判断是不是故障，
 * 排查只能靠人在服务器上手敲 curl。</p>
 *
 * <p><b>为什么不改全局 include-message</b>：那个配置在服务器上外部的 {@code application.yml} 里
 * （与 DB 凭据同文件），改动覆盖所有接口与默认错误页，本机无法验证。这里只为本服务显式抛出的
 * 业务异常补一个 {@code message} 字段：HTTP 状态码、以及 {@code status/error/path} 既有字段
 * 全部保持不变，属向后兼容的增量。</p>
 *
 * <p><b>刻意不兜底 {@code Exception}</b>：未预期异常继续走 Spring 默认 500，不在这里被伪装成业务错误，
 * 否则堆栈会被抹平、故障会被读成「参数不对」。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException e,
                                                                   HttpServletRequest request) {
        int code = e.getStatusCode().value();
        String reason = e.getReason();
        // 4xx 是业务拒绝，属正常流，记 info 便于按「谁被什么门拦下」排障；5xx 才提级
        if (code >= 500) {
            log.error("request failed {} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                    code, reason == null ? e.toString() : reason, e);
        } else {
            log.info("request rejected {} {} -> {} {}", request.getMethod(), request.getRequestURI(),
                    code, reason == null ? HttpStatus.valueOf(code).getReasonPhrase() : reason);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", code);
        body.put("error", HttpStatus.valueOf(code).getReasonPhrase());
        body.put("message", reason == null || reason.isBlank()
                ? HttpStatus.valueOf(code).getReasonPhrase() : reason);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(code).body(body);
    }
}
