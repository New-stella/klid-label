package kr.co.cudo.authoring.version.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code LS_LABEL_VERSION.VER_NO} 재정의(P1) 단위 테스트 — <b>프레임별 순번 → 영상 단위 산출 버전 번호</b>.
 *
 * <p>구 의미(프레임별 {@code count + 1})로는 "영상의 N번째 승인본"을 지목할 수 없었다. 검수 승인은
 * 영상 단위인데 내용이 안 바뀐 프레임은 {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE 때문에 스냅샷이
 * 생기지 않아 프레임마다 번호가 밀리기 때문이다. 새 의미는 {@code LS_DATASET_EXPORT.OUTPUT_VER_NO}
 * 와 같은 번호(관제가 픽업하는 산출 폴더 {@code v1}·{@code v2})이며, <b>실제 채번 배선은 P3</b> 이므로
 * 이 단계에서는 {@code null}(= 아직 모름)이 정직한 값이다.
 *
 * @design D5
 * @req R6
 */
class LsLabelVersionVersionNoTest {

    @Test
    @DisplayName("버전번호는_null을_허용한다")
    void 버전번호는_null을_허용한다() {
        // given / when — 산출 버전 번호를 아직 모르는 스냅샷(P3 전까지의 정상 상태)
        LsLabelVersion version = LsLabelVersion.create(
                1L, 10L, "hash-a", "{\"items\":[]}", null,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");

        // then — 예외 없이 생성되고 값은 null 로 보존된다(0 등으로 지어내지 않는다)
        assertThat(version.getVersionNo()).isNull();
        assertThat(version.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_APPROVED);
        assertThat(version.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
    }

    @Test
    @DisplayName("버전번호가_주어지면_그대로_보존된다 — P3_채번_배선용")
    void 버전번호가_주어지면_그대로_보존된다() {
        // given / when — P3 에서 산출 버전 번호를 실제로 싣게 될 경로
        LsLabelVersion version = LsLabelVersion.create(
                1L, 10L, "hash-b", "{\"items\":[]}", 2,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");

        // then
        assertThat(version.getVersionNo()).isEqualTo(2);
    }

    @Test
    @DisplayName("재활성은_버전번호를_건드리지_않는다")
    void 재활성은_버전번호를_건드리지_않는다() {
        // given — 롤백 재활성(D-ISSUE-21) 경로는 식별자·페이로드·버전번호를 보존한다
        LsLabelVersion version = LsLabelVersion.create(
                1L, 10L, "hash-c", "{\"items\":[]}", null,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");

        // when
        version.deactivate();
        assertThatCode(version::activate).doesNotThrowAnyException();

        // then — null 이어도 재활성이 NPE 로 깨지지 않는다(int 였다면 언박싱에서 터진다)
        assertThat(version.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        assertThat(version.getVersionNo()).isNull();
    }
}
