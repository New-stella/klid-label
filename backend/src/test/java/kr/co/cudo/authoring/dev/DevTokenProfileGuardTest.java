package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.dev.controller.DevTokenController;
import kr.co.cudo.authoring.dev.service.DevTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발/검수 전용 토큰 발급 빈은 운영(prd) 프로파일에서 미등록 — endpoint 자체가 부재하여 404 처리됨.
 *
 * <p>{@link ApplicationContextRunner} 로 슬림 컨텍스트만 띄워 빈 등록 여부를 검증한다.
 * 운영 환경 yml(DB/외부 API ENV 변수 다수 필요)을 우회하기 위함.
 */
class DevTokenProfileGuardTest {

    private SecretKey newRandomKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String material = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(material.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("prd_프로파일에서는_DevTokenController_DevTokenService_빈_미등록")
    void devTokenBeansAbsentWhenPrd() {
        SecretKey key = newRandomKey();
        new ApplicationContextRunner()
                .withUserConfiguration(DevTokenController.class, DevTokenService.class)
                .withBean(JwtKeyResolver.class, () -> () -> key)
                .withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.jwt.allowed-issuers=klid-auth"
                )
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DevTokenController.class);
                    assertThat(ctx).doesNotHaveBean(DevTokenService.class);
                });
    }

    @Test
    @DisplayName("local_프로파일에서는_DevTokenController_DevTokenService_빈_등록")
    void devTokenBeansPresentWhenLocal() {
        SecretKey key = newRandomKey();
        new ApplicationContextRunner()
                .withUserConfiguration(DevTokenController.class, DevTokenService.class)
                .withBean(JwtKeyResolver.class, () -> () -> key)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "authoring.jwt.allowed-issuers=klid-auth"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DevTokenController.class);
                    assertThat(ctx).hasSingleBean(DevTokenService.class);
                });
    }

    @Test
    @DisplayName("dev_프로파일에서도_빈_등록_운영만_차단")
    void devTokenBeansPresentWhenDev() {
        SecretKey key = newRandomKey();
        new ApplicationContextRunner()
                .withUserConfiguration(DevTokenController.class, DevTokenService.class)
                .withBean(JwtKeyResolver.class, () -> () -> key)
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "authoring.jwt.allowed-issuers=klid-auth"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DevTokenController.class);
                    assertThat(ctx).hasSingleBean(DevTokenService.class);
                });
    }

    @Test
    @DisplayName("stg_프로파일에서도_빈_등록_운영만_차단")
    void devTokenBeansPresentWhenStg() {
        SecretKey key = newRandomKey();
        new ApplicationContextRunner()
                .withUserConfiguration(DevTokenController.class, DevTokenService.class)
                .withBean(JwtKeyResolver.class, () -> () -> key)
                .withPropertyValues(
                        "spring.profiles.active=stg",
                        "authoring.jwt.allowed-issuers=klid-auth"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DevTokenController.class);
                    assertThat(ctx).hasSingleBean(DevTokenService.class);
                });
    }
}
