package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentInputFile;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitCommand;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitResult;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 증강 외부 위탁 분할 실행 서비스 테스트 — Phase 7-A1.
 *
 * <h3>검증 축</h3>
 * <ul>
 *   <li>100장 상한 분할 위탁 (250장 → 100/100/50)</li>
 *   <li>중간 청크 실패 격리 + 실패 사유 DB 기록(조용한 유실 금지)</li>
 *   <li>PII fail-closed — 비식별 경로 부재 시 위탁 거부, 원본 경로 미유출</li>
 *   <li>외부가 발급한 job_id 적재</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentJobSubmitServiceTest {

    @Mock private LsDataSrcRepository srcRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private AugmentJobRecorder jobRecorder;
    @Mock private ExternalAugmentClient externalClient;
    @Mock private AugmentMetrics metrics;
    @Mock private DeidentReportGate deidentReportGate;

    private AugmentJobSubmitService service;
    private final AtomicLong jobSnSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        service = new AugmentJobSubmitService(
                srcRepository, videoRepository, jobRecorder, externalClient, metrics,
                deidentReportGate, 100);
        given(videoRepository.findById(anyLong())).willReturn(Optional.empty());
        given(jobRecorder.recordIssued(anyLong(), anyInt(), anyString(), anyList()))
                .willAnswer(inv -> jobSnSeq.incrementAndGet());
        given(externalClient.requestAugment(any()))
                .willAnswer(inv -> AugmentSubmitResult.accepted("ext-" + System.nanoTime()));
    }

    private AugmentRequestedItemEvent event() {
        return new AugmentRequestedItemEvent(
                7L, 700L, "WINTER", "AUG-key", "http://localhost:8080/api/v1/genai/callback", "1");
    }

    /** 비식별 경로가 채워진 프레임 n 건. */
    private void seedFrames(int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{(long) i, "/storage/deidentified/frames/" + i + ".jpg"});
        }
        given(srcRepository.findDeidFramePathsByRawSn(700L)).willReturn(rows);
    }

    @Test
    @DisplayName("프레임_250장이면_100_100_50_으로_3개_job_으로_분할_위탁됨")
    void splitsInto100ChunkJobs() {
        seedFrames(250);

        int accepted = service.submit(event()).accepted();

        assertThat(accepted).isEqualTo(3);
        ArgumentCaptor<AugmentSubmitCommand> captor =
                ArgumentCaptor.forClass(AugmentSubmitCommand.class);
        verify(externalClient, times(3)).requestAugment(captor.capture());
        List<AugmentSubmitCommand> commands = captor.getAllValues();
        assertThat(commands).extracting(c -> c.inputFiles().size())
                .containsExactly(100, 100, 50);
        assertThat(commands).extracting(AugmentSubmitCommand::jobSeq).containsExactly(1, 2, 3);
        assertThat(commands).allSatisfy(c -> assertThat(c.jobCount()).isEqualTo(3));
        // 청크별 request_id 는 서로 다르다(멱등키 충돌 금지).
        assertThat(commands).extracting(AugmentSubmitCommand::requestId)
                .containsExactly("AUG-key-1", "AUG-key-2", "AUG-key-3")
                .doesNotHaveDuplicates();
        // 위탁 전 선기록 3건
        verify(jobRecorder, times(3)).recordIssued(eq(7L), anyInt(), anyString(), anyList());
    }

    @Test
    @DisplayName("sequence는_각_job_내에서_이어지며_전체_프레임이_빠짐없이_위탁된다")
    void sequenceCoversAllFrames() {
        seedFrames(250);

        service.submit(event());

        ArgumentCaptor<AugmentSubmitCommand> captor =
                ArgumentCaptor.forClass(AugmentSubmitCommand.class);
        verify(externalClient, times(3)).requestAugment(captor.capture());
        List<Integer> sequences = captor.getAllValues().stream()
                .flatMap(c -> c.inputFiles().stream())
                .map(AugmentInputFile::sequence)
                .toList();
        assertThat(sequences).hasSize(250);
        assertThat(sequences.get(0)).isEqualTo(1);
        assertThat(sequences.get(249)).isEqualTo(250);
    }

    @Test
    @DisplayName("위탁_시점의_sequence_to_srcSn_매핑이_영속화된다")
    void persistsSequenceToSrcSnMappingAtSubmit() {
        // given — 프레임 3건(srcSn 0,1,2 순).
        seedFrames(3);

        // when
        service.submit(event());

        // then — 외부로 보낸 input_files 의 sequence 와 같은 순서로 (fileSeq, srcSn) 대응이 기록된다.
        //        이 대응이 없으면 결과 수신 시 프레임 정렬로 순서를 재계산해야 해 프레임 변동에 어긋난다.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AugmentJobFileRef>> refCaptor = ArgumentCaptor.forClass(List.class);
        verify(jobRecorder).recordIssued(eq(7L), eq(1), eq("AUG-key-1"), refCaptor.capture());
        assertThat(refCaptor.getValue())
                .extracting(AugmentJobFileRef::fileSeq, AugmentJobFileRef::srcSn)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 0L),
                        org.assertj.core.groups.Tuple.tuple(2, 1L),
                        org.assertj.core.groups.Tuple.tuple(3, 2L));
    }

    @Test
    @DisplayName("분할_위탁_중_2번째_job_실패해도_3번째_가_계속_위탁되고_실패행이_남음")
    void isolatesChunkFailure() {
        seedFrames(250);
        given(externalClient.requestAugment(any())).willAnswer(inv -> {
            AugmentSubmitCommand c = inv.getArgument(0);
            if (c.jobSeq() == 2) {
                throw new IllegalStateException("외부 장애(mock)");
            }
            return AugmentSubmitResult.accepted("ext-" + c.jobSeq());
        });

        int accepted = service.submit(event()).accepted();

        assertThat(accepted).isEqualTo(2);
        verify(externalClient, times(3)).requestAugment(any());
        // 실패는 조용히 삼키지 않고 사유와 함께 남긴다.
        verify(jobRecorder, times(1))
                .markFailed(anyLong(), eq(LsDataAugJob.ERR_SUBMIT_FAILED), anyString());
        verify(jobRecorder, times(2)).markAccepted(anyLong(), anyString());
    }

    @Test
    @DisplayName("외부가_준_job_id_가_LS_DATA_AUG_JOB_에_저장됨")
    void persistsExternallyIssuedJobId() {
        seedFrames(5);
        given(externalClient.requestAugment(any()))
                .willReturn(AugmentSubmitResult.accepted("external-job-xyz"));

        service.submit(event());

        verify(jobRecorder).markAccepted(anyLong(), eq("external-job-xyz"));
    }

    @Test
    @DisplayName("비식별_경로가_null_인_프레임이_있으면_요청이_거부되고_원본경로가_외부로_나가지_않음")
    void failsClosedWhenDeidPathMissing() {
        given(srcRepository.findDeidFramePathsByRawSn(700L)).willReturn(List.<Object[]>of(
                new Object[]{1L, "/storage/deidentified/frames/1.jpg"},
                new Object[]{2L, null}));

        int accepted = service.submit(event()).accepted();

        assertThat(accepted).isZero();
        verify(externalClient, never()).requestAugment(any());
        verify(jobRecorder).recordRejected(eq(7L), anyString(),
                eq(LsDataAugJob.ERR_DEID_PATH_MISSING), anyString());
    }

    @Test
    @DisplayName("비식별_경로가_빈문자열인_프레임도_fail_closed_로_거부된다")
    void failsClosedWhenDeidPathBlank() {
        given(srcRepository.findDeidFramePathsByRawSn(700L)).willReturn(List.<Object[]>of(
                new Object[]{1L, "   "}));

        assertThat(service.submit(event()).accepted()).isZero();
        verify(externalClient, never()).requestAugment(any());
    }

    @Test
    @DisplayName("프레임이_없는_영상은_위탁하지_않고_사유를_남긴다")
    void failsClosedWhenNoFrames() {
        given(srcRepository.findDeidFramePathsByRawSn(700L)).willReturn(List.<Object[]>of());

        assertThat(service.submit(event()).accepted()).isZero();
        verify(externalClient, never()).requestAugment(any());
        verify(jobRecorder).recordRejected(eq(7L), anyString(),
                eq(LsDataAugJob.ERR_DEID_PATH_MISSING), anyString());
    }

    @Test
    @DisplayName("input_files_의_모든_file_path_가_비식별_경로다")
    void allInputPathsAreDeidentified() {
        seedFrames(3);

        service.submit(event());

        ArgumentCaptor<AugmentSubmitCommand> captor =
                ArgumentCaptor.forClass(AugmentSubmitCommand.class);
        verify(externalClient).requestAugment(captor.capture());
        assertThat(captor.getValue().inputFiles())
                .allSatisfy(f -> assertThat(f.filePath()).startsWith("/storage/deidentified/"));
        // 원본 경로 컬럼은 조회조차 하지 않는다(경로 자체를 없앤 구조).
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("이벤트유형이_없는_영상은_ETC_로_대체된다")
    void fallsBackToEtcEventType() {
        seedFrames(1);

        service.submit(event());

        ArgumentCaptor<AugmentSubmitCommand> captor =
                ArgumentCaptor.forClass(AugmentSubmitCommand.class);
        verify(externalClient).requestAugment(captor.capture());
        assertThat(captor.getValue().evntType())
                .isEqualTo(AugmentJobSubmitService.EVNT_TYPE_FALLBACK);
    }

    @Test
    @DisplayName("max_input_files_설정이_계약상한_100_을_넘기면_100_으로_clamp_된다")
    void clampsChunkSizeToContractMax() {
        AugmentJobSubmitService oversized = new AugmentJobSubmitService(
                srcRepository, videoRepository, jobRecorder, externalClient, metrics,
                deidentReportGate, 500);
        seedFrames(150);

        oversized.submit(event());

        ArgumentCaptor<AugmentSubmitCommand> captor =
                ArgumentCaptor.forClass(AugmentSubmitCommand.class);
        verify(externalClient, times(2)).requestAugment(captor.capture());
        assertThat(captor.getAllValues()).extracting(c -> c.inputFiles().size())
                .containsExactly(100, 50);
    }

    @Test
    @DisplayName("선기록_실패시_외부로_위탁하지_않는다")
    void skipsSubmitWhenIssueRecordFails() {
        seedFrames(3);
        given(jobRecorder.recordIssued(anyLong(), anyInt(), anyString(), anyList()))
                .willThrow(new IllegalStateException("중복 멱등키(mock)"));

        assertThat(service.submit(event()).accepted()).isZero();
        verify(externalClient, never()).requestAugment(any());
    }

    // ─────────── 선기록 전량 선행 (DEV_FIX 2차 MEDIUM-2 — 부분 프레임셋 확정 차단) ───────────

    @Test
    @DisplayName("선기록_실패가_있으면_증강이_성공_확정되지_않는다")
    void partialIssueRecordNeverYieldsSuccess() {
        // given — 프레임 250장(청크 3개) 중 2번째 청크의 선기록이 IDMP_KEY 충돌로 실패한다.
        //         구 구현은 2번 청크의 job 행 없이 1·3번만 위탁해, 롤업이 "존재하는 행 전부 성공" 으로
        //         판정하며 부분 프레임셋을 ACCEPTED 로 확정했다.
        seedFrames(250);
        given(jobRecorder.recordIssued(anyLong(), anyInt(), anyString(), anyList()))
                .willAnswer(inv -> {
                    if ((int) inv.getArgument(1) == 2) {
                        throw new IllegalStateException("중복 멱등키(mock)");
                    }
                    return jobSnSeq.incrementAndGet();
                });

        // when
        AugmentJobSubmitService.SubmitOutcome outcome = service.submit(event());

        // then — 한 건도 나가지 않는다(선기록이 위탁보다 <전부> 앞서므로 노출 자체가 없다).
        assertThat(outcome.accepted()).isZero();
        assertThat(outcome.requiresFailureRollup())
                .as("콜백이 오지 않으므로 호출부가 즉시 실패 롤업해야 한다(PENDING 고착 금지)")
                .isTrue();
        verify(externalClient, never()).requestAugment(any());
    }

    @Test
    @DisplayName("청크_3개중_2번째_선기록_실패시_전체가_실패로_종결된다")
    void issueRecordFailureTerminatesAlreadyRecordedJobs() {
        // given — 1번 청크는 선기록 성공, 2번에서 실패.
        seedFrames(250);
        given(jobRecorder.recordIssued(anyLong(), anyInt(), anyString(), anyList()))
                .willAnswer(inv -> {
                    if ((int) inv.getArgument(1) == 2) {
                        throw new IllegalStateException("중복 멱등키(mock)");
                    }
                    return jobSnSeq.incrementAndGet();
                });

        // when
        service.submit(event());

        // then — 이미 선기록된 1번 행을 terminal FAILED 로 종결한다. 비종결로 남기면 롤업이 영원히
        //        보류되고(고아 RECEIVED), 행을 아예 안 남기면 부분 프레임셋이 성공 확정된다.
        verify(jobRecorder, times(1))
                .markFailed(anyLong(), eq(LsDataAugJob.ERR_ISSUE_RECORD_FAILED), anyString());
        verify(jobRecorder, never()).markAccepted(anyLong(), anyString());
    }

    @Test
    @DisplayName("선기록은_첫_위탁보다_먼저_전량_수행된다")
    void allChunksAreRecordedBeforeFirstSubmit() {
        // given
        seedFrames(250);
        List<String> order = new ArrayList<>();
        given(jobRecorder.recordIssued(anyLong(), anyInt(), anyString(), anyList()))
                .willAnswer(inv -> {
                    order.add("issue-" + inv.getArgument(1));
                    return jobSnSeq.incrementAndGet();
                });
        given(externalClient.requestAugment(any())).willAnswer(inv -> {
            order.add("submit-" + ((AugmentSubmitCommand) inv.getArgument(0)).jobSeq());
            return AugmentSubmitResult.accepted("ext");
        });

        // when
        service.submit(event());

        // then — 기대 job 집합이 위탁 전에 완성돼야 롤업이 부분 프레임셋을 구분할 수 있다.
        assertThat(order).containsExactly(
                "issue-1", "issue-2", "issue-3", "submit-1", "submit-2", "submit-3");
    }

    // ─────────────── 비식별 누락 신고 게이트 (DEV_FIX HIGH-2, PII) ───────────────

    @Test
    @DisplayName("비식별_신고중인_영상은_증강_위탁이_보류된다")
    void withholdsSubmitWhenUnderDeidentReport() {
        // given — 신고 구간(DE_IDNTF_YN='F'). 비식별 프레임 경로 자체는 남아 있다(파일도 그대로).
        seedFrames(250);
        given(deidentReportGate.isUnderDeidentReport(700L)).willReturn(true);

        // when
        AugmentJobSubmitService.SubmitOutcome outcome = service.submit(event());

        // then — 거부(2026-07-29 정책): 위탁 0건 + 사유 기록 → 호출부가 실패 롤업한다.
        //         구 "보류 후 해소 시 재개" 는 재개 배선 철회로 폐기(트리거 없는 PENDING 고착 방지).
        assertThat(outcome.accepted()).isZero();
        assertThat(outcome.requiresFailureRollup()).isTrue();
        verify(jobRecorder, never()).recordIssued(anyLong(), anyInt(), anyString(), anyList());
        verify(jobRecorder).recordRejected(anyLong(), anyString(),
                eq(LsDataAugJob.ERR_DEID_REPORT_OPEN), anyString());
    }

    @Test
    @DisplayName("비식별_신고중_영상의_프레임_경로가_외부로_전송되지_않는다")
    void neverSendsFramePathsWhenUnderDeidentReport() {
        // given — 신고는 "이 영상의 비식별본에 PII 가 남아 있다" 는 확인이다. 벤더가 공유 NAS 에서
        //         실제 파일을 읽으므로 경로 전송 자체가 유출이다(CWE-359).
        seedFrames(250);
        given(deidentReportGate.isUnderDeidentReport(700L)).willReturn(true);

        // when
        service.submit(event());

        // then — 외부 호출 0회 + 프레임 경로 조회조차 하지 않는다.
        verify(externalClient, never()).requestAugment(any());
        verify(srcRepository, never()).findDeidFramePathsByRawSn(anyLong());
    }

    @Test
    @DisplayName("위탁_도중_비식별_신고가_확인되면_남은_청크를_중단하고_실패로_종결한다")
    void abortsRemainingChunksWhenReportObservedMidSubmit() {
        // given — 첫 청크 위탁 후 신고가 커밋된 상황(1회차 false, 이후 true).
        seedFrames(250);
        given(deidentReportGate.isUnderDeidentReport(700L)).willReturn(false, true, true);

        // when
        AugmentJobSubmitService.SubmitOutcome outcome = service.submit(event());

        // then — 남은 2개 청크는 나가지 않고, 부분 프레임셋 확정을 막기 위해 <이미 선기록된> 남은
        //         job 행을 terminal FAILED 로 종결한다(비종결로 두면 롤업이 영원히 보류된다).
        assertThat(outcome.accepted()).isEqualTo(1);
        verify(externalClient, times(1)).requestAugment(any());
        verify(jobRecorder, times(2))
                .markFailed(anyLong(), eq(LsDataAugJob.ERR_DEIDENT_REPORT), anyString());
    }

    @Test
    @DisplayName("신고구간이_아니면_기존_위탁_흐름이_그대로_유지된다")
    void normalFlowUnaffectedByGate() {
        seedFrames(250);
        given(deidentReportGate.isUnderDeidentReport(700L)).willReturn(false);

        AugmentJobSubmitService.SubmitOutcome outcome = service.submit(event());

        assertThat(outcome.accepted()).isEqualTo(3);
        verify(externalClient, times(3)).requestAugment(any());
    }

    @Test
    @DisplayName("위탁_0건_은_실패롤업_대상이다")
    void zeroAcceptedRequiresFailureRollup() {
        given(srcRepository.findDeidFramePathsByRawSn(700L)).willReturn(List.<Object[]>of());

        AugmentJobSubmitService.SubmitOutcome outcome = service.submit(event());

        assertThat(outcome.requiresFailureRollup()).isTrue();
    }
}
