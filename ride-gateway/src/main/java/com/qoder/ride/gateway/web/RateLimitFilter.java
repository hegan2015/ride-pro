package com.qoder.ride.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** API 限流：按来源 IP 固定窗口计数（演示级单机限流，生产应接分布式限流）。 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${gateway.rate-limit-per-second:100}")
    private int limitPerSecond;

    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(request);
        long second = System.currentTimeMillis() / 1000;
        long[] window = windows.compute(ip, (k, v) -> {
            if (v == null || v[0] != second) {
                return new long[]{second, 1};
            }
            v[1]++;
            return v;
        });
        if (windows.size() > 10000) {
            windows.entrySet().removeIf(e -> e.getValue()[0] < second - 1);
        }
        if (window[1] > limitPerSecond) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后重试\",\"code\":\"A0429\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}