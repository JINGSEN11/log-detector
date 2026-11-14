package com.csys.logdetector.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.csys.logdetector.user.entity.User;
import com.csys.logdetector.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 首次启动时创建管理员账号（密码取 {@code APP_ADMIN_PASSWORD}，用 BCrypt 存库）。
 *
 * <p>这样样库里不会出现明文口令或写死的散列值；重复启动不会重复插入。</p>
 */
@Component
public class UserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);
    private static final String ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public UserBootstrap(UserMapper userMapper,
                         PasswordEncoder passwordEncoder,
                         @Value("${app.admin.password:admin123}") String adminPassword) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, ADMIN_USERNAME));
        if (existing != null && existing > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole("ADMIN");
        admin.setDisplayName("系统管理员");
        admin.setEnabled(true);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        userMapper.insert(admin);
        log.info("已创建默认管理员账号 {}（密码来自 APP_ADMIN_PASSWORD），请登录后立即修改", ADMIN_USERNAME);
    }
}
