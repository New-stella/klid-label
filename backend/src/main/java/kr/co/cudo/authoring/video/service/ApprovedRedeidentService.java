package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.dto.RedeidentResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 검수완료 영상 재비식별(Approved Re-deidentification) 오케스트레이션 서비스 (Phase 3 / UC018).
 *
 * <p>REVIEWER 가 검수완료(APPROVED) 영상의 비식별을 재수행 요청하면, 전제조건을 검증하고 작업락을
 * 선점한 뒤 KPST 위탁(REDEIDENT 표시)을 수행한다. 완료(비식별 프레임 attach + DE_IDNTF_YN='Y' +
 * PRVC 정정)는 폴링 잡({@code KpstDeidentPollJob} → {@code KpstDeidentTxService}) 이 비동기로 이어받는다.
 *
 * <h3>전제조건(요청 거부 가드)</h3>
 * <ul>
 *   <li>영상 미존재 → 404 NOT_FOUND.</li>
 *   <li>검수완료(LS_RAW_DATA_STATUS.DATA_STTS_CD=APPROVED) 가 아니면 → 409 CONFLICT.</li>
 *   <li><b>이미 비식별됨(DE_IDENT_YN='Y') 배제</b> → 409 CONFLICT. 네이티브 기비식별(frm_no=추출순번)
 *       영상을 배제해 frm_no 의미 불일치를 원천 차단한다. 대상은 'N'/'F'(미비식별/마이그레이션,
 *       frm_no=실프레임번호)로 한정된다.</li>
 *   <li>이미 잠금(작업락 선점) 상태면 → 409 CONFLICT (멱등 — 중복/동시 요청 차단).</li>
 * </ul>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>인가(CWE-285): 본 서비스는 REVIEWER 전제다(엔드포인트 {@code @PreAuthorize} 는 Phase 4 컨트롤러).
 *       IDOR 의식 — 영상 ID 만으로 임의 처리되지 않도록 상태/락 가드를 둔다.</li>
 *   <li>동시성(CWE-362): 동일 영상 활성 락 1건을 강제하는 partial unique index(V69)가 최후 방어다.
 *       선제 {@code isRawLocked} 검사를 통과한 두 동시 요청 중 하나만 락 INSERT 에 성공하며, 나머지는
 *       DB 가 원자적으로 거부({@link DataIntegrityViolationException})한다. 이를 CONFLICT 로 변환해
 *       KPST 이중 위탁을 차단한다.</li>
 *   <li>Privacy(CWE-359): 로그에 rawSn/actor sub 식별자만 출력. PII/원본 경로 미출력.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class ApprovedRedeidentService {

    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final WorkLockService workLockService;
    private final KpstDeidentService kpstDeidentService;

    /**
     * 검수완료 영상 재비식별 요청 — 전제조건 검증 → 작업락 선점 → KPST 위탁(REDEIDENT) → ACCEPTED.
     *
     * @param rawSn 대상 영상 ID
     * @param actor 요청자(REVIEWER) 토큰 클레임
     * @return 수락 응답(비동기 — 완료는 폴링 잡이 이어받음)
     */
    @Transactional(value = "controlTransactionManager")
    public RedeidentResponse requestRedeident(Long rawSn, TokenClaims actor) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        // 1) 영상 로드 (404)
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 2) 검수완료(APPROVED) 전제 검증
        if (!isReviewApproved(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "검수완료 영상만 재비식별 가능합니다.");
        }
        // 2-1) 이미 비식별된(네이티브 기비식별) 영상 배제 — frm_no 의미 불일치 원천 차단
        if ("Y".equals(raw.getDeIdntfYn())) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별된 영상입니다.");
        }

        // 3) 작업락 선점 — 진행 중(이미 잠김)이면 409, 아니면 선점
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }
        // M-3: actor null 폴백("reviewer") 제거 — 감사 추적이 끊기는 fail-open 방지(서비스 방어).
        // Controller(Phase4)가 인증을 강제하나, 서비스 레벨에서도 인증 토큰을 필수화한다(fail-closed).
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "재비식별 요청에는 인증 토큰이 필요합니다.");
        }
        String ownerId = actor.sub();
        // 동시 INSERT 충돌(V69 partial unique index) → 409. 선제 isRawLocked 검사를 통과한
        // 두 동시 요청 중 하나만 락 INSERT 에 성공하고, 나머지는 DB 가 원자적으로 거부한다.
        // 예외를 여기서 CONFLICT 로 변환해 KPST 이중 위탁(submit)을 시작하기 전에 차단한다(CWE-362).
        try {
            workLockService.lockRawForRedeident(rawSn, ownerId);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 재비식별 처리 중인 영상입니다.");
        }

        // 4) KPST 위탁 + REDEIDENT 표시 (procLog REQ_KIND_CD=REDEIDENT)
        LsDeidentProcLog procLog = kpstDeidentService.submit(raw, true);

        log.info("[Redeident] requested rawSn={} actor={} procLogSn={} prjId={}",
                rawSn, ownerId, procLog.getProcLogSn(), procLog.getKpstPrjId());

        // 5) 비동기 — 폴링 잡이 완료를 이어받는다.
        return RedeidentResponse.accepted(rawSn, procLog.getProcLogSn(), procLog.getKpstPrjId());
    }

    /**
     * 영상(rawSn) 의 검수 상태가 APPROVED(검수 완료) 인지 판정. 상태 row 가 없으면 미검수로 간주.
     * (DeidentReportService.isReviewApproved 와 동일 패턴)
     */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }
}
