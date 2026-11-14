package com.csys.logdetector.log.sanitizer;

/** 为后续事件聚合生成稳定、不可逆的来源标识。 */
public interface IpPseudonymizer {

    String pseudonymize(String ip);
}
