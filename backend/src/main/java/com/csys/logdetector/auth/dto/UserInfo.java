package com.csys.logdetector.auth.dto;

/**
 * 当前登录用户信息（不含 token）。
 * 返回当前登录用户的信息
 */
public record UserInfo(Long id, String username, String role) {
}
