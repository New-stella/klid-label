package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개발용 로그인 표준 계정의 <b>번호 단일 진실원</b>과 자동 등록 제외 판정
 * (@design AC-1016 · @design UC-041).
 *
 * <h3>여기서 고정하는 두 축</h3>
 * <ol>
 *   <li><b>토글 연동</b> — 개발용 로그인이 꺼진 형상에서는 제외가 <b>아무 일도 하지 않는다</b>.
 *       운영에 같은 번호를 쓰는 실제 사용자가 있을 수 있고, 그 사용자가 자동 등록에서 빠지면
 *       오류 없이 조용히 정책이 훼손된다.</li>
 *   <li><b>발급 값 ↔ 제외 집합 정합</b> — 개발용 토큰이 실제로 싣는 기본 번호가 제외 집합에
 *       속해야 한다. 이 둘이 갈리면 새 dev 계정만 제외에서 빠져 원래 사고가 그대로 재현된다.</li>
 * </ol>
 */
class DevStandardAccountsTest {

    private final DevStandardAccounts devLoginOn = new DevStandardAccounts(true);
    private final DevStandardAccounts devLoginOff = new DevStandardAccounts(false);

    /** 표준 계정과 겹치지 않는 일반 진입자 번호. */
    private static final long ORDINARY_USER_NO = 969_400_777L;

    @Test
    @DisplayName("★모든_역할의_기본_사용자번호가_제외_집합에_속한다_발급값과_판정이_갈리지_않는다")
    void everyIssuedDefaultUserNoIsInExcludedSet() {
        for (Role role : Role.values()) {
            String userNo = DevStandardAccounts.userNoOf(role);

            assertThat(userNo)
                    .as("%s 의 기본 사용자번호가 비어 있다 — 그 역할의 토큰 주체가 비게 된다", role)
                    .isNotBlank();
            assertThat(DevStandardAccounts.isDevStandardUserNo(Long.parseLong(userNo)))
                    .as("%s 의 기본 번호 %s 가 제외 집합 밖이다 — 발급은 되는데 제외는 안 되는 "
                            + "계정이 생기고, 그 계정에서 원래 사고가 그대로 재현된다", role, userNo)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("★개발용_로그인이_켜지면_표준_계정은_자동_등록_제외_대상이다")
    void devStandardAccountsAreExcludedWhenEnabled() {
        for (Role role : Role.values()) {
            long userNo = Long.parseLong(DevStandardAccounts.userNoOf(role));
            assertThat(devLoginOn.isAutoRegisterExcluded(userNo)).isTrue();
        }
    }

    @Test
    @DisplayName("★★개발용_로그인이_꺼지면_같은_번호여도_제외하지_않는다_운영_형상은_종전_그대로")
    void nothingIsExcludedWhenDevLoginDisabled() {
        for (Role role : Role.values()) {
            long userNo = Long.parseLong(DevStandardAccounts.userNoOf(role));
            assertThat(devLoginOff.isAutoRegisterExcluded(userNo))
                    .as("운영에는 %s 번을 쓰는 실제 사용자가 있을 수 있다 — 그 사용자가 자동 "
                            + "등록에서 조용히 빠지면 오류 없이 정책이 훼손된다", userNo)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("표준_계정이_아니면_토글과_무관하게_제외하지_않는다")
    void ordinaryUserIsNeverExcluded() {
        assertThat(devLoginOn.isAutoRegisterExcluded(ORDINARY_USER_NO)).isFalse();
        assertThat(devLoginOff.isAutoRegisterExcluded(ORDINARY_USER_NO)).isFalse();
        assertThat(DevStandardAccounts.isDevStandardUserNo(ORDINARY_USER_NO)).isFalse();
    }

    @Test
    @DisplayName("식별할_수_없는_주체는_판정에서_터지지_않는다")
    void nullUserNoIsSafe() {
        assertThat(DevStandardAccounts.isDevStandardUserNo(null)).isFalse();
        assertThat(devLoginOn.isAutoRegisterExcluded(null)).isFalse();
    }

    @Test
    @DisplayName("기본_번호는_역할마다_서로_다르다_한_행을_두_역할이_가리키지_않는다")
    void defaultUserNosAreDistinctPerRole() {
        long distinct = java.util.Arrays.stream(Role.values())
                .map(DevStandardAccounts::userNoOf)
                .distinct()
                .count();

        assertThat(distinct)
                .as("두 역할이 같은 번호를 쓰면 한 토큰의 주체가 다른 사람의 행을 가리킨다"
                        + "(과거 1002/2001 오매핑 사고)")
                .isEqualTo(Role.values().length);
    }
}
