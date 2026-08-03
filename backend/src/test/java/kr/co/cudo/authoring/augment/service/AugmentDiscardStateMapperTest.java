package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.augment.dto.AugmentDiscardStateResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 폐기 축 응답 판정({@link AugmentDiscardStateMapper}) 단위 검증 — Phase 1 작업 A.
 *
 * <p><b>왜 판정을 별도 빈으로 떼어 단위 검증하는가</b>: 폐기 원장은 <b>반려마다 새 행</b>을 만들고 복구는
 * 기존 행을 <b>닫을 뿐 지우지 않는다</b>. 그래서 한 {@code dataAugSn} 에 반려→복구→재반려 이력이 여러
 * 행으로 쌓이고, "최신 PK 행을 그대로 노출" 하면 <b>지금은 멀쩡한 항목이 과거의 닫힌 폐기행 때문에
 * '곧 삭제됨' 으로 표시</b>된다(HIGH #1). 이 분기표는 조회 API 전체를 띄우지 않고도 전량 고정할 수 있어야
 * 회귀가 싸다.
 */
class AugmentDiscardStateMapperTest {

    private static final long DATA_AUG_SN = 4242L;
    private static final long NEW_RAW_SN = 777L;
    private static final long ORGNL_RAW_SN = 776L;
    private static final int GRACE_DAYS = 7;

    private static AugmentDiscardStateMapper mapper(boolean enabled) {
        return new AugmentDiscardStateMapper(new AugmentDiscardProperties(
                enabled, GRACE_DAYS, 3_600_000L, 600_000L, 50, 60, 5));
    }

    private static LsDataAugDscd marked(LocalDateTime at) {
        return LsDataAugDscd.mark(DATA_AUG_SN, NEW_RAW_SN, ORGNL_RAW_SN,
                "겨울 질감이 부자연스럽다", "1", at, "WINTER", "{\"time\":\"NIGHT\"}");
    }

    @Test
    @DisplayName("반려된_외부증강_항목은_폐기시각과_실삭제예정일시를_내려준다")
    void openMarkCarriesDiscardedAtAndPurgeAt() {
        // given
        LocalDateTime at = LocalDateTime.now().minusDays(1);

        // when
        AugmentDiscardStateResponse state = mapper(true).toResponse(marked(at));

        // then
        assertThat(state).isNotNull();
        assertThat(state.discardedAt()).isEqualTo(at);
        assertThat(state.purgeAt()).isEqualTo(at.plusDays(GRACE_DAYS));
        assertThat(state.purged()).isFalse();
        assertThat(state.restorable()).isTrue();
    }

    @Test
    @DisplayName("복구된_항목은_폐기축이_null_이다")
    void restoredMarkIsNotDiscardedAnymore() {
        // given — 반려 후 유예 내 복구. 행은 지워지지 않고 RSTR_DT 만 찍혀 <닫힌다>.
        LsDataAugDscd row = marked(LocalDateTime.now().minusDays(2));
        row.restore("1", "오판이었다", LocalDateTime.now().minusDays(1));

        // when / then — 지금은 폐기 상태가 아니므로 축 자체를 내리지 않는다.
        assertThat(mapper(true).toResponse(row)).isNull();
    }

    @Test
    @DisplayName("실삭제된_항목은_purged_true_restorable_false")
    void purgedMarkIsNotRestorable() {
        // given
        LsDataAugDscd row = marked(LocalDateTime.now().minusDays(10));
        row.markDbDeleted("system", LocalDateTime.now());

        // when
        AugmentDiscardStateResponse state = mapper(true).toResponse(row);

        // then
        assertThat(state).isNotNull();
        assertThat(state.purged()).isTrue();
        assertThat(state.restorable()).isFalse();
    }

    @Test
    @DisplayName("실삭제_클레임중인_항목은_restorable_false")
    void claimedMarkIsNotRestorable() {
        // given — 스윕이 원자 클레임(DEL_PRCS_DT)을 잡은 구간. 아직 삭제는 커밋되지 않았다.
        LsDataAugDscd row = marked(LocalDateTime.now().minusDays(10));
        ReflectionTestUtils.setField(row, "delPrcsDt", LocalDateTime.now());

        // when
        AugmentDiscardStateResponse state = mapper(true).toResponse(row);

        // then
        assertThat(state).isNotNull();
        assertThat(state.purged()).isFalse();
        assertThat(state.restorable())
                .as("집행 중에 복구 버튼을 그리면 사용자가 누르는 순간 409 가 난다")
                .isFalse();
    }

    @Test
    @DisplayName("그랜드퍼더링_항목은_폐기표식이_없어_discard_가_null")
    void noMarkMeansNoDiscardAxis() {
        assertThat(mapper(true).toResponse(null)).isNull();
    }

    @Test
    @DisplayName("폐기배치가_비활성이면_purgeAt_은_null")
    void purgeAtIsNullWhenSweepDisabled() {
        // given — 스윕이 뜨지 않으면 영원히 안 지워진다. 거짓 예정 시각을 내리지 않는다.
        LocalDateTime at = LocalDateTime.now().minusDays(1);

        // when
        AugmentDiscardStateResponse state = mapper(false).toResponse(marked(at));

        // then
        assertThat(state).isNotNull();
        assertThat(state.discardedAt()).isEqualTo(at);
        assertThat(state.purgeAt()).isNull();
        assertThat(state.purged()).isFalse();
        assertThat(state.restorable()).isTrue();
    }

    @Test
    @DisplayName("purgeAt_이_지났어도_실삭제_전이면_purged_는_false")
    void overduePurgeAtDoesNotMeanPurged() {
        // given — 스윕 주기(기본 1시간)만큼은 "예정 시각이 지났는데 아직 살아있는" 상태가 정상이다.
        LocalDateTime at = LocalDateTime.now().minusDays(GRACE_DAYS + 3L);

        // when
        AugmentDiscardStateResponse state = mapper(true).toResponse(marked(at));

        // then
        assertThat(state.purgeAt()).isBefore(LocalDateTime.now());
        assertThat(state.purged()).isFalse();
        assertThat(state.restorable()).isTrue();
    }
}
