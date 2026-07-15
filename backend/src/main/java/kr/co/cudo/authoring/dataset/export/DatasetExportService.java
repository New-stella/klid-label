package kr.co.cudo.authoring.dataset.export;

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
 *   <li><b>무수정 재승인 멱등</b>: 직전 SUCCEEDED export 의 콘텐츠 해시와 현재 라벨 상태 해시가 같으면
 *       재산출을 skip 한다(중복 v2 생성 방지).</li>
 * </ul>
 */
@Service
public class DatasetExportService {

    private static final Logger log = LoggerFactory.getLogger(DatasetExportService.class);

    /** 버전 채번 UK 충돌 시 재시도 상한(백스톱은 UK). */
    static final int MAX_VERSION_RETRY = 3;

    private final DatasetExportTxService txService;
    private final DatasetExportWriter writer;

    public DatasetExportService(DatasetExportTxService txService, DatasetExportWriter writer) {
        this.txService = txService;
        this.writer = writer;
    }

    /**
     * 한 영상(rawSn)에 대해 원본+비식별 2벌의 학습데이터 파일을 산출한다.
     *
     * <p>파일 쓰기 실패는 export 레코드 FAILED 로만 반영하고 예외를 전파하지 않는다(승인 불변).
     * 로딩/멱등 판정 실패 등 그 외 예외는 상위(@Async 러너)가 삼킨다.
     */
    public void export(long rawSn) {
        Optional<ExportPreparation> prepOpt = txService.loadPreparation(rawSn);
        if (prepOpt.isEmpty()) {
            return; // 프레임/활성 메타 부재 — TxService 가 사유 로깅
        }
        ExportPreparation prep = prepOpt.get();

        // 무수정 재승인 멱등 — 직전 성공 산출과 라벨 상태가 같으면 재산출하지 않는다.
        if (prep.isUnchangedFromLastSucceeded()) {
            log.info("[DatasetExport] unchanged since last export — idempotent skip rawSn={}", rawSn);
            return;
        }

        InsertedExport inserted = insertWithRetry(rawSn, prep.contentHash());
        if (inserted == null) {
            log.error("[DatasetExport] version numbering exhausted retries — abort rawSn={}", rawSn);
            return;
        }

        try {
            ExportResult original = writer.write(
                    rawSn, ExportKind.ORIGINAL, inserted.version(), prep.ctx(), prep.frames());
            ExportResult deidentified = writer.write(
                    rawSn, ExportKind.DEIDENTIFIED, inserted.version(), prep.ctx(), prep.frames());
            int totalWritten = original.writtenCnt() + deidentified.writtenCnt();
            txService.markSucceeded(inserted.exportSn(), totalWritten);
            log.info("[DatasetExport] export succeeded rawSn={} version={} written={}",
                    rawSn, inserted.version(), totalWritten);
        } catch (RuntimeException e) {
            // HIGH — 파일 산출 실패가 검수 승인을 롤백시키지 않는다(@Async 분리 + 예외 삼킴).
            // export 레코드만 FAILED 로 표시한다. 경로 원문/PII 미출력(CWE-359/117).
            txService.markFailed(inserted.exportSn());
            log.warn("[DatasetExport] export failed — approval unaffected rawSn={} version={} cause={}",
                    rawSn, inserted.version(), e.getClass().getSimpleName());
        }
    }

    /**
     * 버전을 {@code count+1} 로 채번해 PENDING 레코드를 INSERT 한다. UK 위반 시 재채번 재시도.
     *
     * @return 예약된 산출 레코드, 재시도 소진 시 null
     */
    private InsertedExport insertWithRetry(long rawSn, String contentHash) {
        for (int attempt = 1; attempt <= MAX_VERSION_RETRY; attempt++) {
            try {
                return txService.insertNextVersion(rawSn, contentHash);
            } catch (DataIntegrityViolationException e) {
                // 동시 승인이 같은 버전을 선점 — 재채번(count 재조회) 후 재시도. UK 백스톱.
                log.warn("[DatasetExport] version UK conflict — retry rawSn={} attempt={}", rawSn, attempt);
            }
        }
        return null;
    }
}
