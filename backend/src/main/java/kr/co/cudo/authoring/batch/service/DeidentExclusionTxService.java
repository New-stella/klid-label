package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비식별 제외(출처유형 제외) 결과 <b>기록</b> — 짧은 독립 트랜잭션 전용 빈.
 *
 * <p>왜 별도 빈인가: 원본 복사({@link DeidentExclusionService})는 대용량 파일 I/O 라 DB 트랜잭션 밖에서
 * 해야 하고, 기록은 복사가 끝난 뒤 짧게 커밋해야 한다. 같은 빈 안의 자기호출로는 트랜잭션 프록시를
 * 타지 않으므로 기록을 이 빈으로 분리한다.
 *
 * <p>KPST 계열 Tx 서비스({@code KpstDeidentTxService})에 두지 않는 이유: 그 빈은
 * {@code kpst.deid.enabled=true} 일 때만 존재하는데, 제외 처리는 외부 연동이 꺼져 있어도 동작해야 한다.
 *
 * @design ADR-066
 * @design ERD-017
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeidentExclusionTxService {

    /** 제외 성공 이력 등록자 — REG_ID(varchar 30) 이내. */
    public static final String EXCLUDED_PROC_REG_ID = "batch-deident-excluded";

    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final DeidentApprovalHoldReleaser deidentApprovalHoldReleaser;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;

    /**
     * 제외 성공 — 이력 SUCCEEDED + 요청종류 EXCLUDED + 복사본 경로, 영상 비식별여부 'Y'.
     *
     * <p>{@code MARKING_READY} 전이는 여기서 하지 않는다 — 동기 완료 결과를 받은 호출자
     * ({@code AsyncDeidentifyRunner}·{@code DevPipelineRunner})가 기존 분기에서 전이하고 예약 마킹을 깨운다.
     * 외부 위탁 완료 지점과 같은 부수 동작(검수 승인 보류 해제·스트림 메타 캐시 무효화)은 함께 수행한다
     * (둘 다 통상 영상에서는 no-op).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long rawSn, String orgnlFilePathNm, String copiedFilePathNm) {
        LsDataRaw managed = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null, orgnlFilePathNm, EXCLUDED_PROC_REG_ID);
        procLog.markExcluded();
        procLog.succeed(copiedFilePathNm);
        procLogRepository.save(procLog);
        managed.markDeidentified("Y");
        deidentApprovalHoldReleaser.releaseOnDeidentSuccess(managed);
        streamMetaCacheEvictor.evictAfterCommit(rawSn);
    }

    /**
     * 제외 실패 — 영상 비식별여부 'F' + 이력 FAILED(요청종류 EXCLUDED). {@code MARKING_READY} 미전이.
     *
     * <p>{@link BatchTransitionService#recordDeidentFailure} 와 같은 독립 커밋 규약이되, 실패 행을 <b>한 건</b>만
     * 남기면서 요청종류를 EXCLUDED 로 표시하기 위해 여기서 직접 기록한다(그 메서드를 부르면 요청종류가 빈
     * 실패 행이 따로 생긴다). {@code errorCode}/{@code detail} 에 경로 원문·PII 를 담지 않는다(CWE-209).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long rawSn, String errorCode, String detail) {
        if (rawSn == null) {
            return;
        }
        LsDataRaw managed = videoRepository.findById(rawSn).orElse(null);
        if (managed == null) {
            log.warn("[Deident] raw video not found rawSn={} (excluded-fail)", rawSn);
            return;
        }
        managed.markDeidentified("F");
        String orgnlPath = managed.getRawFilePathNm();
        if (orgnlPath == null || orgnlPath.isBlank()) {
            orgnlPath = "N/A";
        }
        LsDeidentProcLog failLog = LsDeidentProcLog.request(
                rawSn, null, orgnlPath, BatchTransitionService.DEIDENT_FAIL_PROC_REG_ID);
        failLog.markExcluded();
        failLog.fail(errorCode, detail);
        procLogRepository.save(failLog);
        log.warn("[Deident] excluded deident failure recorded rawSn={} errCd={}", rawSn, errorCode);
    }
}
