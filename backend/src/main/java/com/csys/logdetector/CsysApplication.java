package com.csys.logdetector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 脚手架启动类。
 *
 * <p>MyBatis Mapper 接口标注 {@code @Mapper}；手写 JDBC 数据访问类由 Spring 组件扫描注册。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CsysApplication {

    public static void main(String[] args) {
        SpringApplication.run(CsysApplication.class, args);
    }
}
