package kr.co.cudo.authoring.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

@Configuration
public class RoleHierarchy {

    @Bean
    public org.springframework.security.access.hierarchicalroles.RoleHierarchy authoringRoleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("");
    }
}
