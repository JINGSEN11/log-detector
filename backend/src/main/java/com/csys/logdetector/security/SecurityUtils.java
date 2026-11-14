package com.csys.logdetector.security;

import com.csys.logdetector.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取当前登录用户的便捷入口。
 *
 * <pre>
 * LoginUser user = SecurityUtils.currentUser();
 * </pre>
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        throw new BizException(401, "未登录或登录已过期");
    }
}
