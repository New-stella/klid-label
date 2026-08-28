package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>역할 계층이 미치지 않는 유일한 예외</b> — 원본(비-비식별) 프레임 이미지 축은 관리자가
 * 물려받지 않는다. [design: ADR-055] [design: ROLE-004] [design: AC-125] [design: API-018]
 *
 * <h3>무엇을 고정하는가</h3>
 * <p>라벨 조회 응답의 프레임 이미지 종류({@code frameImageType})가 관리자에게는 {@code raw=true} 를
 * <b>명시</b>해도 {@code RAW} 가 아니라 {@code DEID} 로 나간다. 판정은
 * {@code LabelService.resolveFrameImageType} 의 {@code actor.role() == Role.REVIEWER} 한 줄이며,
 * 계층 이관 라운드에서 그 줄만 {@code hasRole(Role.REVIEWER)} 로 바뀌면 개인정보 노출면이 관리자까지
 * <b>조용히</b> 넓어진다(CWE-359). 코드 주석은 게이트가 아니므로 이 시험이 그 되돌림을 막는다.
 *
 * <h3>짝이 되는 시험</h3>
 * <p>같은 정책의 다른 얼굴 — <b>실제 바이트 서빙</b> 쪽은
 * {@code kr.co.cudo.authoring.video.service.FrameImageAdminRawExceptionTest} 가 고정한다. 이 자리는
 * "응답이 무엇이라고 말하는가"(선언 축)이고 그쪽은 "무엇이 실제로 나가는가"(바이트 축)라, 한쪽만
 * 열리면 응답이 말하는 종류와 실제로 나가는 벌이 어긋난다. <b>두 시험은 함께 본다.</b>
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <p>「관리자는 {@code RAW} 를 못 받는다」와 짝으로 <b>「검수자는 {@code RAW} 를 받는다」</b>를 둔다.
 * 원본 분기가 애초에 도달 불가면 앞 단언은 <b>항상 참</b>이 되어 아무것도 지키지 않는다.
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>판정을 {@code actor != null && actor.hasRole(Role.REVIEWER) && allowRaw} 로 바꾸면 이 클래스의
 * 관리자 시험 1건이 FAILED 가 된다({@code DEID} 를 기대하는데 {@code RAW} 가 나온다).
 */
class LabelFrameImageTypeAdminExceptionTest {

    private static TokenClaims actor(String sub, Role role) {
        return new TokenClaims(sub, role, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    // ---------- 예외 본체 — 관리자는 원본 축을 물려받지 않는다 ----------

    @Test
    @DisplayName("★관리자가_raw_true_를_명시해도_프레임_이미지_종류는_DEID_다_계층_예외")
    void adminRawRequestStillResolvesToDeid() {
        assertThat(LabelService.resolveFrameImageType(actor("969300021", Role.ADMIN), true))
                .as("개인정보 열람은 역할 계층과 별개 축이라 관리자에게 자동으로 열리지 않는다")
                .isEqualTo("DEID");
    }

    // ---------- 대조군 — 원본 분기가 실제로 도달 가능함을 확인 ----------

    @Test
    @DisplayName("검수자의_raw_true_는_RAW_다_원본_분기가_도달_가능함을_고정")
    void reviewerRawRequestResolvesToRaw() {
        // 이 시험이 없으면 위 관리자 시험은 "원본 분기가 애초에 죽어 있어도" 통과한다(항상 참인 시험).
        assertThat(LabelService.resolveFrameImageType(actor("1", Role.REVIEWER), true))
                .isEqualTo("RAW");
    }

    @Test
    @DisplayName("작업자의_raw_true_는_무시되어_DEID_다_기존_정책_불변")
    void workerRawRequestResolvesToDeid() {
        assertThat(LabelService.resolveFrameImageType(actor("100", Role.WORKER), true))
                .isEqualTo("DEID");
    }

    @Test
    @DisplayName("raw_를_요청하지_않으면_역할과_무관하게_DEID_다")
    void withoutRawAlwaysDeid() {
        assertThat(LabelService.resolveFrameImageType(actor("1", Role.REVIEWER), false)).isEqualTo("DEID");
        assertThat(LabelService.resolveFrameImageType(actor("969300021", Role.ADMIN), false)).isEqualTo("DEID");
        assertThat(LabelService.resolveFrameImageType(actor("100", Role.WORKER), false)).isEqualTo("DEID");
    }

    @Test
    @DisplayName("인증_주체가_없으면_DEID_다_fail_closed")
    void nullActorResolvesToDeid() {
        assertThat(LabelService.resolveFrameImageType(null, true)).isEqualTo("DEID");
    }
}
