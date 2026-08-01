package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 증강 위탁 job 1건의 <b>성공 산출물 적용</b> — 웹훅 경로와 결과조회 회수 경로의 <b>단일 원천</b>.
 *
 * <h3>왜 분리했는가 (S15 — 정적 가드의 한계 보완)</h3>
 * <p>{@code output_file_path} 는 <b>외부가 준 NAS 절대경로</b>라 파일시스템에 닿기 전에
 * {@link VideoArtifactRootResolver#verifyExternalReadablePath} 를 반드시 통과해야 한다(CWE-22).
 * 이 규약을 강제하는 {@code ExternalAugmentClientContractGuardTest} 는 <b>파일 단위 정적 스캔</b>이라,
 * 회수 경로(INT-030)를 <b>다른 파일로 새로 구현하면 가드가 초록인 채로 검증이 빠진다</b>(그 테스트의
 * "한계" 절이 명시한 우회 형태 그대로다). 그래서 회수 경로는 새 검증 로직을 쓰지 않고 <b>웹훅이 쓰던
 * 이 코드를 그대로</b> 호출한다.
 *
 * <h3>순서 대응 계약 (fail-closed)</h3>
 * <p>계약상 {@code results[]} 에는 입력 식별자가 없어 <b>순서</b>로만 대응시킬 수 있다. 위탁 시점에
 * 못박아 둔 항목({@code LS_DATA_AUG_JOB_FILE}, {@code FILE_SEQ} 오름차순)과 수신 결과를 같은 순서로
 * 짝짓고, <b>건수가 다르면 성공으로 접수하지 않고</b> 그 job 을 FAILED 로 종결한다 — 부분/오정렬
 * 산출물로 프레임셋을 채우면 다른 프레임에 남의 증강본이 붙는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AugmentJobSuccessApplier {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    /** 위탁 시점에 못박은 순서↔프레임 대응 — 여기에 산출 경로를 되붙인다(Phase 7-D). */
    private final LsDataAugJobFileRepository jobFileRepository;
    /** 외부가 준 {@code output_file_path} 가 <b>읽기</b> 허용 루트 하위인지 확인한다(CWE-22). */
    private final VideoArtifactRootResolver artifactRootResolver;
    /** 거부 사유 집계 — 상태를 바꾸지 않는 거부가 조용히 고착되는 것을 운영이 감지할 근거. */
    private final AugmentMetrics metrics;

    /**
     * 외부 산출 경로 목록을 <b>정규화 후</b> 읽기 허용 루트 하위인지 검증한다(CWE-22).
     *
     * <p>판정 축은 {@link VideoArtifactRootResolver#verifyExternalReadablePath} 다 — 벤더 산출물은
     * 우리가 <b>읽어서</b> 파생 프레임으로 복사할 대상이므로, 쓰기 base allowlist
     * ({@code raw-mount-roots}) 를 넓히지 않고 별도 읽기 루트({@code external-read-roots})로 허용한다.
     *
     * <p>하나라도 허용 밖이면 예외로 끊고 <b>아무 상태도 바꾸지 않는다</b>. 거부 메시지에 경로 원문·
     * 내부 디렉터리 구조를 담지 않는다(CWE-209).
     *
     * @param outputFilePaths 외부가 준 경로 목록({@code results[]} 순서 그대로)
     * @param logKey          로그 상관키(request_id 등 — sanitize 후 출력)
     * @return 검증을 통과한 경로 목록(입력 순서 보존)
     * @throws CustomException 빈 결과(계약 위반) 또는 허용 루트 밖 경로
     */
    public List<String> verifyOutputPaths(List<String> outputFilePaths, String logKey) {
        if (outputFilePaths == null || outputFilePaths.isEmpty()) {
            // SUCCEEDED 인데 산출물이 없다 = 계약 위반. 성공으로 접수하면 빈 증강본이 확정된다.
            metrics.callbackRejected(AugmentMetrics.REASON_MISSING_RESULTS);
            log.warn("[Augment] succeeded without results key={}", safe(logKey));
            throw new CustomException(ErrorCode.INVALID_INPUT, "SUCCEEDED 결과에는 results 가 필요합니다.");
        }
        List<String> verified = new ArrayList<>(outputFilePaths.size());
        for (String path : outputFilePaths) {
            try {
                artifactRootResolver.verifyExternalReadablePath(path);
            } catch (CustomException e) {
                metrics.callbackRejected(AugmentMetrics.REASON_OUTPUT_PATH);
                log.warn("[Augment] output path rejected (outside readable roots) key={} code={}"
                                + " — job 은 비종결로 남는다(재전송/재회수 대기)",
                        safe(logKey), e.getErrorCode());
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "output_file_path 가 허용된 저장 경로가 아닙니다.");
            }
            verified.add(path);
        }
        return verified;
    }

    /**
     * 검증된 산출 경로를 위탁 항목에 순서대로 되붙이고 job 을 성공 종결한다.
     *
     * <p>건수가 어긋나면 성공으로 접수하지 않고 이 job 을 {@code FAILED}
     * ({@link LsDataAugJob#ERR_RESULT_COUNT_MISMATCH})로 종결한다 — 롤업의 "부분 실패 = 전체 실패"
     * 규칙에 따라 증강 1건도 실패로 끝난다(fail-closed).
     *
     * @param target        대상 job (호출자가 증강 행을 FOR UPDATE 로 잠근 상태여야 한다)
     * @param externalJobId 외부 job_id (null 이면 기존 값 유지)
     * @param outputs       {@link #verifyOutputPaths} 를 통과한 경로 목록
     * @param logKey        로그 상관키
     * @return true = 성공 종결 / false = 건수 불일치로 실패 종결
     */
    public boolean applySucceeded(LsDataAugJob target, String externalJobId,
                                  List<String> outputs, String logKey) {
        List<LsDataAugJobFile> files =
                jobFileRepository.findByAugJobSnOrderByFileSeqAsc(target.getAugJobSn());
        if (files.size() != outputs.size()) {
            target.markFailed(externalJobId, LsDataAugJob.ERR_RESULT_COUNT_MISMATCH,
                    "위탁 " + files.size() + "건 대비 수신 " + outputs.size() + "건");
            log.warn("[Augment] result count mismatch — job failed (fail-closed) key={} "
                            + "jobSeq={} issuedCount={} receivedCount={}",
                    safe(logKey), target.getJobSeq(), files.size(), outputs.size());
            return false;
        }
        for (int i = 0; i < files.size(); i++) {
            files.get(i).applyResultPath(outputs.get(i));
        }
        jobFileRepository.saveAll(files);
        target.markSucceeded(externalJobId);
        log.info("[Augment] job succeeded key={} jobSeq={} outputCount={}",
                safe(logKey), target.getJobSeq(), outputs.size());
        return true;
    }

    /** Log Injection (CWE-117) 방어 — CR/LF/TAB 제거. */
    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
