package io.openharness.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;

public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    private static final List<String> MAPPER_RESOURCES = List.of(
            "mapper/SessionMapper.xml",
            "mapper/MessageMapper.xml",
            "mapper/InteractionMapper.xml",
            "mapper/ReplayMapper.xml"
    );

    private DataSourceConfig() {}

    public static DataSource createHikariDataSource(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(600000);
        return new HikariDataSource(config);
    }

    public static SqlSessionFactory createSqlSessionFactory(DataSource dataSource) {
        Environment env = new Environment("oh", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMappers("io.openharness.core.persistence.mapper");

        for (String resource : MAPPER_RESOURCES) {
            try (var is = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(is, config, resource, config.getSqlFragments()).parse();
            } catch (Exception e) {
                log.error("Failed to load mapper: {}", resource, e);
            }
        }

        return new SqlSessionFactoryBuilder().build(config);
    }
}
