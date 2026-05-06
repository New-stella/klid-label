package kr.co.cudo.authoring.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean(name = "controlServerWebClient")
    public WebClient controlServerWebClient(
            @Value("${authoring.integration.control-server.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean(name = "portalServerWebClient")
    public WebClient portalServerWebClient(
            @Value("${authoring.integration.portal-server.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean(name = "deidentifyWebClient")
    public WebClient deidentifyWebClient(
            @Value("${authoring.integration.deidentify.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean(name = "aiServerWebClient")
    public WebClient aiServerWebClient(
            @Value("${authoring.integration.ai-server.base-url}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean(name = "giteaWebClient")
    public WebClient giteaWebClient(
            @Value("${authoring.integration.gitea.base-url}") String baseUrl,
            @Value("${authoring.integration.gitea.token:}") String token) {
        WebClient.Builder b = WebClient.builder().baseUrl(baseUrl);
        if (token != null && !token.isBlank()) {
            b.defaultHeader("Authorization", "token " + token);
        }
        return b.build();
    }
}
