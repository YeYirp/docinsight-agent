package com.example.docinsight.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 可选的 Bearer Token 鉴权：app.security.api-token 为空时不拦截（本机默认用法），
 * 配置后 /api/** 必须带匹配的 token。静态页面与 index.html 不受影响。
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    private final AiProperties properties;

    public ApiTokenFilter(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AiProperties.Security security = properties.security();
        if (security == null || !security.enabled()
                || !request.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String presented = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : "";
        if (constantTimeEquals(presented, security.apiToken())) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"需要有效的 Bearer Token\"}");
    }

    /** 定长比较，避免按前缀逐字节比较泄露 token。 */
    private static boolean constantTimeEquals(String presented, String expected) {
        if (presented == null || expected == null) {
            return false;
        }
        byte[] left = presented.getBytes(StandardCharsets.UTF_8);
        byte[] right = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
