package kr.co.cudo.authoring.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기동 시 dev 로그인/업로드 토글이 켜져 있으면 경고를 산출하는지 검증.
 *
 * <p>로깅 부수효과 대신 순수 함수 {@code warningMessage()} 가 경고 문자열(Optional)을 반환하도록
 * 설계해 단위 테스트한다. 시크릿/토큰은 메시지에 절대 포함하지 않는다.
 *
 * <p>DEV_FIX H-5 — prd 전용 강화 문구 분기는 제거되었다(도달 불가 죽은 코드). 운영 계열(prd/stg) +
 * dev 로그인 조합은 {@link DevToggleProfileGuard} 가 {@code @PostConstruct} 에서 부팅 자체를 막으므로
 * {@code ApplicationReadyEvent} 기반인 본 경고는 절대 실행되지 않았다. 해당 커버리지는
 * {@code DevLoginToggleTest} 의 부팅 거부 케이스가 대체한다.
 */
class DevToggleStartupWarnerTest {

    @Test
    @DisplayName("둘다_false면_경고없음")
    void noWarningWhenAllDisabled() {
        assertThat(DevToggleStartupWarner.warningMessage(false, false)).isEmpty();
    }

    @Test
    @DisplayName("dev_login_켜지면_경고발생")
    void warnsWhenLoginEnabled() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(true, false);
        assertThat(msg).isPresent();
        assertThat(msg.get()).contains("dev login");
    }

    @Test
    @DisplayName("dev_upload_켜지면_경고발생")
    void warnsWhenUploadEnabled() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(false, true);
        assertThat(msg).isPresent();
        assertThat(msg.get()).contains("dev upload");
    }

    @Test
    @DisplayName("★업로드만_켜지면_상시기능_안내이며_끄라는_지시가_붙지_않는다")
    void uploadOnlyWarningHasNoTurnOffInstruction() {
        // CO-007 — 수동 업로드는 운영 상시 기능이 됐다. 예전 꼬리 문장("운영에서는 반드시 OFF")이
        //   그대로 붙으면 운영 매 기동마다 상시 기능을 끄라는 지시가 로그에 남아,
        //   그 로그를 따르는 운영자·감리가 정상 기능을 끄게 된다.
        Optional<String> msg = DevToggleStartupWarner.warningMessage(false, true);

        assertThat(msg).isPresent();
        assertThat(msg.get())
                .as("업로드 단독이면 끄라는 지시가 붙어서는 안 된다")
                .doesNotContain("반드시 OFF");
        assertThat(msg.get())
                .as("대신 상시 기능임과 되돌리는 방법을 알린다")
                .contains("상시 기능")
                .contains("DEV_UPLOAD_ENABLED=false")
                .contains("VITE_DEV_UPLOAD_ENABLED=false");
    }

    @Test
    @DisplayName("★dev_login이_켜지면_끄라는_지시가_붙는다_그건_여전히_bring-up_전용이다")
    void loginWarningKeepsTurnOffInstruction() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(true, false);

        assertThat(msg).isPresent();
        assertThat(msg.get())
                .as("dev 로그인은 인증 우회 표면이라 운영에서 꺼야 한다는 지시가 유지돼야 한다")
                .contains("반드시 OFF");
    }

    @Test
    @DisplayName("★둘다_켜지면_끄라는_지시가_dev_login_만_가리킨다")
    void bothEnabledScopesTurnOffInstructionToLogin() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(true, true);

        assertThat(msg).isPresent();
        // 지시는 남되 그 대상이 dev login 임이 문장에 드러나야 한다 —
        //   안 그러면 둘 다 끄라는 뜻으로 읽혀 상시 기능이 꺼진다.
        assertThat(msg.get()).contains("반드시 OFF");
        assertThat(msg.get())
                .as("업로드는 그 지시 대상이 아님이 문장에 드러나야 한다")
                .contains("dev upload 는 운영 상시 기능이라 이 지시 대상이 아니다");
    }

    @Test
    @DisplayName("경고문에_시크릿_토큰_단어_미포함")
    void warningDoesNotLeakSecrets() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(true, true);
        assertThat(msg).isPresent();
        String lower = msg.get().toLowerCase();
        assertThat(lower).doesNotContain("secret");
        assertThat(lower).doesNotContain("jwt_secret");
    }
}
