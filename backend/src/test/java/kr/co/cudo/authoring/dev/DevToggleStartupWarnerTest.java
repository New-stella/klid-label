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
 */
class DevToggleStartupWarnerTest {

    @Test
    @DisplayName("둘다_false면_경고없음")
    void noWarningWhenAllDisabled() {
        assertThat(DevToggleStartupWarner.warningMessage(false, false, false)).isEmpty();
    }

    @Test
    @DisplayName("dev_login_켜지면_경고발생")
    void warnsWhenLoginEnabled() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(true, false, false);
        assertThat(msg).isPresent();
        assertThat(msg.get()).contains("dev login");
    }

    @Test
    @DisplayName("dev_upload_켜지면_경고발생")
    void warnsWhenUploadEnabled() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(false, true, false);
        assertThat(msg).isPresent();
        assertThat(msg.get()).contains("dev upload");
    }

    @Test
    @DisplayName("prd_프로파일_포함이면_더_강한_경고문구")
    void strongerWarningWhenPrdActive() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(true, false, true);
        assertThat(msg).isPresent();
        assertThat(msg.get()).contains("prd");
    }

    @Test
    @DisplayName("경고문에_시크릿_토큰_단어_미포함")
    void warningDoesNotLeakSecrets() {
        Optional<String> msg = DevToggleStartupWarner.warningMessage(true, true, true);
        assertThat(msg).isPresent();
        String lower = msg.get().toLowerCase();
        assertThat(lower).doesNotContain("secret");
        assertThat(lower).doesNotContain("jwt_secret");
    }
}
