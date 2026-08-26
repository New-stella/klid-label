package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AutolabelPresence} 판정 계약 가드 — 술어 인자와 <b>조기 종료</b>를 못박는다.
 *
 * <p>실 DB 로 판정 결과를 검증하는 것은 {@code AutolabelPresenceIT} 다. 여기서는 리포지토리에
 * <b>무엇을 어떻게 물었는지</b>(출처 목록·페이지 크기·호출하지 않은 메서드)를 확인한다 — 조기 종료는
 * 결과값으로 드러나지 않아 결과 단언만으로는 회귀를 잡지 못한다.
 */
@ExtendWith(MockitoExtension.class)
class AutolabelPresenceTest {

    @Mock private LsDataLblRepository labelRepository;
    @InjectMocks private AutolabelPresence presence;

    @Test
    @DisplayName("존재_확인은_한_건만_읽고_전건_카운트를_세지_않는다")
    void existsReadsOnlyFirstRowAndNeverCounts() {
        // given — 자동 라벨이 여러 건 있어도 리포지토리는 한 건만 돌려준다(페이지 크기 1).
        when(labelRepository.findAutoLabelLblSnsByRawSn(anyLong(), any(), any(Pageable.class)))
                .thenReturn(List.of(11L));

        // when
        boolean exists = presence.exists(7L);

        // then ① 결과
        assertThat(exists).isTrue();

        // then ② 조기 종료 — 페이지 크기 1 로 물었다(수용기준 4).
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(labelRepository).findAutoLabelLblSnsByRawSn(eq(7L), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
        assertThat(pageable.getValue().getPageNumber()).isZero();

        // then ③ 전건 카운트로 위임하지 않는다 — 세고 나서 0 과 비교하면 전량을 훑는다.
        verify(labelRepository, never()).countAutoLabelByRawSn(anyLong(), any());
    }

    @Test
    @DisplayName("판정_술어에_넘기는_출처는_YOLO_와_SAM2_뿐이고_보간은_넣지_않는다")
    void passesOnlyAutolabelSourceCodes() {
        when(labelRepository.findAutoLabelLblSnsByRawSn(anyLong(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        presence.exists(7L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> sources = ArgumentCaptor.forClass(Collection.class);
        verify(labelRepository).findAutoLabelLblSnsByRawSn(eq(7L), sources.capture(), any(Pageable.class));
        assertThat(sources.getValue())
                .containsExactlyInAnyOrder(LsDataLbl.SRC_YOLO, LsDataLbl.SRC_SAM2)
                .doesNotContain(LsDataLbl.SRC_INTERPOLATE, LsDataLbl.SRC_VLM);
        // 목록은 클래스 상수 하나가 소유한다 — 술어마다 문자열을 복제하지 않는다.
        assertThat(AutolabelPresence.AUTOLABEL_SRC_CDS)
                .containsExactly(LsDataLbl.SRC_YOLO, LsDataLbl.SRC_SAM2);
    }

    @Test
    @DisplayName("rawSn_이_null_이면_조회하지_않고_없음으로_답한다")
    void nullRawSnIsAbsentWithoutQuery() {
        assertThat(presence.exists(null)).isFalse();
        assertThat(presence.count(null)).isZero();
        verifyNoInteractions(labelRepository);
    }

    @Test
    @DisplayName("건수는_같은_술어의_카운트_쿼리를_그대로_돌려준다")
    void countDelegatesToCountQuery() {
        when(labelRepository.countAutoLabelByRawSn(eq(7L), any())).thenReturn(3L);

        assertThat(presence.count(7L)).isEqualTo(3L);

        verify(labelRepository, never())
                .findAutoLabelLblSnsByRawSn(anyLong(), any(), any(Pageable.class));
    }
}
