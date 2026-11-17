package com.qoder.ride.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 管理端接口鉴权：/api/admin/** 须携带 X-Admin-Token（常量时间比较防时序侧信道）。 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    @Value("${gateway.admin-token:ride-admin}")
    private String adminToken;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"管理端令牌缺失或错误\",\"code\":\"A0401\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}