package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkRequest;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkResponse;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueResponse;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관제 인입 재큐 서비스({@link ControlIngestRequeueService}) 단위 테스트 — 설계 §6-0-1-b.
 *
 * <p>검증 축:
 * <ul>
 *   <li><b>가역 종결</b> — {@code FAILED} 행만 되살리고, 그 외 상태는 409 로 거부한다(중복 적재 차단).</li>
 *   <li><b>404 vs 409 구분</b> — "없는 행"과 "상태가 달라 못 되살림"을 섞으면 운영자가 원인을 못 찾는다.</li>
 *   <li><b>상한 강제</b>(CWE-770) — 일괄 재큐는 상한을 넘겨 조회·갱신하지 않는다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ControlIngestRequeueServiceTest {

    private static final long RCPTN_SN = 4242L;
    private static final String ACTOR = "1";

    @Mock
    private LsDataIngestRepository ingestRepository;

    private ControlIngestRequeueService service;

    @BeforeEach
    void setUp() {
        service = new ControlIngestRequeueService(ingestRepository);
    }

    @Test
    @DisplayName("FAILED_인입행을_재큐하면_재큐건수와_남은_종결건수를_반환한다")
    void requeuesFailedRow() {
        // given
        when(ingestRepository.existsById(RCPTN_SN)).thenReturn(true);
        when(ingestRepository.requeueFailedForRetry(RCPTN_SN)).thenReturn(1);
        when(ingestRepository.countByPrcsSttsCd(LsDataIngest.PRCS_STTS_FAILED)).thenReturn(7L);

        // when
        ControlIngestRequeueResponse response = service.requeue(RCPTN_SN, ACTOR);

        // then
        assertThat(response.rcptnSn()).isEqualTo(RCPTN_SN);
        assertThat(response.requeued()).isEqualTo(1);
        assertThat(response.remainingFailed()).isEqualTo(7L);
    }

    @Test
    @DisplayName("존재하지_않는_인입행은_404다")
    void notFoundWhenRowMissing() {
        // given
        when(ingestRepository.existsById(RCPTN_SN)).thenReturn(false);

        // when / then — 없는 행을 409 로 뭉뚱그리면 운영자가 원인을 구분할 수 없다.
        assertThatThrownBy(() -> service.requeue(RCPTN_SN, ACTOR))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(ingestRepository, never()).requeueFailedForRetry(anyLong());
    }

    @Test
    @DisplayName("FAILED가_아닌_행은_409로_거부한다")
    void conflictWhenNotFailed() {
        // given — 조건부 UPDATE 가 0행(이미 재큐됐거나 처리 중이거나 성공 종결됐다).
        //   여기서 되살리면 이미 적재된 영상이 중복 적재된다(CWE-362).
        when(ingestRepository.existsById(RCPTN_SN)).thenReturn(true);
        when(ingestRepository.requeueFailedForRetry(RCPTN_SN)).thenReturn(0);

        // when / then
        assertThatThrownBy(() -> service.requeue(RCPTN_SN, ACTOR))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("식별자가_null이면_400이다")
    void invalidInputWhenIdNull() {
        assertThatThrownBy(() -> service.requeue(null, ACTOR))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("일괄_재큐는_요청_상한을_그대로_리포지토리에_전달한다")
    void bulkRequeuePassesLimit() {
        // given
        when(ingestRepository.requeueFailedBatch(25)).thenReturn(25);
        when(ingestRepository.countByPrcsSttsCd(LsDataIngest.PRCS_STTS_FAILED)).thenReturn(75L);

        // when
        ControlIngestRequeueBulkResponse response =
                service.requeueBatch(new ControlIngestRequeueBulkRequest(25), ACTOR);

        // then — 처리 건수와 잔여 건수를 응답에 담아 재호출로 이어서 회수할 수 있게 한다.
        assertThat(response.requeued()).isEqualTo(25);
        assertThat(response.limit()).isEqualTo(25);
        assertThat(response.remainingFailed()).isEqualTo(75L);
    }

    @Test
    @DisplayName("일괄_재큐_상한_미지정시_기본값이_적용된다")
    void bulkRequeueUsesDefaultLimit() {
        // given
        when(ingestRepository.requeueFailedBatch(ControlIngestRequeueBulkRequest.DEFAULT_LIMIT)).thenReturn(3);
        when(ingestRepository.countByPrcsSttsCd(LsDataIngest.PRCS_STTS_FAILED)).thenReturn(0L);

        // when
        ControlIngestRequeueBulkResponse response =
                service.requeueBatch(new ControlIngestRequeueBulkRequest(null), ACTOR);

        // then — 무제한이 아니라 기본 상한으로 접힌다(CWE-770).
        assertThat(response.limit()).isEqualTo(ControlIngestRequeueBulkRequest.DEFAULT_LIMIT);
        assertThat(response.requeued()).isEqualTo(3);
    }

    @Test
    @DisplayName("일괄_재큐는_상한을_넘겨_조회하지_않는다")
    void bulkRequeueRejectsOversizedLimit() {
        // given — 컨트롤러 @Valid 를 우회한 직접 호출에서도 상한을 지킨다(방어적 clamp).
        int oversized = ControlIngestRequeueBulkRequest.MAX_LIMIT + 1;

        // when / then — 한 트랜잭션이 수십만 행을 잠그면 인입 폴링·관제 INSERT 가 함께 멈춘다.
        assertThatThrownBy(() -> service.requeueBatch(new ControlIngestRequeueBulkRequest(oversized), ACTOR))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(ingestRepository, never()).requeueFailedBatch(anyInt());
    }

    @Test
    @DisplayName("일괄_재큐_대상이_없어도_예외가_아니라_0건_응답이다")
    void bulkRequeueWithNoTargetsIsNotAnError() {
        // given — "되살릴 게 없다"는 정상 결과다(일괄은 대상 집합이 비어 있을 수 있다).
        when(ingestRepository.requeueFailedBatch(anyInt())).thenReturn(0);
        when(ingestRepository.countByPrcsSttsCd(LsDataIngest.PRCS_STTS_FAILED)).thenReturn(0L);

        // when
        ControlIngestRequeueBulkResponse response =
                service.requeueBatch(new ControlIngestRequeueBulkRequest(10), ACTOR);

        // then
        assertThat(response.requeued()).isZero();
        assertThat(response.remainingFailed()).isZero();
    }
}
