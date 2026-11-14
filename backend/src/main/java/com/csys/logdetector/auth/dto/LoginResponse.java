package com.csys.logdetector.auth.dto;

/**
 * 登录成功后返回：token 交给前端存本地，后续请求放 {@code Authorization: Bearer <token>}。
 * 登录成功后封装结果返回给前端
 */
public record LoginResponse(String token, String username, String role) {
}
