package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentJobRollup;
import kr.co.cudo.authoring.webhook.service.AugmentOutcome;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 분할 위탁 job 롤업 규칙 단위 테스트 (Phase 8-A).
 *
 * <p>롤업은 웹훅 수신부와 만료 스윕이 <b>공유</b>하는 판정이다. 만료 종결(FAILED)이 섞이면
 * 증강 1건은 절대 성공으로 확정되지 않아야 한다(부분 실패 = 전체 실패).
 */
@ExtendWith(MockitoExtension.class)
class AugmentJobRollupTest {

    private static final Long AUG_SN = 900L;

    @Mock private AugmentResultService augmentResultService;

    private AugmentJobRollup rollup() {
        return new AugmentJobRollup(augmentResultService);
    }

    private static LsDataAugJob succeeded(int seq) {
        LsDataAugJob job = LsDataAugJob.createIssued(AUG_SN, seq, "K-" + seq, 1);
        job.markSucceeded("J-" + seq);
        return job;
    }

    private static LsDataAugJob expired(int seq) {
        LsDataAugJob job = LsDataAugJob.createIssued(AUG_SN, seq, "K-" + seq, 1);
        job.markFailed(LsDataAugJob.ERR_EXPIRED, "무갱신 경과로 만료 종결");
        return job;
    }

    private static LsDataAugJob running(int seq) {
        LsDataAugJob job = LsDataAugJob.createIssued(AUG_SN, seq, "K-" + seq, 1);
        job.markRunning("J-" + seq);
        return job;
    }

    @Test
    @DisplayName("만료된_job_이_있으면_증강이_성공으로_확정되지_않는다")
    void expiredJobForcesFailureRollup() {
        // given: 1건 성공 + 1건 만료 종결
        List<LsDataAugJob> jobs = List.of(succeeded(1), expired(2));

        // when
        AugmentApplyResult rolledUp = rollup().rollUpIfAllTerminal(AUG_SN, jobs, "J-1");

        // then: 만료는 성공이 아니다 — 증강 1건은 실패로 확정된다(fail-closed)
        assertThat(rolledUp).isNotEqualTo(AugmentApplyResult.DEFERRED);
        ArgumentCaptor<AugmentOutcome> outcome = ArgumentCaptor.forClass(AugmentOutcome.class);
        verify(augmentResultService).handle(outcome.capture());
        assertThat(outcome.getValue().success())
                .as("만료 종결이 섞였는데 성공으로 둔갑하면 불완전 프레임셋이 학습데이터가 된다")
                .isFalse();
    }

    @Test
    @DisplayName("전_job_이_성공이면_증강이_성공으로_확정된다")
    void allSucceededRollsUpAsSuccess() {
        // given
        List<LsDataAugJob> jobs = List.of(succeeded(1), succeeded(2));

        // when
        AugmentApplyResult rolledUp = rollup().rollUpIfAllTerminal(AUG_SN, jobs, "J-1");

        // then
        assertThat(rolledUp).isNotEqualTo(AugmentApplyResult.DEFERRED);
        ArgumentCaptor<AugmentOutcome> outcome = ArgumentCaptor.forClass(AugmentOutcome.class);
        verify(augmentResultService).handle(outcome.capture());
        assertThat(outcome.getValue().success()).isTrue();
    }

    @Test
    @DisplayName("비종결_job_이_남아있으면_롤업이_보류된다")
    void pendingJobDefersRollup() {
        // given
        List<LsDataAugJob> jobs = List.of(succeeded(1), running(2));

        // when
        AugmentApplyResult rolledUp = rollup().rollUpIfAllTerminal(AUG_SN, jobs, "J-1");

        // then
        assertThat(rolledUp).isEqualTo(AugmentApplyResult.DEFERRED);
        verify(augmentResultService, never()).handle(any());
    }
}
