package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.webhook.dto.GenAiCallbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 생성형 AI(증강) 결과 웹훅 처리 — 「생성형 AI API 연동명세서 v1.1」 정합 (Phase 7-A2).
 *
 * <p>{@code POST /v1/genai/callback} 수신부. 웹훅은 <b>job 단위</b>로 오고 증강 1건
 * ({@code LS_DATA_AUG})은 입력 100장 상한 때문에 여러 job 으로 분할 위탁되므로,
 * <b>전 job 종결 후에만</b> 증강 1건의 최종 처리({@link AugmentResultService})를 수행한다.
 *
 * <h3>인증 — request_id 발급 게이트 (최종 방어선)</h3>
 * <p>계약상 웹훅에 서명·인증 헤더가 없다. 필터 단계(IP allowlist·rate limit·size cap) 위에
 * <b>우리가 발급한 {@code request_id} 만 처리</b>하는 게이트를 둔다. 발급 원장은
 * {@code LS_DATA_AUG_JOB.IDMP_KEY} 다 — 위탁 <b>직전</b> 선기록되는 값이라
 * "우리가 낸 요청" 의 단일 진실원이며, 청크 단위 키를 그대로 보유한다
 * ({@code LS_WEBHOOK_IDEMPOTENCY} 원장은 증강 1건 단위 키만 갖고 있어 청크 키를 매칭할 수 없다).
 * 미발급 키는 {@code 401} 이며, 필터가 이 401 을 rate limit 에 집계해 탐색 공격을 차단한다.
 *
 * <h3>동시성 — 롤업 1회 보장 (CWE-362)</h3>
 * <p>마지막 job 의 콜백 2건이 동시에 도착하면 롤업이 2회 실행될 수 있다. 이를 막기 위해
 * <b>job 행을 갱신하기 전에</b> 증강 행({@code LS_DATA_AUG})을 {@code FOR UPDATE} 로 잠근다.
 * 순서를 뒤집으면(= job 먼저 갱신) 두 트랜잭션이 서로의 미커밋 갱신을 못 봐서
 * <b>양쪽 다 "아직 남은 job 있음" 으로 판정하고 롤업이 통째로 유실</b>된다. 잠금을 선점하면
 * 후행 트랜잭션은 선행 커밋 이후에 진입해 전 job 종결을 관측한다. 실제 확정은
 * {@link AugmentResultService#handle} 이 같은 행 잠금 + PENDING 앵커로 한 번 더 방어한다.
 *
 * <h3>멱등</h3>
 * <p>외부는 전송 실패 시 재시도하므로 <b>같은 페이로드 중복 수신이 정상</b>이다. 이미 종결
 * (SUCCEEDED/FAILED/CANCELED)된 job 의 콜백은 상태를 바꾸지 않고 {@code applied=false} 로 흡수한다.
 *
 * <h3>산출물 적재 (Phase 7-D)</h3>
 * <p>SUCCEEDED 콜백의 {@code results[].output_file_path} 는 <b>버리지 않고</b> 위탁 시점에 못박은
 * 항목({@code LS_DATA_AUG_JOB_FILE})에 순서대로 되붙인다. 증강 1건이 여러 job 으로 쪼개져 콜백이
 * 여러 번 오므로, 마지막 롤업 시점에 앞 job 들의 산출 경로가 남아 있어야 프레임셋을 통째로 채울 수
 * 있기 때문이다(마지막 콜백 페이로드만으로는 1/N 밖에 알 수 없다).
 *
 * <h3>부분 실패 = 전체 실패 (fail-closed)</h3>
 * <p>job 1건이라도 FAILED 면 증강을 성공 처리하지 않는다. 부분 결과로 증강 영상을 만들면 프레임이
 * 빠진 불완전한 학습데이터가 되기 때문이다. 실패 job 의 seq/사유는 로그와
 * {@code LS_DATA_AUG_JOB}(ERR_CD/ERR_MSG_CN)에 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenAiCallbackService {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    /** 로그에 남길 외부 오류 메시지 최대 길이 — 로그 폭주 차단. */
    private static final int LOG_MSG_MAX = 200;

    private final LsDataAugJobRepository jobRepository;
    /** 위탁 시점에 못박은 순서↔프레임 대응 — 여기에 산출 경로를 되붙인다(Phase 7-D). */
    private final LsDataAugJobFileRepository jobFileRepository;
    private final LsDataAugRepository augRepository;
    /** 전 job 종결 판정 + 증강 1건 확정 — 만료 스윕과 <b>같은 규칙</b>을 쓰기 위한 단일 원천. */
    private final AugmentJobRollup rollup;
    /** 외부가 준 {@code output_file_path} 가 <b>읽기</b> 허용 루트 하위인지 확인한다(CWE-22). */
    private final VideoArtifactRootResolver artifactRootResolver;
    /** 거부 사유 집계 — 상태를 바꾸지 않는 400 이 조용히 고착되는 것을 운영이 감지할 근거. */
    private final AugmentMetrics metrics;

    /**
     * 웹훅 1건 처리.
     *
     * @return {@link AugmentApplyResult#APPLIED} = 상태를 갱신함 /
     *         {@link AugmentApplyResult#DUPLICATE} = 멱등 흡수(이미 종결된 job 의 재전송) /
     *         {@code WITHHELD_*} = job 은 갱신했으나 증강 인계가 정책 보류됨(E-ISSUE-11 — 응답
     *         {@code applied:false} + 사유로 회신해야 외부가 "정상 인계" 로 오해하지 않는다)
     */
    @Transactional("controlTransactionManager")
    public AugmentApplyResult handle(GenAiCallbackRequest req) {
        String requestId = req.requestId();

        // 1) 발급 게이트 — 우리가 낸 request_id 가 아니면 401 (무단 주입 차단).
        Long dataAugSn = jobRepository.findByIdempotencyKey(requestId)
                .map(LsDataAugJob::getDataAugSn)
                .orElseThrow(() -> {
                    log.warn("[Webhook][GenAi] unknown request_id={}", safe(requestId));
                    return new CustomException(ErrorCode.UNAUTHORIZED, "발급되지 않은 request_id 입니다.");
                });

        // 2) 롤업 직렬화 — job 갱신 전에 증강 행을 잠근다(위 클래스 주석의 "롤업 유실" 방지).
        augRepository.findByDataAugSnForUpdate(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: dataAugSn=" + dataAugSn));

        // 3) 잠금 이후 job 전량을 다시 읽는다 — 선행 트랜잭션의 커밋 결과가 반영된 스냅샷이어야 한다.
        List<LsDataAugJob> jobs = jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn);
        LsDataAugJob target = jobs.stream()
                .filter(j -> requestId.equals(j.getIdempotencyKey()))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "위탁 job 을 찾을 수 없습니다."));

        // 4) 오배송 차단 — 202 로 받아 둔 job_id 와 다른 값이면 처리하지 않는다.
        if (target.getExternalJobId() != null && !target.getExternalJobId().equals(req.jobId())) {
            log.warn("[Webhook][GenAi] job_id mismatch request_id={} expected={} received={}",
                    safe(requestId), safe(target.getExternalJobId()), safe(req.jobId()));
            throw new CustomException(ErrorCode.CONFLICT, "job_id 가 일치하지 않습니다.");
        }

        // 5) 멱등 — 이미 종결된 job 의 재전송은 상태를 바꾸지 않는다.
        if (target.isTerminal()) {
            log.info("[Webhook][GenAi] duplicate callback absorbed request_id={} state={}",
                    safe(requestId), safe(target.getJobSttsCd()));
            return AugmentApplyResult.DUPLICATE;
        }

        // 6) 상태 반영
        if (LsDataAugJob.STTS_RUNNING.equals(req.status())) {
            target.markRunning(req.jobId());
            jobRepository.save(target);
            log.info("[Webhook][GenAi] running request_id={} progress={} step={}",
                    safe(requestId), req.progress(), safe(req.currentStep()));
            return AugmentApplyResult.APPLIED; // 진행 상태만 갱신 — 결과 처리·롤업 없음
        }

        if (LsDataAugJob.STTS_SUCCEEDED.equals(req.status())) {
            applySucceeded(target, req, requestId);
        } else {
            target.markFailed(req.jobId(), req.errorCode(), req.errorMessage());
            log.warn("[Webhook][GenAi] job failed request_id={} jobSeq={} code={} message={}",
                    safe(requestId), target.getJobSeq(), safe(req.errorCode()),
                    truncateForLog(req.errorMessage()));
        }
        jobRepository.save(target);
        jobRepository.flush();

        // 7) 롤업 — 전 job 종결 시에만 증강 1건을 확정한다.
        //    job 상태는 확실히 갱신됐으므로 회신은 APPLIED 다(정책 보류 값은 2026-07-29 로 폐기 —
        //    신고 구간 차단은 파생 생성이 아니라 외부 위탁 쪽에서, 그것도 거부로 종결된다).
        rollup.rollUpIfAllTerminal(dataAugSn, jobs, req.jobId());
        return AugmentApplyResult.APPLIED;
    }

    /**
     * SUCCEEDED 수신 반영 — 산출 경로를 <b>위탁 항목에 되붙인 뒤에만</b> 성공으로 종결한다.
     *
     * <p>계약상 {@code results[]} 에는 입력 식별자가 없어 <b>순서</b>로만 대응시킬 수 있다. 따라서
     * 위탁 시점에 못박아 둔 항목({@code LS_DATA_AUG_JOB_FILE}, FILE_SEQ 오름차순)과 수신 결과를
     * 같은 순서로 짝짓는다. <b>건수가 다르면 짝짓기가 성립하지 않으므로</b> 성공으로 접수하지 않고
     * 이 job 을 FAILED 로 종결한다 — 롤업의 부분 실패 규칙에 따라 증강 1건도 실패로 끝난다
     * (fail-closed. 부분/오정렬 산출물로 프레임셋을 채우면 다른 프레임에 남의 증강본이 붙는다).
     */
    private void applySucceeded(LsDataAugJob target, GenAiCallbackRequest req, String requestId) {
        List<String> outputs = verifiedOutputPaths(req, requestId);
        List<LsDataAugJobFile> files = jobFileRepository.findByAugJobSnOrderByFileSeqAsc(target.getAugJobSn());
        if (files.size() != outputs.size()) {
            target.markFailed(req.jobId(), LsDataAugJob.ERR_RESULT_COUNT_MISMATCH,
                    "위탁 " + files.size() + "건 대비 수신 " + outputs.size() + "건");
            log.warn("[Webhook][GenAi] result count mismatch — job failed (fail-closed) request_id={} "
                            + "jobSeq={} issuedCount={} receivedCount={}",
                    safe(requestId), target.getJobSeq(), files.size(), outputs.size());
            return;
        }
        for (int i = 0; i < files.size(); i++) {
            files.get(i).applyResultPath(outputs.get(i));
        }
        jobFileRepository.saveAll(files);
        target.markSucceeded(req.jobId());
        log.info("[Webhook][GenAi] job succeeded request_id={} jobSeq={} outputCount={}",
                safe(requestId), target.getJobSeq(), outputs.size());
    }

    /**
     * {@code results[].output_file_path} 를 <b>정규화 후</b> <b>읽기</b> 허용 루트 하위인지 검증한다(CWE-22).
     *
     * <p>판정 축은 {@code VideoArtifactRootResolver#verifyExternalReadablePath} 다 — 벤더 산출물은 우리가
     * <b>읽어서</b> 파생 프레임으로 복사할 대상이므로, 쓰기 base allowlist
     * ({@code raw-mount-roots}) 를 넓히지 않고 별도 읽기 루트({@code external-read-roots})로 허용한다.
     *
     * <p>하나라도 허용 밖이면 {@code 400} 으로 거부하고 <b>상태를 바꾸지 않는다</b> — job 을 강제로
     * FAILED 로 만들지 않으므로 외부가 올바른 경로로 재전송하면 정상 처리된다. 거부 메시지에 경로
     * 원문·내부 디렉터리 구조를 담지 않는다(CWE-209).
     *
     * <p><b>고착 회수(Phase 8-A)</b>: 상태를 바꾸지 않는다는 것은, 외부가 재시도를 포기하면
     * job 이 비종결(RECEIVED/RUNNING)로 남아 증강 1건이 PENDING 에 머문다는 뜻이다. 재전송 여지는
     * 그대로 두되 <b>무한 대기는 없앤다</b> — {@code AugmentJobExpirySweeper} 가 무갱신 경과 임계를
     * 넘긴 비종결 job 을 {@code FAILED(ERR_CD=EXPIRED)} 로 회수해 롤업을 진행시킨다. 관측 근거도
     * 유지된다 — WARN 로그 + 메트릭({@code augment.callback.rejected} tag {@code reason=output_path}).
     *
     * @return 검증을 통과한 경로 목록({@code results[]} 순서 그대로 — 위탁 항목과 짝짓는 재료)
     */
    private List<String> verifiedOutputPaths(GenAiCallbackRequest req, String requestId) {
        List<GenAiCallbackRequest.ResultItem> results = req.results();
        if (results == null || results.isEmpty()) {
            // SUCCEEDED 인데 산출물이 없다 = 계약 위반. 성공으로 접수하면 빈 증강본이 확정된다.
            metrics.callbackRejected(AugmentMetrics.REASON_MISSING_RESULTS);
            log.warn("[Webhook][GenAi] succeeded without results request_id={}", safe(requestId));
            throw new CustomException(ErrorCode.INVALID_INPUT, "SUCCEEDED 콜백에는 results 가 필요합니다.");
        }
        List<String> paths = new ArrayList<>(results.size());
        for (GenAiCallbackRequest.ResultItem item : results) {
            try {
                artifactRootResolver.verifyExternalReadablePath(item.outputFilePath());
            } catch (CustomException e) {
                metrics.callbackRejected(AugmentMetrics.REASON_OUTPUT_PATH);
                log.warn("[Webhook][GenAi] output path rejected (outside readable roots) request_id={} code={}"
                                + " — job 은 비종결로 남는다(재전송 대기). 재시도 소진 시 증강이 PENDING 에 머문다",
                        safe(requestId), e.getErrorCode());
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "output_file_path 가 허용된 저장 경로가 아닙니다.");
            }
            paths.add(item.outputFilePath());
        }
        return paths;
    }

    /** Log Injection (CWE-117) 방어 — CR/LF/TAB 제거. */
    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }

    /** 외부 문자열은 길이도 제한해 로그를 보호한다. */
    private static String truncateForLog(String s) {
        String cleaned = safe(s);
        return cleaned.length() <= LOG_MSG_MAX ? cleaned : cleaned.substring(0, LOG_MSG_MAX) + "...";
    }
}
