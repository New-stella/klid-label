package kr.co.cudo.authoring.dev;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * [보안] 기동 시 dev 로그인/업로드 토글이 켜져 있으면 WARN 경고를 남긴다.
 *
 * <p>관제서버 미기동 폐쇄망 bring-up 용으로 켜는 토글이 그대로 방치되는 사고를 막기 위한 가시성 경고다.
 * 토글 자체는 항상 기본 false(fail-closed) — 이 경고는 켜졌을 때만 발생한다.
 *
 * <h3>운영 계열(prd/stg) 경고 분기를 두지 않는 이유 (DEV_FIX H-5)</h3>
 * <p>과거에는 "active profile 에 prd 포함" 시 더 강한 문구를 덧붙이는 분기가 있었으나 <b>도달 불가능한
 * 죽은 코드</b>였다. 본 리스너는 {@link ApplicationReadyEvent}(컨텍스트 refresh <i>완료 후</i>)에 발화하는데,
 * {@link DevToggleProfileGuard} 가 {@code @PostConstruct}(refresh <i>중</i>)에서 prd·stg + dev 로그인
 * 조합을 이미 {@code IllegalStateException} 으로 중단시키기 때문이다. 즉 그 분기가 찍힐 수 있는 실행 경로는
 * 존재하지 않았다. 따라서 본 경고는 <b>부팅이 차단되지 않는 조합</b>(local/dev 프로파일, 또는 prd/stg 라도
 * dev 업로드 토글만 켜진 경우)에만 의미가 있으며, 운영 계열의 dev 로그인 차단은 전적으로
 * {@link DevToggleProfileGuard} 의 fail-fast 책임이다.
 *
 * <p>경고 문자열에는 시크릿/토큰을 절대 포함하지 않는다 (CWE-200/532).
 */
@Slf4j
@Component
public class DevToggleStartupWarner implements ApplicationListener<ApplicationReadyEvent> {

    private final boolean devLoginEnabled;
    private final boolean devUploadEnabled;

    public DevToggleStartupWarner(
            @Value("${authoring.dev.login.enabled:false}") boolean devLoginEnabled,
            @Value("${authoring.dev.upload.enabled:false}") boolean devUploadEnabled) {
        this.devLoginEnabled = devLoginEnabled;
        this.devUploadEnabled = devUploadEnabled;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        warningMessage(devLoginEnabled, devUploadEnabled)
                .ifPresent(msg -> log.warn("{}", msg));
    }

    /**
     * 켜진 토글에 대한 경고 문자열을 산출한다 (없으면 {@link Optional#empty()}).
     * 순수 함수 — 로깅 부수효과와 분리해 단위 테스트 가능.
     *
     * <p>프로파일 인자를 받지 않는다 — 클래스 javadoc 참조(운영 계열 조합은 부팅 단계에서 이미 차단됨).
     */
    static Optional<String> warningMessage(boolean devLoginEnabled, boolean devUploadEnabled) {
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
        return Optional.of(sb.toString());
    }
}
