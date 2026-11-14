package com.csys.logdetector.detection;

import com.csys.logdetector.detection.mapper.EventAnalysisMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;

final class EventAnalysisTestMapperFactory {
    private EventAnalysisTestMapperFactory() {}

    static EventAnalysisMapper create(DataSource dataSource) {
        return create(dataSource, EventAnalysisMapper.class, "EventAnalysisMapper.xml");
    }

    static <T> T create(DataSource dataSource, Class<T> mapper, String xml) {
        try {
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setMapperLocations(new ClassPathResource("mapper/detection/" + xml));
            SqlSessionFactory factory = factoryBean.getObject();
            return new SqlSessionTemplate(factory).getMapper(mapper);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
