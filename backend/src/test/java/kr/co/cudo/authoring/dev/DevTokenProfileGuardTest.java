package kr.co.cudo.authoring.dev;

import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import kr.co.cudo.authoring.dev.controller.DevTokenController;
import kr.co.cudo.authoring.dev.service.DevTokenService;
import kr.co.cudo.authoring.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 개발/검수 전용 토큰 발급 빈은 {@code authoring.dev.login.enabled} 프로퍼티로만 게이팅된다.
 *
 * <p>기존 {@code @Profile("!prd")} → {@code @ConditionalOnProperty} 전환.
 * 관제서버 미기동 폐쇄망 bring-up 을 위해 prd 에서도 env({@code DEV_LOGIN_ENABLED=true}) 로
 * 켤 수 있어야 한다. 단 <b>기본값은 false(fail-closed)</b> 라 미설정 시 prd 든 dev 든 빈 부재.
 *
 * <p>{@link ApplicationContextRunner} 로 슬림 컨텍스트만 띄워 빈 등록 여부를 검증한다.
 */
class DevTokenProfileGuardTest {

    private SecretKey newRandomKey() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String material = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return io.jsonwebtoken.security.Keys.hmacShaKeyFor(material.getBytes(StandardCharsets.UTF_8));
    }

    private ApplicationContextRunner runner() {
        SecretKey key = newRandomKey();
        return new ApplicationContextRunner()
                .withUserConfiguration(DevTokenController.class, DevTokenService.class)
                .withBean(JwtKeyResolver.class, () -> (JwtKeyResolver) () -> key)
                .withBean(UserRepository.class, () -> mock(UserRepository.class))
                .withPropertyValues("authoring.jwt.allowed-issuers=klid-auth");
    }

    @Test
    @DisplayName("dev_login_enabled_미설정이면_DevToken_빈_미등록_failClosed")
    void devTokenBeansAbsentWhenPropertyMissing() {
        runner()
                .withPropertyValues("spring.profiles.active=prd")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DevTokenController.class);
                    assertThat(ctx).doesNotHaveBean(DevTokenService.class);
                });
    }

    @Test
    @DisplayName("dev_login_enabled_false면_DevToken_빈_미등록")
    void devTokenBeansAbsentWhenFalse() {
        runner()
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "authoring.dev.login.enabled=false"
                )
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(DevTokenController.class);
                    assertThat(ctx).doesNotHaveBean(DevTokenService.class);
                });
    }

    @Test
    @DisplayName("dev_login_enabled_true면_prd_프로파일에서도_DevToken_빈_등록")
    void devTokenBeansPresentWhenEnabledEvenInPrd() {
        runner()
                .withPropertyValues(
                        "spring.profiles.active=prd",
                        "authoring.dev.login.enabled=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DevTokenController.class);
                    assertThat(ctx).hasSingleBean(DevTokenService.class);
                });
    }

    @Test
    @DisplayName("dev_login_enabled_true면_local_프로파일에서도_DevToken_빈_등록")
    void devTokenBeansPresentWhenEnabledInLocal() {
        runner()
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "authoring.dev.login.enabled=true"
                )
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DevTokenController.class);
                    assertThat(ctx).hasSingleBean(DevTokenService.class);
                });
    }
}
