package kr.co.cudo.authoring.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    private static final String API_DESCRIPTION = """
            학습데이터 저작도구 API

            ## 개발/검수 환경 인증 방법 (FE 가이드)
            1. `POST /v1/dev/tokens` 호출 — `role`(REVIEWER/WORKER/PORTAL_USER) 과
               `channel`(INTERNAL/PORTAL) 을 지정해 테스트 JWT 를 발급받습니다.
            2. 응답 `data.token` 값을 복사합니다.
            3. 우측 상단 **Authorize** 버튼을 눌러 토큰을 붙여넣습니다.
               (`Bearer ` 접두사는 입력하지 않습니다 — Swagger 가 자동으로 부여합니다.)
            4. 보호된 API 를 호출하면 `Authorization: Bearer {token}` 헤더가 자동 전송됩니다.

            > persist-authorization 가 켜져 있어 브라우저를 새로고침해도 Authorize 토큰이 유지됩니다.

            ## [주의] 운영(prd) 환경
            운영 환경에서는 dev-token 엔드포인트(`/v1/dev/tokens`)와 Swagger UI / API 문서가
            모두 비활성화되어 존재하지 않습니다. 위 절차는 개발/검수 환경에서만 사용 가능합니다.
            """;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("KLID Authoring API")
                        .version("v1")
                        .description(API_DESCRIPTION))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
