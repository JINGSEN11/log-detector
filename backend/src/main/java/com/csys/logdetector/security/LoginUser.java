package com.csys.logdetector.security;

/**
 * 认证通过后放进 SecurityContext 的当前用户信息。
 *
 * @param id       用户主键
 * @param username 登录名
 * @param role     角色（不含 ROLE_ 前缀）
 */
public record LoginUser(Long id, String username, String role) {

    public String authority() {
        return "ROLE_" + role;
    }
}
