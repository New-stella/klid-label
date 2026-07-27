package kr.co.cudo.authoring.dataset.export;

import io.micrometer.core.instrument.Timer;
import kr.co.cudo.authoring.observability.metrics.DatasetExportMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Phase 4 — 검수 승인 후 학습데이터 파일 산출 <b>오케스트레이터</b>.
 *
 * <p>Phase 1~3 자산({@link DatasetExportPathResolver}·{@code NiaJsonBuilder} 컨텍스트·
 * {@link DatasetExportWriter}·{@code LS_DATASET_EXPORT})을 조립해, 한 영상에 대해
 * <b>원본(orgnl)+비식별(deid) 2벌</b>의 프레임 이미지+JSON 을 산출한다. DB I/O 는 REQUIRES_NEW 경계의
 * {@link DatasetExportTxService} 에 위임하고, 이 클래스는 <b>의사결정·재시도·파일쓰기</b>만 담당한다.
 *
 * <h3>정합·동시성·멱등 방어 (HIGH)</h3>
 * <ul>
 *   <li><b>파일실패 ↔ 승인 정합</b>: 이 흐름은 승인 커밋 후 {@code @Async} 로 분리 실행되며, 파일 쓰기
 *       실패는 예외를 삼키고 export 레코드를 FAILED 로만 기록한다 — 검수 승인은 절대 롤백되지 않는다.</li>
 *   <li><b>버전 채번 TOCTOU(CWE-362)</b>: {@code count+1} 채번은 UK(DATA_RAW_SN,EXPORT_VER_NO) 위반 시
 *       재채번으로 재시도한다({@link #MAX_VERSION_RETRY}회). UK 가 최종 백스톱이라 동시 승인에도 버전이
 *       유일하게 부여된다.</li>
 *   <li><b>승인 경로는 항상 강제 재생성(R6)</b>: 검수 승인({@code onReviewApproved}) 트리거는
 *       {@code forceRegenerate=true} 로 진입해, 내용 변경 여부와 무관하게 <b>매 승인마다 새 버전 폴더 +
 *       JSON/이미지를 전량 재생성</b>한다(멱등 skip 미적용). 최초 승인·무수정 재승인 모두 새 버전을 채번한다.</li>
 *   <li><b>재동결 경로만 멱등 skip 유지</b>: event_annotation 지연 승인 등 재동결
 *       ({@code onReExport}, {@code forceRegenerate=false}) 은 직전 SUCCEEDED/PARTIAL(멱등 baseline)
 *       export 의 콘텐츠 해시와 현재 라벨 상태 해시가 같으면 재산출을 skip 한다(중복 v2 생성 방지). PARTIAL 을
 *       baseline 에 포함해, 원천 이미지가 지속 부재한 영상의 무수정 재동결이 매번 새 버전을 채번하며 이미지
 *       파일을 무한 재복사(디스크 누적)하는 회귀를 막는다.</li>
 * </ul>
 *
 * <p><b>retention (범위 밖 — 후속 Phase 백로그)</b>: 승인마다 새 버전 + 프레임 2벌(orgnl/deid) 복사가
 * 누적되나(R6 확정), 구 버전 정리(retention) 잡은 미구현이다. 별도 Phase 에서 보존 정책·정리 잡을 도입한다.
 */
@Service
public class DatasetExportService {

    private static final Logger log = LoggerFactory.getLogger(DatasetExportService.class);

    /** 버전 채번 UK 충돌 시 재시도 상한(백스톱은 UK). */
    static final int MAX_VERSION_RETRY = 3;

    /** result/duration outcome 태그 값(저카디널리티 — 종결 분기별 고정 문자열). */
    private static final String OUTCOME_COMPLETED = "completed";
    private static final String OUTCOME_PARTIAL = "partial";
    private static final String OUTCOME_FAILED = "failed";
    private static final String OUTCOME_VERSION_EXHAUSTED = "version_exhausted";
    private static final String OUTCOME_IDEMPOTENT_SKIP = "idempotent_skip";
    private static final String OUTCOME_NO_INPUT = "no_input";

    private final DatasetExportTxService txService;
    private final DatasetExportWriter writer;
    private final DatasetExportPathResolver pathResolver;
    private final DatasetExportMetrics metrics;

    public DatasetExportService(DatasetExportTxService txService, DatasetExportWriter writer,
                                DatasetExportPathResolver pathResolver,
                                DatasetExportMetrics metrics) {
        this.txService = txService;
        this.writer = writer;
        this.pathResolver = pathResolver;
        this.metrics = metrics;
    }

    /**
     * 한 영상(rawSn)에 대해 원본+비식별 2벌의 학습데이터 파일을 산출한다(재동결 기본 경로, 멱등 skip 적용).
     *
     * <p>{@code forceRegenerate=false} 로 위임하는 편의 오버로드다. 재동결({@code onReExport})·통합 시험
     * 하네스가 사용하며, 직전 export 와 동일 해시면 멱등 skip 한다. 승인 경로(R6 강제 재생성)는
     * {@link #export(long, boolean)} 을 {@code true} 로 호출한다.
     */
    public void export(long rawSn) {
        export(rawSn, false);
    }

    /**
     * 한 영상(rawSn)에 대해 원본+비식별 2벌의 학습데이터 파일을 산출한다.
     *
     * <p>파일 쓰기 실패는 export 레코드 FAILED 로만 반영하고 예외를 전파하지 않는다(승인 불변).
     * 로딩/멱등 판정 실패 등 그 외 예외는 상위(@Async 러너)가 삼킨다.
     *
     * @param forceRegenerate 승인 경로(R6)면 {@code true} — 직전과 동일 해시여도 멱등 skip 없이 전량 재생성.
     *                        재동결 경로면 {@code false} — 직전 export 와 동일 해시면 멱등 skip.
     */
    public void export(long rawSn, boolean forceRegenerate) {
        // 관찰성(observability.md) — 각 호출당 result counter 정확히 1회, duration timer 정확히 1회 stop.
        // outcome 지역변수를 종결 분기마다 확정하고, finally 단일 지점에서 배타적으로 기록한다.
        // 계측 코드는 예외를 던지지 않아 기존 예외 전파 계약(승인 불변)을 바꾸지 않는다.
        Timer.Sample sample = metrics.startSample();
        String outcome = OUTCOME_FAILED; // fail-secure 기본값 — 로딩/판정 중 예외 이탈 시 failed 로 관측
        int skippedFrames = 0;
        try {
            Optional<ExportPreparation> prepOpt = txService.loadPreparation(rawSn);
            if (prepOpt.isEmpty()) {
                outcome = OUTCOME_NO_INPUT; // 프레임/활성 메타 부재 — TxService 가 사유 로깅
                return;
            }
            ExportPreparation prep = prepOpt.get();

            // 재동결 경로만 멱등 skip — 직전 성공/부분 산출(멱등 baseline)과 라벨 상태가 같으면 재산출하지 않는다.
            // 승인 경로(forceRegenerate=true, R6)는 무수정 재승인도 항상 전량 재생성하므로 skip 을 건너뛴다.
            if (!forceRegenerate && prep.isUnchangedFromLastExport()) {
                log.info("[DatasetExport] unchanged since last export — idempotent skip rawSn={}", rawSn);
                outcome = OUTCOME_IDEMPOTENT_SKIP;
                return;
            }

            // A-1/S1/S9 — 산출 base 를 <b>먼저</b> 검증한다. 원본 경로(RAW_FILE_PATH_NM)가 비었거나 허용
            // 마운트 루트 밖(손상 데이터·호스트 절대경로 등)이면 기본 루트로 조용히 새지 않고(fail-secure)
            // export 를 FAILED 로 마감한다. 이 흐름은 승인 커밋 이후 @Async 라 승인은 롤백되지 않는다.
            // RuntimeException 전체를 잡는 이유: NPE/InvalidPath 등이 러너로 새면 PENDING 고착(스윕 대기)이 된다.
            String videoRoot;
            try {
                videoRoot = pathResolver.resolveVideoRoot(rawSn, prep.rawFilePathNm()).toString();
            } catch (RuntimeException e) {
                markBaseRejected(rawSn, prep.contentHash(), e);
                outcome = OUTCOME_FAILED;
                return;
            }

            // TODO(retention, 후속 Phase): 승인 경로(force=true)는 무수정 재승인도 매번 새 버전 + 프레임 2벌을
            //  누적한다(R6 확정 허용). 구 버전 정리(retention) 잡·보존 정책은 이번 범위 밖 — 별도 Phase 에서 도입.
            InsertedExport inserted = insertWithRetry(rawSn, prep.contentHash(), videoRoot);
            if (inserted == null) {
                log.error("[DatasetExport] version numbering exhausted retries — abort rawSn={}", rawSn);
                outcome = OUTCOME_VERSION_EXHAUSTED;
                return;
            }

            try {
                // E-ISSUE-41(정책 A) — 파생영상(해상도 파생)은 원본 픽셀이 실재하지 않아 프레임의
                // SRC_FILE_PATH_NM 이 전부 비어 있다. 없는 원본을 있는 척 산출하지 않도록 ORIGINAL 벌을
                // 아예 만들지 않으며, 이 부재는 "정상"이므로 PARTIAL 판정 대상에서도 제외한다
                // (구 동작: ORIGINAL 전 프레임이 skip 으로 집계돼 항상 PARTIAL 로 강등).
                boolean originalAbsent = hasNoOriginalFrames(prep);
                int totalWritten = 0;
                int totalSkipped = 0;
                if (originalAbsent) {
                    log.info("[DatasetExport] original kind skipped — derivative video has no original frames "
                            + "rawSn={} version={} reason=DERIVATIVE_NO_ORIGINAL", rawSn, inserted.version());
                } else {
                    ExportResult original = writer.write(
                            rawSn, prep.rawFilePathNm(), ExportKind.ORIGINAL, inserted.version(),
                            prep.ctx(), prep.frames());
                    totalWritten += original.writtenCnt();
                    totalSkipped += original.skippedCnt();
                }
                ExportResult deidentified = writer.write(
                        rawSn, prep.rawFilePathNm(), ExportKind.DEIDENTIFIED, inserted.version(),
                        prep.ctx(), prep.frames());
                totalWritten += deidentified.writtenCnt();
                totalSkipped += deidentified.skippedCnt();
                if (totalWritten == 0) {
                    // 아무 프레임도 산출 못함 = 사실상 실패 — FAILED 로 마감(승인 불변).
                    txService.markFailed(inserted.exportSn());
                    log.warn("[DatasetExport] nothing produced — marked FAILED rawSn={} version={}",
                            rawSn, inserted.version());
                    outcome = OUTCOME_FAILED;
                } else if (totalSkipped > 0) {
                    // 일부만 산출 — 원천 이미지 부재 등으로 건너뛴 프레임이 있어 PARTIAL 로 마감.
                    txService.markPartial(inserted.exportSn(), totalWritten);
                    log.warn("[DatasetExport] partial export rawSn={} version={} written={} skipped={}",
                            rawSn, inserted.version(), totalWritten, totalSkipped);
                    outcome = OUTCOME_PARTIAL;
                    skippedFrames = totalSkipped;
                } else {
                    txService.markSucceeded(inserted.exportSn(), totalWritten);
                    log.info("[DatasetExport] export succeeded rawSn={} version={} written={}",
                            rawSn, inserted.version(), totalWritten);
                    outcome = OUTCOME_COMPLETED;
                    skippedFrames = totalSkipped; // 완전성공은 0
                }
            } catch (RuntimeException e) {
                // HIGH — 파일 산출 실패가 검수 승인을 롤백시키지 않는다(@Async 분리 + 예외 삼킴).
                // export 레코드만 FAILED 로 표시한다. 경로 원문/PII 미출력(CWE-359/117).
                txService.markFailed(inserted.exportSn());
                log.warn("[DatasetExport] export failed — approval unaffected rawSn={} version={} cause={}",
                        rawSn, inserted.version(), e.getClass().getSimpleName());
                outcome = OUTCOME_FAILED; // 성공/부분 분기 미도달 — failed 로만 1회 계상(이중계상 없음)
            }
        } finally {
            // 배타적 단일 기록 — 조기 return/예외 이탈 어느 경로든 정확히 1회.
            sample.stop(metrics.durationTimer(outcome));
            metrics.recordResult(outcome);
            if (OUTCOME_PARTIAL.equals(outcome) || OUTCOME_COMPLETED.equals(outcome)) {
                // 부재 프레임 총량 관측 — 완전성공은 0 증가(카운터 등록만).
                metrics.incrementSkippedFrames(skippedFrames);
            }
        }
    }

    /**
     * 이 영상의 프레임이 <b>원본 경로를 하나도 갖지 않는가</b>(= 파생영상이라 원본 픽셀 부재).
     *
     * <p>파생 유형(증강/해상도)을 추정하지 않고 <b>데이터 사실</b>로 판정한다 — 외부 증강 파생은 원본
     * 프레임을 실제로 보유하므로 이 판정에 걸리지 않고 기존대로 2벌이 산출된다. 프레임이 0건이면
     * (loadPreparation 이 이미 걸러내지만) 보수적으로 false 를 반환해 기존 경로를 그대로 탄다.
     */
    private static boolean hasNoOriginalFrames(ExportPreparation prep) {
        if (prep.frames() == null || prep.frames().isEmpty()) {
            return false;
        }
        return prep.frames().stream().allMatch(f -> f == null || f.frame() == null
                || f.frame().getSrcFilePathNm() == null || f.frame().getSrcFilePathNm().isBlank());
    }

    /**
     * 산출 base 거부(S1) — 원본 경로가 없거나 허용 마운트 루트 밖이라 산출 루트를 만들 수 없는 경우,
     * 흔적 없이 사라지지 않도록 export 레코드를 남기고 즉시 FAILED 로 마감한다(경로는 null).
     *
     * <p>승인 트랜잭션은 이미 커밋된 뒤이므로 롤백되지 않는다. 로그·예외 메시지에 경로 원문이나 NAS
     * 구조를 담지 않는다(CWE-209) — rawSn 과 ErrorCode 만 남긴다.
     */
    private void markBaseRejected(long rawSn, String contentHash, RuntimeException cause) {
        String reason = (cause instanceof kr.co.cudo.authoring.common.exception.CustomException ce)
                ? String.valueOf(ce.getErrorCode())
                : cause.getClass().getSimpleName();
        try {
            InsertedExport rejected = insertWithRetry(rawSn, contentHash, null);
            if (rejected != null) {
                txService.markFailed(rejected.exportSn());
            }
        } catch (RuntimeException e) {
            // 기록 실패도 승인에 영향을 주지 않는다 — 관측만 남긴다.
            log.error("[DatasetExport] failed to record base rejection rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
        }
        log.error("[DatasetExport] export base rejected — marked FAILED rawSn={} reason={}", rawSn, reason);
    }

    /**
     * 버전을 {@code count+1} 로 채번해 PENDING 레코드를 INSERT 한다. UK 위반 시 재채번 재시도.
     *
     * @return 예약된 산출 레코드, 재시도 소진 시 null
     */
    private InsertedExport insertWithRetry(long rawSn, String contentHash, String exportPathNm) {
        for (int attempt = 1; attempt <= MAX_VERSION_RETRY; attempt++) {
            try {
                return txService.insertNextVersion(rawSn, contentHash, exportPathNm);
            } catch (DataIntegrityViolationException e) {
                // 동시 승인이 같은 버전을 선점 — 재채번(count 재조회) 후 재시도. UK 백스톱.
                log.warn("[DatasetExport] version UK conflict — retry rawSn={} attempt={}", rawSn, attempt);
            }
        }
        return null;
    }
}
