package kr.co.cudo.authoring.dev;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * [보안] 기동 시 dev 로그인/업로드 토글이 켜져 있으면 WARN 경고를 남긴다.
 *
 * <p>관제서버 미기동 폐쇄망 bring-up 용으로 {@code authoring.dev.login.enabled}/{@code authoring.dev.upload.enabled}
 * 를 prd 에서도 켤 수 있게 되어, 켜진 상태로 운영에 방치되는 사고를 방지하기 위한 가시성 경고다.
 * 토글 자체는 항상 기본 false(fail-closed) — 이 경고는 켜졌을 때만 발생한다.
 *
 * <p>경고 문자열에는 시크릿/토큰을 절대 포함하지 않는다 (CWE-200/532).
 */
@Slf4j
@Component
public class DevToggleStartupWarner implements ApplicationListener<ApplicationReadyEvent> {

    private final boolean devLoginEnabled;
    private final boolean devUploadEnabled;
    private final boolean prdActive;

    public DevToggleStartupWarner(
            @Value("${authoring.dev.login.enabled:false}") boolean devLoginEnabled,
            @Value("${authoring.dev.upload.enabled:false}") boolean devUploadEnabled,
            Environment environment) {
        this.devLoginEnabled = devLoginEnabled;
        this.devUploadEnabled = devUploadEnabled;
        this.prdActive = Arrays.asList(environment.getActiveProfiles()).contains("prd");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        warningMessage(devLoginEnabled, devUploadEnabled, prdActive)
                .ifPresent(msg -> log.warn("{}", msg));
    }

    /**
     * 켜진 토글에 대한 경고 문자열을 산출한다 (없으면 {@link Optional#empty()}).
     * 순수 함수 — 로깅 부수효과와 분리해 단위 테스트 가능.
     */
    static Optional<String> warningMessage(boolean devLoginEnabled, boolean devUploadEnabled, boolean prdActive) {
        if (!devLoginEnabled && !devUploadEnabled) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder("[SECURITY] ");
        if (devLoginEnabled) {
            sb.append("dev login ENABLED (/v1/dev/tokens 가 인증 없이 토큰 발급) ");
        }
        if (devUploadEnabled) {
            sb.append("dev upload ENABLED (/v1/dev/autolabel-test 업로드 노출) ");
        }
        sb.append("— bring-up 전용 토글. 운영에서는 반드시 OFF.");
        if (prdActive) {
            sb.append(" ⚠⚠ active profile 에 prd 포함 — 운영 환경에서 dev 토글이 켜져 있습니다. 즉시 점검 요망.");
        }
        return Optional.of(sb.toString());
    }
}
