package kr.co.cudo.authoring.export.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.export.dto.ExportRequest;
import kr.co.cudo.authoring.export.dto.ExportStatusResponse;
import kr.co.cudo.authoring.export.entity.LsDataSet;
import kr.co.cudo.authoring.export.quartz.ExportJobScheduler;
import kr.co.cudo.authoring.export.repository.LsDataSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 10 — 학습데이터셋 내보내기 오케스트레이션.
 *
 * <p>책임:
 * <ul>
 *   <li>RBAC: prepareExport / getStatus 모두 REVIEWER 만 호출 가능</li>
 *   <li>LsDataSet (PENDING) INSERT</li>
 *   <li>Quartz Job 트리거 (JobDataMap 으로 exportSn 전달)</li>
 *   <li>실제 처리는 ExportRunner (Quartz Job 또는 단위 테스트가 호출)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class ExportService {

    private final LsDataSetRepository repository;
    private final ExportJobScheduler scheduler;

    /**
     * 내보내기 작업 등록 + Quartz Job 트리거 (비동기).
     */
    @Transactional("controlTransactionManager")
    public ExportStatusResponse prepareExport(ExportRequest req, TokenClaims actor) {
        requireReviewer(actor);
        LsDataSet entity = LsDataSet.createPending(req.pjtId(), req.format(), actor.sub());
        LsDataSet saved = repository.save(entity);
        scheduler.schedule(saved.getExportSn());
        log.info("[Export] prepared exportSn={} pjtId={} format={} actor={}",
                saved.getExportSn(), req.pjtId(), req.format(), actor.sub());
        return ExportStatusResponse.from(saved);
    }

    /**
     * 데이터셋(내보내기 작업) 목록 페이징 (REVIEWER 의 /manage/datasets 화면용).
     */
    public Page<ExportStatusResponse> listDatasets(Pageable pageable, TokenClaims actor) {
        requireReviewer(actor);
        return repository.findAllByOrderByRegisteredAtDesc(pageable)
                .map(ExportStatusResponse::from);
    }

    /**
     * 단건 상태 조회. REVIEWER 만.
     */
    public ExportStatusResponse getStatus(Long exportSn, TokenClaims actor) {
        requireReviewer(actor);
        LsDataSet entity = repository.findById(exportSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "export 작업을 찾을 수 없습니다: " + exportSn));
        return ExportStatusResponse.from(entity);
    }

    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }
}
