package io.openharness.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;

public class DataSourceConfig {

    private DataSourceConfig() {}

    public static DataSource createHikariDataSource(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);  // CLI 单用户，连接池无需大
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
        // Mapper 接口注册
        config.addMappers("io.openharness.core.persistence.mapper");
        return new SqlSessionFactoryBuilder().build(config);
    }
}
