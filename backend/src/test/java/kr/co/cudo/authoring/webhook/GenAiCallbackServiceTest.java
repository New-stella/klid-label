package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.webhook.dto.GenAiCallbackRequest;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentOutcome;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import kr.co.cudo.authoring.webhook.service.GenAiCallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 생성형 AI 웹훅 수신부 단위 테스트 — Phase 7-A2.
 *
 * <p>검증 축: ①발급 게이트(무단 주입 401) ②RUNNING 은 진행만 ③전 job 성공 시에만 확정
 * ④부분 실패 fail-closed ⑤중복 수신 멱등 ⑥외부 경로 순회 차단.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenAiCallbackServiceTest {

    @Mock private LsDataAugJobRepository jobRepository;
    @Mock private LsDataAugJobFileRepository jobFileRepository;
    @Mock private LsDataAugRepository augRepository;
    @Mock private AugmentResultService augmentResultService;
    @Mock private VideoArtifactRootResolver artifactRootResolver;
    @Mock private kr.co.cudo.authoring.observability.metrics.AugmentMetrics metrics;

    private GenAiCallbackService service;

    private static final Long AUG_SN = 700L;

    @BeforeEach
    void setUp() {
        // 롤업 규칙은 만료 스윕과 공유하는 단일 원천(AugmentJobRollup)이다 — 실제 구현을 그대로 쓰고
        //   확정 호출(AugmentResultService)만 목킹해 기존 검증 축을 유지한다.
        // 성공 산출물 적용(경로 검증 + 되붙임 + 성공 종결)은 웹훅/결과조회 회수 공용 단일 원천
        //   AugmentJobSuccessApplier 가 담당한다 — 실제 구현을 그대로 써야 기존 검증 축
        //   (verifyExternalReadablePath 호출 여부·건수 불일치 fail-closed)이 유지된다.
        service = new GenAiCallbackService(
                jobRepository, augRepository,
                new kr.co.cudo.authoring.webhook.service.AugmentJobRollup(augmentResultService),
                new kr.co.cudo.authoring.webhook.service.AugmentJobSuccessApplier(
                        jobFileRepository, artifactRootResolver, metrics));
        when(augRepository.findByDataAugSnForUpdate(AUG_SN))
                .thenReturn(Optional.of(pendingAug()));
        // 인계 서비스는 목킹 대상이지만 판정 enum 을 돌려줘야 롤업 결과가 성립한다(기본 = 정상 적용).
        when(augmentResultService.handle(any())).thenReturn(AugmentApplyResult.APPLIED);
    }

    // ─── 픽스처 ───────────────────────────────────────────────

    private static LsDataAug pendingAug() {
        return LsDataAug.createRequested(1L, LsDataAug.AUG_WINTER, "1", "AUG-K", null);
    }

    /** 위탁 선기록 상태(RECEIVED) job 1건. */
    private static LsDataAugJob issuedJob(int seq, String requestId, String externalJobId) {
        LsDataAugJob job = LsDataAugJob.createIssued(AUG_SN, seq, requestId, 100);
        if (externalJobId != null) {
            job.markAccepted(externalJobId);
        }
        return job;
    }

    private static LsDataAugJob succeededJob(int seq, String requestId) {
        LsDataAugJob job = issuedJob(seq, requestId, "job-" + seq);
        job.markSucceeded("job-" + seq);
        return job;
    }

    private void givenJobs(LsDataAugJob... jobs) {
        List<LsDataAugJob> list = List.of(jobs);
        when(jobRepository.findByDataAugSnOrderByJobSeqAsc(AUG_SN)).thenReturn(list);
        for (LsDataAugJob job : list) {
            when(jobRepository.findByIdempotencyKey(job.getIdempotencyKey()))
                    .thenReturn(Optional.of(job));
        }
        // 위탁 항목 1건(픽스처의 SUCCEEDED 콜백이 results 1건이라 건수 일치).
        givenIssuedFiles(1);
    }

    /** 위탁 시점에 못박은 항목(FILE_SEQ 오름차순) — 결과와 짝지을 대상. */
    private List<LsDataAugJobFile> givenIssuedFiles(int count) {
        List<LsDataAugJobFile> files = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            files.add(LsDataAugJobFile.issued(1L, i, 600L + i));
        }
        when(jobFileRepository.findByAugJobSnOrderByFileSeqAsc(any())).thenReturn(files);
        return files;
    }

    private static GenAiCallbackRequest succeeded(String requestId, String jobId) {
        return new GenAiCallbackRequest(requestId, jobId, "SUCCEEDED", 100, "COMPLETED", "t",
                List.of(new GenAiCallbackRequest.ResultItem(
                        "g1", "IMAGE", "/storage/genai/" + jobId + "/001_gen.jpg", null)),
                null, null);
    }

    private static GenAiCallbackRequest running(String requestId, String jobId, int progress) {
        return new GenAiCallbackRequest(requestId, jobId, "RUNNING", progress, "INFERENCE", "t",
                null, null, null);
    }

    private static GenAiCallbackRequest failed(String requestId, String jobId) {
        return new GenAiCallbackRequest(requestId, jobId, "FAILED", null, null, "t", null,
                "MODEL_EXECUTION_FAILED", "입력 파일을 읽을 수 없습니다");
    }

    // ─── 테스트 ───────────────────────────────────────────────

    @Test
    @DisplayName("우리가_발급하지_않은_request_id_웹훅은_401")
    void unknownRequestId_returns401() {
        when(jobRepository.findByIdempotencyKey("FORGED")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handle(succeeded("FORGED", "job-1")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("RUNNING_웹훅은_진행상태만_갱신하고_결과처리를_하지_않는다")
    void runningCallback_onlyUpdatesProgress() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);

        assertThat(service.handle(running("AUG-K-1", "job-1", 50)).applied()).isTrue();

        assertThat(job.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_RUNNING);
        verify(augmentResultService, never()).handle(any());
        verify(artifactRootResolver, never()).verifyExternalReadablePath(any());
    }

    @Test
    @DisplayName("SUCCEEDED_웹훅_수신시_해당_job_이_SUCCEEDED_로_갱신된다")
    void succeededCallback_marksJobSucceeded() {
        LsDataAugJob job1 = issuedJob(1, "AUG-K-1", "job-1");
        LsDataAugJob job2 = issuedJob(2, "AUG-K-2", "job-2");
        givenJobs(job1, job2);

        // 롤업 보류(DEFERRED) → applied:false. job 자체는 갱신되지만 증강 1건은 아직 인계되지 않았다.
        assertThat(service.handle(succeeded("AUG-K-1", "job-1")).applied()).isFalse();

        assertThat(job1.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
        // job2 가 아직 종결 전이므로 증강 1건은 확정되지 않는다.
        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("SUCCEEDED_수신시_산출경로가_위탁항목에_순서대로_되붙는다")
    void succeededCallback_bindsOutputPathsToIssuedFiles() {
        // given — 위탁 항목 2건 + 결과 2건.
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);
        List<LsDataAugJobFile> files = givenIssuedFiles(2);
        GenAiCallbackRequest req = new GenAiCallbackRequest(
                "AUG-K-1", "job-1", "SUCCEEDED", 100, "COMPLETED", "t",
                List.of(new GenAiCallbackRequest.ResultItem("g1", "IMAGE", "/storage/genai/job-1/1.jpg", null),
                        new GenAiCallbackRequest.ResultItem("g2", "IMAGE", "/storage/genai/job-1/2.jpg", null)),
                null, null);

        // when
        service.handle(req);

        // then — results[] 순서와 FILE_SEQ 순서가 1:1 로 맞물려 적재된다(버려지지 않는다).
        assertThat(files).extracting(LsDataAugJobFile::getResultFilePathNm)
                .containsExactly("/storage/genai/job-1/1.jpg", "/storage/genai/job-1/2.jpg");
        verify(jobFileRepository).saveAll(files);
        assertThat(job.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    @Test
    @DisplayName("results_개수가_위탁_input_files_개수와_다르면_증강이_실패처리된다")
    void resultCountMismatch_failsClosed() {
        // given — 위탁은 3건인데 결과가 1건만 왔다(순서 대응이 성립하지 않는다).
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);
        List<LsDataAugJobFile> files = givenIssuedFiles(3);

        // when
        service.handle(succeeded("AUG-K-1", "job-1"));

        // then — 성공으로 접수하지 않고 job 을 실패로 종결 → 롤업이 증강 1건을 REJECTED 로 끝낸다.
        assertThat(job.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_FAILED);
        assertThat(job.getErrorCode()).isEqualTo(LsDataAugJob.ERR_RESULT_COUNT_MISMATCH);
        assertThat(files).allSatisfy(f -> assertThat(f.getResultFilePathNm()).isNull());
        ArgumentCaptor<AugmentOutcome> captor = ArgumentCaptor.forClass(AugmentOutcome.class);
        verify(augmentResultService).handle(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
    }

    @Test
    @DisplayName("위탁항목이_없는_job_의_SUCCEEDED_는_실패처리된다_구위탁_fail_closed")
    void missingIssuedFiles_failsClosed() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);
        givenIssuedFiles(0);

        service.handle(succeeded("AUG-K-1", "job-1"));

        assertThat(job.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_FAILED);
        assertThat(job.getErrorCode()).isEqualTo(LsDataAugJob.ERR_RESULT_COUNT_MISMATCH);
    }

    @Test
    @DisplayName("전체_job_이_SUCCEEDED_여야_증강_결과가_확정된다")
    void rollUpOnlyWhenAllJobsSucceeded() {
        LsDataAugJob job1 = succeededJob(1, "AUG-K-1");
        LsDataAugJob job2 = issuedJob(2, "AUG-K-2", "job-2");
        givenJobs(job1, job2);

        service.handle(succeeded("AUG-K-2", "job-2"));

        ArgumentCaptor<AugmentOutcome> captor = ArgumentCaptor.forClass(AugmentOutcome.class);
        verify(augmentResultService).handle(captor.capture());
        assertThat(captor.getValue().dataAugSn()).isEqualTo(AUG_SN);
        assertThat(captor.getValue().success()).isTrue();
        // 생성형 AI 는 영상 파일을 돌려주지 않는다 → 부모 경로 폴백을 위해 null 이어야 한다.
        assertThat(captor.getValue().rawFilePathNm()).isNull();
    }

    @Test
    @DisplayName("3개_job_중_1개가_FAILED_면_증강이_실패처리되고_부분결과로_확정되지_않는다")
    void partialFailure_failsClosed() {
        LsDataAugJob job1 = succeededJob(1, "AUG-K-1");
        LsDataAugJob job2 = issuedJob(2, "AUG-K-2", "job-2");
        LsDataAugJob job3 = issuedJob(3, "AUG-K-3", "job-3");
        givenJobs(job1, job2, job3);

        // job2 실패 → 아직 job3 이 남아 확정 보류
        service.handle(failed("AUG-K-2", "job-2"));
        verify(augmentResultService, never()).handle(any());
        assertThat(job2.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_FAILED);
        assertThat(job2.getErrorCode()).isEqualTo("MODEL_EXECUTION_FAILED");

        // job3 성공 → 전 job 종결. 2/3 성공이지만 부분 결과로 확정하지 않는다.
        service.handle(succeeded("AUG-K-3", "job-3"));

        ArgumentCaptor<AugmentOutcome> captor = ArgumentCaptor.forClass(AugmentOutcome.class);
        verify(augmentResultService).handle(captor.capture());
        assertThat(captor.getValue().success())
                .as("job 1건이라도 실패하면 증강은 실패로 종결돼야 한다(프레임 누락 산출물 차단)")
                .isFalse();
    }

    @Test
    @DisplayName("같은_웹훅이_두_번_도착해도_결과가_중복_생성되지_않는다")
    void duplicateCallback_isIdempotent() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);

        assertThat(service.handle(succeeded("AUG-K-1", "job-1")).applied()).isTrue();
        assertThat(service.handle(succeeded("AUG-K-1", "job-1")).applied()).isFalse();

        verify(augmentResultService, org.mockito.Mockito.times(1)).handle(any());
    }

    @Test
    @DisplayName("output_file_path_가_허용루트_밖이면_거부된다")
    void outputPathOutsideAllowedRoot_isRejected() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 원본 저장 경로입니다."))
                .when(artifactRootResolver).verifyExternalReadablePath(any());

        GenAiCallbackRequest req = new GenAiCallbackRequest(
                "AUG-K-1", "job-1", "SUCCEEDED", 100, "COMPLETED", "t",
                List.of(new GenAiCallbackRequest.ResultItem(
                        "g1", "IMAGE", "/storage/../etc/passwd", null)),
                null, null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(job.getJobSttsCd())
                .as("거부된 콜백은 상태를 바꾸지 않아 올바른 경로로 재전송할 수 있어야 한다")
                .isEqualTo(LsDataAugJob.STTS_RECEIVED);
        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("SUCCEEDED_인데_results_가_없으면_400_이며_확정되지_않는다")
    void succeededWithoutResults_isRejected() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);
        GenAiCallbackRequest req = new GenAiCallbackRequest(
                "AUG-K-1", "job-1", "SUCCEEDED", 100, "COMPLETED", "t", null, null, null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("202_로_받아둔_job_id_와_다른_웹훅은_409_오배송_차단")
    void jobIdMismatch_returns409() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);

        assertThatThrownBy(() -> service.handle(succeeded("AUG-K-1", "job-OTHER")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    /**
     * 다청크 위탁의 <b>부분 도착</b> 회신 계약 고정 (적대검증 MEDIUM-2).
     *
     * <p>구 구현은 롤업 결과를 버리고 무조건 {@code APPLIED} 를 회신해, 2청크 중 1청크만 SUCCEEDED 인
     * 정상 진행 구간에도 {@code applied:true} 가 나갔다(외부가 "증강 인계 완료" 로 오해). {@code applied}
     * 의 의미는 컨트롤러 javadoc·docs/v2-wiki/14-augmentation.md 가 진실원이며 = "증강 1건의 상태가
     * 실제로 전이됐는가" 다. 외부 명세서 v1.1 은 콜백 응답 본문을 규정하지 않는다.
     */
    @Test
    @DisplayName("다청크_위탁의_부분도착은_applied_false_로_회신한다_롤업_보류")
    void partialChunkArrival_isReportedAsNotApplied() {
        // given — 2청크 위탁(job1/job2) 중 job1 만 SUCCEEDED 로 도착.
        LsDataAugJob job1 = issuedJob(1, "AUG-K-1", "job-1");
        LsDataAugJob job2 = issuedJob(2, "AUG-K-2", "job-2");
        givenJobs(job1, job2);

        // when
        AugmentApplyResult result = service.handle(succeeded("AUG-K-1", "job-1"));

        // then — 롤업 보류(DEFERRED) → applied:false. job 상태 갱신은 그대로 유지된다.
        assertThat(result).isEqualTo(AugmentApplyResult.DEFERRED);
        assertThat(result.applied()).isFalse();
        assertThat(job1.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
        verify(augmentResultService, never()).handle(any());
    }

    @Test
    @DisplayName("마지막_청크_도착으로_인계가_끝나면_applied_true_로_회신한다")
    void lastChunkArrival_isReportedAsApplied() {
        LsDataAugJob job1 = succeededJob(1, "AUG-K-1");
        LsDataAugJob job2 = issuedJob(2, "AUG-K-2", "job-2");
        givenJobs(job1, job2);
        when(augmentResultService.handle(any())).thenReturn(AugmentApplyResult.APPLIED);

        AugmentApplyResult result = service.handle(succeeded("AUG-K-2", "job-2"));

        assertThat(result.applied()).isTrue();
    }

    /**
     * 정책 보류({@code WITHHELD_*})는 2026-07-29 로 폐기됐다 — 신고 구간 차단은 파생 생성이 아니라
     * 외부 위탁 쪽에서, 그것도 거부로 종결된다. 따라서 인계가 실제로 이뤄지면 응답은
     * {@code applied:true} 이고 사유 코드는 실리지 않는다.
     */
    @Test
    @DisplayName("job_갱신이_끝나면_applied_true_로_회신하고_사유코드는_없다")
    void succeededRollup_isReportedAsApplied() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);
        when(augmentResultService.handle(any())).thenReturn(AugmentApplyResult.APPLIED);

        AugmentApplyResult result = service.handle(succeeded("AUG-K-1", "job-1"));

        assertThat(result.applied()).isTrue();
        assertThat(result.reasonCode()).isNull();
        assertThat(job.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
    }

    @Test
    @DisplayName("증강행_잠금을_job_갱신보다_먼저_잡아_롤업_유실을_막는다")
    void locksAugRowBeforeMutatingJob() {
        LsDataAugJob job = issuedJob(1, "AUG-K-1", "job-1");
        givenJobs(job);

        service.handle(succeeded("AUG-K-1", "job-1"));

        // 잠금 조회가 실제로 수행돼야 한다 — 이 잠금이 없으면 마지막 job 동시 콜백에서 두
        // 트랜잭션이 서로의 미커밋 갱신을 못 봐 롤업이 통째로 유실된다.
        verify(augRepository).findByDataAugSnForUpdate(AUG_SN);
    }

}
