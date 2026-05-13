package kr.co.cudo.authoring.batch.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3: BbHint record 의 trackId 필드 검증.
 *
 * <p>record 는 단일 canonical constructor 만 가능하므로 5-arg (trackId 포함) 가 정식 시그니처이고,
 * 기존 4-arg 호출처는 모두 trackId=null 명시로 마이그레이션되었다.
 */
class BbHintTest {

    @Test
    @DisplayName("BbHint_5arg_생성자는_trackId_저장")
    void fiveArgConstructorRetainsTrackId() {
        BbHint hint = new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.85, 42);

        assertThat(hint.srcSn()).isEqualTo(10L);
        assertThat(hint.label()).isEqualTo("person");
        assertThat(hint.points()).containsExactly(1.0, 2.0, 3.0, 4.0);
        assertThat(hint.score()).isEqualTo(0.85);
        assertThat(hint.trackId()).isEqualTo(42);
    }

    @Test
    @DisplayName("BbHint_trackId_null_허용_(트래커가_저신뢰_객체에_ID_안_부여)")
    void trackIdNullAllowed() {
        BbHint hint = new BbHint(20L, "car", List.of(5.0, 6.0, 7.0, 8.0), 0.7, null);

        assertThat(hint.trackId()).isNull();
        assertThat(hint.label()).isEqualTo("car");
    }
}
