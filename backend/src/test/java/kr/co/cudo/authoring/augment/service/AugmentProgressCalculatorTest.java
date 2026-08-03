package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentProgressStatus;
import kr.co.cudo.authoring.augment.dto.AugmentProgressUnavailableReason;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 증강 진행상태 <b>산출 규칙</b> 검증 — 순수 계산이라 Spring 컨텍스트 없이 돈다.
 *
 * <p>고정하는 계약 셋:
 * <ul>
 *   <li>진행률 = <b>파일 수 가중 평균</b>(min·단순평균 아님)</li>
 *   <li>상태 집계 = 취소 최우선 → 부분 실패 = 전체 실패</li>
 *   <li>degrade 시 진행률은 <b>null</b>(거짓 값보다 "모름" 이 안전하다)</li>
 * </ul>
 */
class AugmentProgressCalculatorTest {

    private static AugmentProgressCalculator.JobView job(int seq, String status, int files,
                                                         Integer externalProgress) {
        return new AugmentProgressCalculator.JobView(seq, status, files, externalProgress);
    }

    @Test
    @DisplayName("진행률은_청크_job_들의_파일수_가중평균이다")
    void progressIsFileCountWeightedAverage() {
        // given: 100장 청크가 완료(100%), 1장 청크는 0% — min 이면 0, 단순평균이면 50 이 된다
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_SUCCEEDED, 100, null),
                job(2, LsDataAugJob.STTS_RUNNING, 1, 0));

        // when
        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_PENDING, jobs, null);

        // then: (100×100 + 1×0) / 101 = 99.0… → 99
        assertThat(result.progress())
                .as("min 이면 0, 단순평균이면 50 — 파일 수 가중이어야 99 다")
                .isEqualTo(99);
        assertThat(result.status()).isEqualTo(AugmentProgressStatus.RUNNING);
    }

    @Test
    @DisplayName("청크_3개중_1개만_완료돼도_전체가_0퍼센트로_보이지_않는다")
    void partiallyCompletedChunksDoNotCollapseToZero() {
        // given: 동일 크기 3청크 중 1개 완료
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_SUCCEEDED, 10, null),
                job(2, LsDataAugJob.STTS_RUNNING, 10, 0),
                job(3, LsDataAugJob.STTS_RECEIVED, 10, null));

        // when
        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_PENDING, jobs, null);

        // then
        assertThat(result.progress()).isEqualTo(33);
        assertThat(result.status())
                .as("진행률이 0 보다 큰데 RECEIVED 로 표시하면 '접수됨 33%' 라는 자기모순이 화면에 나온다")
                .isEqualTo(AugmentProgressStatus.RUNNING);
    }

    @Test
    @DisplayName("진행률이_0_이면_접수상태_그대로_표시된다")
    void zeroProgressStaysReceived() {
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_RECEIVED, 10, 0),
                job(2, LsDataAugJob.STTS_RECEIVED, 10, null));

        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_PENDING, jobs, null);

        assertThat(result.status()).isEqualTo(AugmentProgressStatus.RECEIVED);
        assertThat(result.progress()).isZero();
        assertThat(result.nextPollAfterMs()).isEqualTo(AugmentProgressCalculator.POLL_RECEIVED_MS);
    }

    @Test
    @DisplayName("위탁_거부_기록은_파일수가_0이어도_분모에서_사라지지_않는다")
    void rejectedRecordKeepsMinimumWeight() {
        // given: 위탁 거부 기록(TOT_NOCS=0, FAILED) 1건 + 진행 0% 청크 1건
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_FAILED, 0, null),
                job(2, LsDataAugJob.STTS_RUNNING, 1, 0));

        // when
        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_PENDING, jobs, null);

        // then: 가중치가 0 이면 그 청크가 사라져 0% 가 된다 — 최소 1 이라 50% 다
        assertThat(result.progress()).isEqualTo(50);
    }

    @Test
    @DisplayName("전_청크_종결시_하나라도_FAILED_면_FAILED_로_집계된다")
    void anyFailedChunkMakesWholeFailed() {
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_SUCCEEDED, 10, null),
                job(2, LsDataAugJob.STTS_FAILED, 10, null));

        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_PENDING, jobs, null);

        assertThat(result.status()).isEqualTo(AugmentProgressStatus.FAILED);
        assertThat(result.progress()).isEqualTo(100);
        assertThat(result.nextPollAfterMs()).as("종결이면 더 폴링하지 않는다").isZero();
    }

    @Test
    @DisplayName("증강행이_CANCELED_면_청크_상태와_무관하게_CANCELED_다")
    void canceledAugmentWinsOverChunkStates() {
        // given: 늦게 도착한 성공 웹훅으로 청크는 SUCCEEDED 인데 증강은 취소됐다
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_SUCCEEDED, 10, null));

        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_CANCELED, jobs, null);

        assertThat(result.status()).isEqualTo(AugmentProgressStatus.CANCELED);
        assertThat(result.cancelable()).isFalse();
    }

    @Test
    @DisplayName("degrade_사유가_있으면_진행률은_null_이고_사유가_그대로_실린다")
    void degradeYieldsNullProgressWithReason() {
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_RUNNING, 10, null));

        AugmentProgressCalculator.Result result = AugmentProgressCalculator.compute(
                LsDataAug.STTS_PENDING, jobs, AugmentProgressUnavailableReason.TRANSIENT_ERROR);

        assertThat(result.progress()).isNull();
        assertThat(result.reason()).isEqualTo(AugmentProgressUnavailableReason.TRANSIENT_ERROR);
        assertThat(result.nextPollAfterMs())
                .as("일시 장애에는 폴링을 완화한다(서킷을 계속 열지 않도록)")
                .isEqualTo(AugmentProgressCalculator.POLL_TRANSIENT_ERROR_MS);
    }

    @Test
    @DisplayName("noop_과_일시장애는_폴링_힌트까지_다르게_유도된다")
    void noopAndTransientErrorAreDistinguished() {
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_RECEIVED, 10, null));

        AugmentProgressCalculator.Result noop = AugmentProgressCalculator.compute(
                LsDataAug.STTS_PENDING, jobs, AugmentProgressUnavailableReason.NOOP);
        AugmentProgressCalculator.Result transientError = AugmentProgressCalculator.compute(
                LsDataAug.STTS_PENDING, jobs, AugmentProgressUnavailableReason.TRANSIENT_ERROR);

        assertThat(noop.reason()).isNotEqualTo(transientError.reason());
        assertThat(noop.nextPollAfterMs())
                .as("미연동은 폴링해도 값이 생기지 않는다 — 사실상 중단에 가깝게 늦춘다")
                .isGreaterThan(transientError.nextPollAfterMs());
    }

    @Test
    @DisplayName("청크가_없는_PENDING_증강은_에러가_아니라_ACK대기중_이다")
    void pendingWithoutChunksIsAwaitingAck() {
        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_PENDING, List.of(), null);

        assertThat(result.status()).isEqualTo(AugmentProgressStatus.RECEIVED);
        assertThat(result.progress()).isNull();
        assertThat(result.reason()).isEqualTo(AugmentProgressUnavailableReason.AWAITING_ACK);
        assertThat(result.cancelable()).isTrue();
    }

    @Test
    @DisplayName("외부_진행률이_계약_범위를_벗어나도_0에서_100_으로_클램프된다")
    void externalProgressIsClampedIntoContractRange() {
        List<AugmentProgressCalculator.JobView> jobs = List.of(
                job(1, LsDataAugJob.STTS_RUNNING, 10, 9999),
                job(2, LsDataAugJob.STTS_RUNNING, 10, -50));

        AugmentProgressCalculator.Result result =
                AugmentProgressCalculator.compute(LsDataAug.STTS_PENDING, jobs, null);

        assertThat(result.progress()).isEqualTo(50);
    }
}
