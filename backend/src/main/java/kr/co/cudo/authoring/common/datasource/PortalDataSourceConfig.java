package kr.co.cudo.authoring.common.datasource;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                classes = PortalRepo.class
        ),
        entityManagerFactoryRef = "portalEntityManagerFactory",
        transactionManagerRef = "portalTransactionManager"
)
public class PortalDataSourceConfig {

    @Bean(name = "portalDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.portal")
    public DataSource portalDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = "portalEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean portalEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("portalDataSource") DataSource dataSource) {
        return builder
                .dataSource(dataSource)
                .packages("kr.co.cudo.authoring")
                .persistenceUnit("portal")
                .build();
    }

    @Bean(name = "portalTransactionManager")
    public PlatformTransactionManager portalTransactionManager(
            @Qualifier("portalEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
