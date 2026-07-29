package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentInputFile;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitCommand;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitResult;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 증강 외부 위탁 분할 실행 서비스 — Phase 7-A1.
 *
 * <p>증강 결과 1건({@code LS_DATA_AUG})에 대해 대상 영상의 <b>비식별 프레임</b> 전량을
 * {@code input_files} 상한(기본 100장) 단위로 쪼개 여러 job 으로 위탁하고, 결과를
 * {@code LS_DATA_AUG_JOB} 에 행으로 남긴다.
 *
 * <h3>핵심 규칙</h3>
 * <ul>
 *   <li><b>PII fail-closed</b>: 비식별 경로가 비어 있는 프레임이 하나라도 있으면 위탁하지 않는다.
 *       원본(비-비식별) 경로로 대체하지 않는다 — 원본 유출은 보안 결함이다. 거부 사유는
 *       {@code LS_DATA_AUG_JOB}(FAILED/{@code DEID_PATH_MISSING})에 남긴다.</li>
 *   <li><b>비식별 누락 신고 게이트</b>(DEV_FIX HIGH-2): 대상 영상이 신고 구간
 *       ({@code LS_DATA_RAW.DE_IDNTF_YN='F'})이면 <b>한 건도 위탁하지 않는다</b>. 신고는 "이 영상의
 *       비식별본에 PII 가 남아 있다" 는 확인이므로, 그 프레임 경로를 외부 벤더에 넘기면 벤더가 공유
 *       NAS 에서 PII 파일을 실제로 읽는다(CWE-359). 콜백 시점의 부모 {@code 'Y'} 게이트
 *       ({@code AugmentResultService})는 <b>이미 유출된 뒤</b>라 이 창을 닫지 못한다.
 *       판정은 새로 만들지 않고 단일 원천 {@link DeidentReportGate} 를 호출한다.</li>
 *   <li><b>전량 선기록 후 위탁</b>(DEV_FIX 2차 MEDIUM-2): <b>모든</b> 청크를 RECEIVED 로 먼저
 *       선기록(멱등키 + 순서↔프레임 대응 확보)한 뒤에 위탁 루프를 돈다. 선기록이 하나라도 실패하면
 *       한 건도 위탁하지 않고 이미 기록된 앞 청크까지 FAILED 로 종결한다 — 그래야 수신부 롤업이
 *       <b>부분 프레임셋을 전량으로 오인해 성공 확정</b>하는 일이 원천적으로 불가능해진다.
 *       202 수신 후 외부 job_id 를 채우고, 실패는 FAILED + 사유로 남긴다(조용한 삼킴 금지).</li>
 *   <li><b>건별 격리</b>: 2번째 청크의 <b>위탁</b>이 실패해도 3번째 청크를 계속 위탁한다. 전체
 *       성공/부분 실패 판정(집계)은 결과 수신부(A2)의 책임이다.</li>
 * </ul>
 *
 * <p>트랜잭션: 본 서비스는 <b>쓰기 트랜잭션을 열지 않는다</b>. 조회는 readOnly, 기록은
 * {@link AugmentJobRecorder}(REQUIRES_NEW)에 위임한다 — 외부 HTTP 왕복 동안 쓰기 트랜잭션과
 * 커넥션을 붙잡지 않기 위함이다.
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentJobSubmitService {

    /** 명세서 §4.1 input_files 상한. 설정으로 낮출 수는 있어도 계약 상한을 넘길 수 없다. */
    private static final int CONTRACT_MAX_INPUT_FILES = 100;

    /** 이벤트 유형 미상 영상의 대체값 — {@code evnt_type} 은 외부 계약상 필수(1~20)다. */
    static final String EVNT_TYPE_FALLBACK = "ETC";

    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final AugmentJobRecorder jobRecorder;
    private final ExternalAugmentClient externalClient;
    private final AugmentMetrics metrics;
    /** 비식별 누락 신고 구간 판정 — 단일 원천(자체 재구현 금지). */
    private final DeidentReportGate deidentReportGate;
    private final int maxInputFiles;

    public AugmentJobSubmitService(LsDataSrcRepository srcRepository,
                                   VideoRepository videoRepository,
                                   AugmentJobRecorder jobRecorder,
                                   ExternalAugmentClient externalClient,
                                   AugmentMetrics metrics,
                                   DeidentReportGate deidentReportGate,
                                   @Value("${authoring.augment.external.max-input-files:100}")
                                   int maxInputFiles) {
        this.srcRepository = srcRepository;
        this.videoRepository = videoRepository;
        this.jobRecorder = jobRecorder;
        this.externalClient = externalClient;
        this.metrics = metrics;
        this.deidentReportGate = deidentReportGate;
        this.maxInputFiles = clampChunkSize(maxInputFiles);
    }

    private static int clampChunkSize(int configured) {
        if (configured < 1) {
            return CONTRACT_MAX_INPUT_FILES;
        }
        return Math.min(configured, CONTRACT_MAX_INPUT_FILES);
    }

    /**
     * 증강 요청 1건을 청크 단위로 외부 위탁한다.
     *
     * <p><b>전송 진입점 단일 fail-closed</b> — 비식별 누락 신고 구간이면 여기서 전량 거부한다. 게이트를
     * 호출처마다 배선하면 반드시 새므로(Phase 6 교훈), 판정은 {@link DeidentReportGate} 하나에 두고
     * 차단은 <b>실제로 경로가 밖으로 나가는 이 메서드</b> 한 곳에서 한다.
     *
     * <p><b>차단은 보류가 아니라 거부다 (2026-07-29 정책)</b> — 파생 생성이 원본 신고와 무관해지면서
     * 신고 해소 시의 증강 재개 배선이 철회됐다. 재개 트리거가 없는 보류는 아무도 깨우지 못하는
     * PENDING 고착이므로 사유를 남기고 실패로 종결한다(해소 후 재요청이 정상 동선).
     *
     * @return 위탁 결과 — 수락 job 수
     */
    public SubmitOutcome submit(AugmentRequestedItemEvent event) {
        // PII 게이트를 <b>가장 먼저</b> 둔다 — 프레임 경로 조회조차 하기 전에 끊는다.
        if (deidentReportGate.isUnderDeidentReport(event.rawSn())) {
            jobRecorder.recordRejected(event.originAugSn(), event.idempotencyKey(),
                    LsDataAugJob.ERR_DEID_REPORT_OPEN,
                    "비식별 누락 신고 구간이라 외부 위탁을 거부했습니다. 신고 해소 후 다시 요청하세요.");
            metrics.externalRequestFailure();
            log.warn("[Augment] 위탁 거부 — 비식별 누락 신고 구간 originAugSn={} rawSn={}",
                    event.originAugSn(), event.rawSn());
            return SubmitOutcome.of(0);
        }

        List<FrameInput> inputs;
        try {
            inputs = resolveDeidInputFiles(event.rawSn());
        } catch (DeidPathMissingException e) {
            // PII fail-closed — 원본 경로로 대체하지 않고 거부 사유만 남긴다.
            jobRecorder.recordRejected(event.originAugSn(), event.idempotencyKey(),
                    LsDataAugJob.ERR_DEID_PATH_MISSING, e.getMessage());
            metrics.externalRequestFailure();
            log.warn("[Augment] 위탁 거부 — 비식별 프레임 경로 부재 originAugSn={} rawSn={} missingCount={}",
                    event.originAugSn(), event.rawSn(), e.missingCount());
            return SubmitOutcome.of(0);
        }

        String evntType = resolveEventType(event.rawSn());
        List<List<FrameInput>> chunks = partition(inputs);

        // 전량 선기록 — <위탁 전에> 기대 job 집합을 완성한다(DEV_FIX 2차 MEDIUM-2).
        Optional<List<Long>> issued = issueAllChunks(event, chunks);
        if (issued.isEmpty()) {
            // 선기록 단계에서 끊었으므로 외부로 나간 청크는 없다 → 수락 0건(호출부가 실패 롤업).
            return SubmitOutcome.of(0);
        }
        List<Long> augJobSns = issued.get();

        int accepted = 0;
        for (int i = 0; i < chunks.size(); i++) {
            // 청크마다 재판정한다 — 250장 위탁은 수 초~수십 초 걸리고, 그 사이 신고가 커밋되면 남은
            // 청크의 PII 경로 전송을 막을 수 있다(전송 단위가 청크이므로 여기서 끊는 것이 유효하다).
            // 잠금(FOR UPDATE)은 쓰지 않는다 — 외부 HTTP 왕복 전체를 한 트랜잭션으로 묶어 신고 자체를
            // 블록하게 되므로, 여기서는 무잠금 판정으로 남은 노출량만 줄인다.
            if (i > 0 && deidentReportGate.isUnderDeidentReport(event.rawSn())) {
                abortRemainingChunks(event, augJobSns, i, chunks.size());
                break;
            }
            if (submitChunk(event, evntType, chunks.get(i), augJobSns.get(i), i + 1, chunks.size())) {
                accepted++;
            }
        }
        log.info("[Augment] 위탁 완료 originAugSn={} rawSn={} jobCount={} acceptedCount={}",
                event.originAugSn(), event.rawSn(), chunks.size(), accepted);
        return SubmitOutcome.of(accepted);
    }

    /**
     * <b>전 청크 선기록</b>(위탁 전) — 멱등키 + 순서↔프레임 대응을 청크 수만큼 확보한다.
     *
     * <h3>왜 위탁 루프와 분리했나 (DEV_FIX 2차 MEDIUM-2)</h3>
     * <p>구 구현은 "청크마다 선기록 → 즉시 위탁" 이었다. 그래서 2번째 청크의 선기록이 실패하면
     * (예: 원 AFTER_COMMIT 경로와 신고 해제 재개 경로가 같은 멱등키로 동시 진입 → {@code IDMP_KEY}
     * UNIQUE 충돌) <b>그 청크의 job 행이 아예 생기지 않은 채</b> 1·3번 청크만 위탁됐다. 수신부 롤업은
     * "기대 job 수" 를 갖고 있지 않으므로 <b>존재하는 행만 보고 전부 SUCCEEDED</b> 라고 판정해 증강을
     * 성공 확정했다 — 프레임이 빠진 산출물이 ACCEPTED 로 남는 정합 붕괴다.
     *
     * <p>선기록을 전량 먼저 하면 실패가 <b>외부 위탁 전에</b> 드러나므로 전체를 실패로 끝낼 수 있고,
     * 롤업이 대조할 "기대 job 수" 컬럼(스키마 추가)이 필요 없다. 이미 선기록된 앞 청크는
     * {@link LsDataAugJob#ERR_ISSUE_RECORD_FAILED} 로 terminal 종결시켜 <b>비종결 고아 행</b>도 남기지 않는다.
     *
     * @return 청크 순서대로의 {@code AUG_JOB_SN} 목록. 선기록이 하나라도 실패하면 {@link Optional#empty()}
     */
    private Optional<List<Long>> issueAllChunks(AugmentRequestedItemEvent event,
                                                List<List<FrameInput>> chunks) {
        List<Long> augJobSns = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            int jobSeq = i + 1;
            try {
                augJobSns.add(jobRecorder.recordIssued(
                        event.originAugSn(), jobSeq, chunkRequestId(event.idempotencyKey(), jobSeq),
                        chunks.get(i).stream().map(FrameInput::toRef).toList()));
            } catch (Exception e) {
                metrics.externalRequestFailure();
                log.warn("[Augment] job 선기록 실패 — 전 청크 위탁 중단 originAugSn={} jobSeq={}/{} err={}",
                        event.originAugSn(), jobSeq, chunks.size(), sanitize(e.getMessage()));
                failIssuedJobs(augJobSns, LsDataAugJob.ERR_ISSUE_RECORD_FAILED,
                        "선기록 실패로 위탁 전 전체를 중단했습니다. failedJobSeq=" + jobSeq);
                return Optional.empty();
            }
        }
        return Optional.of(augJobSns);
    }

    /**
     * 위탁 <b>도중</b> 신고가 관측돼 남은 청크를 끊었을 때의 종결 기록.
     *
     * <p>이미 나간 청크는 되돌릴 수 없고 프레임셋이 불완전하므로, 이 증강 1건은 성공으로 확정돼선
     * 안 된다. <b>선기록만 되고 위탁되지 않은</b> 남은 job 행을 terminal FAILED 로 종결해 롤업이
     * "부분 실패 = 전체 실패" 규칙으로 끝내게 한다(위탁 <b>전</b> 보류와 달리 여기서는 실패가 맞다).
     * 이 행들을 그냥 두면 비종결이라 롤업이 영원히 보류된다.
     *
     * @param fromIndex 중단 시작 청크의 0-base 인덱스(= 위탁된 마지막 청크 다음)
     */
    private void abortRemainingChunks(AugmentRequestedItemEvent event, List<Long> augJobSns,
                                      int fromIndex, int jobCount) {
        metrics.externalRequestFailure();
        failIssuedJobs(augJobSns.subList(fromIndex, augJobSns.size()),
                LsDataAugJob.ERR_DEIDENT_REPORT,
                "위탁 중 비식별 누락 신고가 확인되어 남은 청크를 중단했습니다. abortedFromJobSeq="
                        + (fromIndex + 1));
        log.warn("[Augment] 위탁 중단 — 비식별 누락 신고 관측 originAugSn={} rawSn={} abortedFrom={}/{}",
                event.originAugSn(), event.rawSn(), fromIndex + 1, jobCount);
    }

    /** 선기록된 job 들을 terminal FAILED 로 종결한다(건별 격리 — 한 건 실패가 나머지를 막지 않는다). */
    private void failIssuedJobs(List<Long> augJobSns, String errorCode, String reason) {
        for (Long augJobSn : augJobSns) {
            try {
                jobRecorder.markFailed(augJobSn, errorCode, reason);
            } catch (Exception e) {
                log.error("[Augment] 선기록 job 종결 실패 — 비종결 행 잔존 가능 augJobSn={} err={}",
                        augJobSn, sanitize(e.getMessage()));
            }
        }
    }

    /**
     * 위탁 결과 — 202 수락된 job 개수.
     *
     * <p>{@code accepted==0} 은 즉시 실패 롤업 대상이다(콜백이 영영 오지 않아 PENDING 고착).
     * 정책 보류 구분({@code withheld})은 2026-07-29 로 제거됐다 — 신고 구간 차단도 거부(실패)로
     * 종결하므로 "롤업하지 않고 재개를 기다리는" 상태가 더 이상 없다.
     *
     * @param accepted 202 수락된 job 개수
     */
    public record SubmitOutcome(int accepted) {

        static SubmitOutcome of(int accepted) {
            return new SubmitOutcome(accepted);
        }

        /** 콜백이 오지 않을 상태(위탁 0건)인가 — 즉시 실패 롤업 판정. */
        public boolean requiresFailureRollup() {
            return accepted == 0;
        }
    }

    /**
     * 청크 1건 위탁 — 외부 호출 → 결과 반영. 예외는 여기서 흡수하되 <b>DB 에 사유를 남긴다</b>.
     *
     * <p>선기록({@code recordIssued})은 {@link #issueAllChunks} 가 <b>위탁 루프 진입 전에</b> 전량
     * 끝냈으므로 여기서는 이미 확보된 {@code augJobSn} 을 받아 쓴다.
     *
     * @return 202 수락 여부
     */
    private boolean submitChunk(AugmentRequestedItemEvent event, String evntType,
                                List<FrameInput> chunk, Long augJobSn, int jobSeq, int jobCount) {
        String requestId = chunkRequestId(event.idempotencyKey(), jobSeq);
        try {
            AugmentSubmitResult result = externalClient.requestAugment(new AugmentSubmitCommand(
                    event.originAugSn(), event.augType(), requestId, evntType,
                    event.requestUserNo(), event.callbackUrl(),
                    chunk.stream().map(FrameInput::toInputFile).toList(), jobSeq, jobCount));
            jobRecorder.markAccepted(augJobSn, result.externalJobId());
            metrics.externalRequestSuccess();
            return true;
        } catch (Exception e) {
            // 건별 격리 — 실패해도 다음 청크는 계속 위탁한다. 사유는 반드시 남긴다.
            metrics.externalRequestFailure();
            String reason = sanitize(e.getMessage());
            jobRecorder.markFailed(augJobSn, LsDataAugJob.ERR_SUBMIT_FAILED, reason);
            log.warn("[Augment] 위탁 실패(격리) originAugSn={} jobSeq={}/{} err={}",
                    event.originAugSn(), jobSeq, jobCount, reason);
            return false;
        }
    }

    /**
     * 대상 영상의 <b>비식별</b> 프레임 경로를 순서대로 만든다. 각 항목은 외부로 나갈 입력 파일과
     * 내부 대응(프레임 {@code srcSn})을 함께 들고 다닌다 — 결과를 정확한 프레임에 되붙이기 위함이다.
     *
     * @throws DeidPathMissingException 프레임이 없거나 비식별 경로가 빈 프레임이 하나라도 있을 때
     */
    private List<FrameInput> resolveDeidInputFiles(Long rawSn) {
        List<Object[]> rows = srcRepository.findDeidFramePathsByRawSn(rawSn);
        if (rows.isEmpty()) {
            throw new DeidPathMissingException("증강 대상 영상에 프레임이 없습니다.", 0);
        }
        List<FrameInput> files = new ArrayList<>(rows.size());
        int missing = 0;
        int sequence = 1;
        for (Object[] row : rows) {
            Long srcSn = (Long) row[0];
            String deidPath = (String) row[1];
            if (deidPath == null || deidPath.isBlank()) {
                missing++;
                continue;
            }
            files.add(new FrameInput(sequence++, srcSn, deidPath));
        }
        if (missing > 0) {
            throw new DeidPathMissingException(
                    "비식별 프레임 경로가 없는 프레임이 있어 외부 위탁을 거부합니다. missingCount=" + missing,
                    missing);
        }
        return files;
    }

    /** 관제 이벤트 유형 코드. 미상이면 계약 필수 필드를 채우기 위해 {@link #EVNT_TYPE_FALLBACK}. */
    private String resolveEventType(Long rawSn) {
        return videoRepository.findById(rawSn)
                .map(LsDataRaw::getEvntTypeCd)
                .filter(code -> code != null && !code.isBlank())
                .orElse(EVNT_TYPE_FALLBACK);
    }

    private List<List<FrameInput>> partition(List<FrameInput> inputs) {
        List<List<FrameInput>> chunks = new ArrayList<>();
        for (int start = 0; start < inputs.size(); start += maxInputFiles) {
            chunks.add(List.copyOf(inputs.subList(start, Math.min(start + maxInputFiles, inputs.size()))));
        }
        return chunks;
    }

    /**
     * 청크별 request_id — aug 단위 멱등 키에 청크 순서를 덧붙인다.
     * 형식({@code ^[A-Za-z0-9_-]+$}) 과 길이(≤64) 를 모두 유지한다.
     */
    static String chunkRequestId(String augIdempotencyKey, int jobSeq) {
        return augIdempotencyKey + "-" + jobSeq;
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. 절대경로는 애초에 로그에 싣지 않는다. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }

    /**
     * 위탁 입력 1건 — 외부로 나갈 {@link AugmentInputFile} 과 내부 대응({@code srcSn})을 함께 든다.
     * {@code srcSn} 은 외부 페이로드에 절대 싣지 않는다(내부 식별자 노출 금지).
     */
    private record FrameInput(int sequence, Long srcSn, String deidPath) {

        AugmentInputFile toInputFile() {
            return new AugmentInputFile(sequence, deidPath);
        }

        AugmentJobFileRef toRef() {
            return new AugmentJobFileRef(sequence, srcSn);
        }
    }

    /** 비식별 경로 부재 — 위탁 거부 신호(내부 전용). */
    private static final class DeidPathMissingException extends RuntimeException {
        private final int missingCount;

        private DeidPathMissingException(String message, int missingCount) {
            super(message);
            this.missingCount = missingCount;
        }

        private int missingCount() {
            return missingCount;
        }
    }
}
