package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LsLabelRepository#findByLabelNmIgnoreCaseAndUseYn} 방어적 top-1 단위 테스트.
 *
 * <p>V120 부분 유니크 인덱스가 활성 근사중복을 원천 차단하지만, 인덱스 이전 레거시 데이터나
 * 인덱스 일시 부재 상황에서도 다중 결과가 조회 로직을 크래시시키지 않도록(belt-and-suspenders)
 * default 메서드가 최소 labelId 1건만 반환함을 검증한다.
 */
class LsLabelRepositoryTopOneDefenseTest {

    private static LsLabel labelWithId(Long id, String name) {
        LsLabel label = LsLabel.create(name, "#E74C3C", "BBOX", 1, "seed");
        try {
            java.lang.reflect.Field f = LsLabel.class.getDeclaredField("labelId");
            f.setAccessible(true);
            f.set(label, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return label;
    }

    @Test
    @DisplayName("findLabelIdByName_가상의_다중결과에도_예외없이_top1_반환한다")
    void 다중결과_top1_반환() {
        // given — 정규화 이름 조회가 (레거시 근사중복 가정) 2건을 반환
        LsLabelRepository repo = mock(LsLabelRepository.class);
        LsLabel first = labelWithId(1L, "dup");
        LsLabel second = labelWithId(2L, "dup");
        when(repo.findActiveByNormalizedName(eq("dup"), eq("Y"), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(repo.findByLabelNmIgnoreCaseAndUseYn("dup", "Y")).thenCallRealMethod();

        // when
        Optional<LsLabel> result = repo.findByLabelNmIgnoreCaseAndUseYn("dup", "Y");

        // then — 예외 없이 최소 labelId(정렬 후 첫 행) 1건 반환
        assertThat(result).isPresent();
        assertThat(result.get().getLabelId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("findByLabelNmIgnoreCaseAndUseYn_결과없으면_Optional_empty")
    void 결과없음_empty() {
        // given
        LsLabelRepository repo = mock(LsLabelRepository.class);
        when(repo.findActiveByNormalizedName(eq("none"), eq("Y"), any(Pageable.class)))
                .thenReturn(List.of());
        when(repo.findByLabelNmIgnoreCaseAndUseYn("none", "Y")).thenCallRealMethod();

        // when / then
        assertThat(repo.findByLabelNmIgnoreCaseAndUseYn("none", "Y")).isEmpty();
    }
}
