package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackages = "kr.co.cudo.authoring",
        includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.ANNOTATION,
                classes = ControlRepo.class
        ),
        entityManagerFactoryRef = "controlEntityManagerFactory",
        transactionManagerRef = "controlTransactionManager"
)
public class ControlDataSourceConfig {

    @Primary
    @Bean(name = "controlDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.control")
    public DataSource controlDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Primary
    @Bean(name = "controlEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean controlEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("controlDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("kr.co.cudo.authoring")
                .persistenceUnit("control")
                .build();
    }

    @Primary
    @Bean(name = "controlTransactionManager")
    public PlatformTransactionManager controlTransactionManager(
            @Qualifier("controlEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
