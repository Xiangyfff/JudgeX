package com.judgex.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * MyBatis wiring.
 *
 * <p>The Spring Boot starter is not used on purpose: the starter owns the
 * SqlSessionFactory and hides the settings that matter here (statement timeout,
 * mapper locations). Everything is explicit instead of auto-configured.</p>
 *
 * <p>SQL lives in {@code resources/mapper/*.xml}; the Java interfaces in
 * {@code com.judgex.repo} and {@code com.judgex.mq} are found by @MapperScan.</p>
 */
@Configuration
@MapperScan({"com.judgex.repo", "com.judgex.mq"})
public class MyBatisConfig {

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapper/*.xml"));

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        // snake_case columns -> camelCase properties, so the XML does not need resultMaps.
        configuration.setMapUnderscoreToCamelCase(true);
        // A stuck query must never hold a pool connection forever.
        configuration.setDefaultStatementTimeout(10);
        // No second-level cache: verdicts change underneath us, stale reads are a bug.
        configuration.setCacheEnabled(false);
        factory.setConfiguration(configuration);

        return factory.getObject();
    }
}
