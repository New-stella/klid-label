package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-052 — 예약 마킹의 활성화·마감 단위 검증.
 *
 * <p>고정하는 불변식 넷:
 * <ol>
 *   <li>활성화는 <b>조건부 UPDATE(원자 클레임)</b>로만 한다 — 조회 후 변경이면 2노드가 둘 다 통과한다.</li>
 *   <li>클레임에 실패하면 <b>이후 처리를 시작하지 않는다</b>(이벤트 미발행 = 중복 기동 금지).</li>
 *   <li>마감도 조건부 UPDATE 라 <b>예약이 아닌 마킹을 덮지 않는다</b>.</li>
 *   <li>예약이 없는 영상은 오류가 아니라 조용한 no-op 이다(사람이 직접 마킹하는 통상 경로).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MarkingActivationTxServiceTest {

    private static final Long RAW_SN = 9210L;

    @Mock
    private LsMarkingRepository markingRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MarkingActivationTxService service;

    private static LsMarking reserved(Long markingSn) {
        LsMarking marking = LsMarking.createReserved(
                RAW_SN, "[{\"frameIndex\":10,\"timestamp\":\"00:05\"}]", "1001", 30.0);
        ReflectionTestUtils.setField(marking, "markingSn", markingSn);
        return marking;
    }

    @Test
    @DisplayName("예약을_활성으로_깨울_때_조건부_UPDATE로_원자_클레임하고_이후_처리를_시작한다")
    void activatesReservationAtomicallyAndStartsDownstream() {
        // given — 예약 1건
        when(markingRepository.findByRawSnAndSttsCdOrderByRegDtDescMarkingSnDesc(
                RAW_SN, LsMarking.STATUS_RESERVED)).thenReturn(List.of(reserved(31L)));
        when(markingRepository.transitionMarkingIfStatus(
                31L, LsMarking.STATUS_RESERVED, LsMarking.STATUS_PENDING)).thenReturn(1);
        when(markingRepository.transitionAllByRawSnIfStatus(
                RAW_SN, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED)).thenReturn(0);

        // when
        Optional<Long> activated = service.activateReserved(RAW_SN);

        // then — RESERVED → PENDING 은 조건부 UPDATE 로만 일어난다(조회 후 save 가 아니다).
        assertThat(activated).contains(31L);
        verify(markingRepository).transitionMarkingIfStatus(
                31L, LsMarking.STATUS_RESERVED, LsMarking.STATUS_PENDING);
        verify(markingRepository, never()).save(any());

        // 그리고 사람이 마킹을 완료했을 때와 같은 이벤트로 잔여 배치를 기동한다.
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new MarkingCompletedEvent(RAW_SN, 31L));
    }

    @Test
    @DisplayName("다른_노드가_먼저_집어가면_이후_처리를_시작하지_않는다 — 중복_기동_금지")
    void doesNotStartDownstreamWhenClaimLost() {
        // given — 조건부 UPDATE 가 0건(=다른 노드가 이미 PENDING 으로 바꿔 놓음)
        when(markingRepository.findByRawSnAndSttsCdOrderByRegDtDescMarkingSnDesc(
                RAW_SN, LsMarking.STATUS_RESERVED)).thenReturn(List.of(reserved(32L)));
        when(markingRepository.transitionMarkingIfStatus(
                32L, LsMarking.STATUS_RESERVED, LsMarking.STATUS_PENDING)).thenReturn(0);

        // when
        Optional<Long> activated = service.activateReserved(RAW_SN);

        // then
        assertThat(activated).isEmpty();
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("예약이_2건이면_최신_1건만_깨우고_나머지는_마감한다 — 깨어날_수_없는_고아_방지")
    void closesSurplusReservations() {
        // given — 최신 먼저 정렬로 2건 (활성 마킹은 영상당 1건뿐이라 나머지는 깨어날 자리가 없다)
        when(markingRepository.findByRawSnAndSttsCdOrderByRegDtDescMarkingSnDesc(
                RAW_SN, LsMarking.STATUS_RESERVED)).thenReturn(List.of(reserved(34L), reserved(33L)));
        when(markingRepository.transitionMarkingIfStatus(
                34L, LsMarking.STATUS_RESERVED, LsMarking.STATUS_PENDING)).thenReturn(1);
        when(markingRepository.transitionAllByRawSnIfStatus(
                RAW_SN, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED)).thenReturn(1);

        // when
        Optional<Long> activated = service.activateReserved(RAW_SN);

        // then — 깨우는 것은 최신 1건, 잉여는 SKIPPED 로 마감된다.
        assertThat(activated).contains(34L);
        verify(markingRepository).transitionAllByRawSnIfStatus(
                RAW_SN, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED);
    }

    @Test
    @DisplayName("예약이_없으면_조용한_no_op — 사람이_직접_마킹하는_통상_경로")
    void noReservationIsSilentNoOp() {
        when(markingRepository.findByRawSnAndSttsCdOrderByRegDtDescMarkingSnDesc(
                RAW_SN, LsMarking.STATUS_RESERVED)).thenReturn(List.of());

        assertThat(service.activateReserved(RAW_SN)).isEmpty();
        assertThat(service.activateReserved(null)).isEmpty();

        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("예약_마감은_RESERVED만_SKIPPED로_바꾼다 — 활성_마킹을_덮지_않는다")
    void closeReservationsOnlyTouchesReserved() {
        when(markingRepository.transitionAllByRawSnIfStatus(
                RAW_SN, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED)).thenReturn(1);

        int closed = service.closeReservations(RAW_SN, "비식별 실패");

        assertThat(closed).isEqualTo(1);
        // 출발 상태가 RESERVED 로 고정된 조건부 UPDATE — 활성화와 겹쳐도 PENDING 을 덮지 않는다.
        verify(markingRepository).transitionAllByRawSnIfStatus(
                RAW_SN, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED);
        verify(markingRepository, never()).save(any());
    }

    @Test
    @DisplayName("마감할_예약이_없으면_0건이고_rawSn이_null이면_no_op — 멱등")
    void closeReservationsIsIdempotent() {
        when(markingRepository.transitionAllByRawSnIfStatus(
                RAW_SN, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED)).thenReturn(0);

        assertThat(service.closeReservations(RAW_SN, null)).isZero();
        assertThat(service.closeReservations(null, "무관")).isZero();
    }
}
