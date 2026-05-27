package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentSummaryResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Phase 9 — 데이터 증강 검수 (V1.5 SFR-07).
 *
 * <p>외부 SFR-07 시스템이 생성한 4종 증강 결과(WINTER/NIGHT/RAIN/RESOLUTION)를
 * REVIEWER 가 검수(accept/reject)한다. 결과는 외부 시스템에도 best-effort 동기화된다.
 *
 * <p>RBAC: 모든 결정 메서드는 REVIEWER 만 호출 가능 (Service 이중 검증).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentReviewService {

    /** UI 표시용 4종 ENUM 정렬 순서. */
    private static final Map<String, Integer> AUG_ORDER = Map.of(
            LsDataAug.AUG_WINTER, 1,
            LsDataAug.AUG_NIGHT, 2,
            LsDataAug.AUG_RAIN, 3,
            LsDataAug.AUG_RESOLUTION, 4
    );

    private final LsDataAugRepository repository;
    private final LsDataAugRvwRepository reviewRepository;
    private final ExternalAugmentClient externalClient;

    /**
     * REVIEWER/WORKER 의 증강 결과 전체 페이징 조회 (srcSn 미지정 시 화면용 목록).
     */
    public Page<AugmentSummaryResponse> listAll(Pageable pageable) {
        return repository.findAllByOrderByRegisteredAtDesc(pageable)
                .map(this::toResponse);
    }

    /**
     * 원본 영상(srcSn)에 대한 4종 증강 결과 묶음 조회.
     * UI 표시 순서(WINTER → NIGHT → RAIN → RESOLUTION)로 정렬.
     */
    public List<AugmentSummaryResponse> findBySource(Long srcSn) {
        return repository.findBySrcSnOrderByAugTypeCd(srcSn).stream()
                .sorted(Comparator.comparingInt(a -> AUG_ORDER.getOrDefault(a.getAugTypeCd(), 99)))
                .map(this::toResponse)
                .toList();
    }

    /**
     * REVIEWER 가 증강 결과를 승인. PENDING → ACCEPTED.
     * LS_DATA_AUG_RVW row INSERT/갱신 + LsDataAug.augProcSttsCd 동기 갱신 (DB 설계서 라인 162-169 호환).
     */
    @Transactional("controlTransactionManager")
    public AugmentSummaryResponse accept(Long dataAugSn, TokenClaims actor) {
        requireReviewer(actor);
        LsDataAug aug = loadOrThrow(dataAugSn);
        // 1) LsDataAug.augProcSttsCd 갱신 (DB 설계서 호환 — PENDING 이외면 CONFLICT)
        aug.applyReviewStatus(LsDataAug.STTS_ACCEPTED);
        // 2) LS_DATA_AUG_RVW row INSERT/갱신
        LsDataAugRvw review = loadOrCreateReview(aug, actor.sub());
        review.accept(actor.sub(), LocalDateTime.now());
        // 외부 통보 (best-effort — 실패해도 본 트랜잭션 영향 없음)
        try {
            externalClient.syncDecision(dataAugSn, LsDataAug.STTS_ACCEPTED, null);
        } catch (Exception e) {
            log.warn("[Augment] external sync failed dataAugSn={} decision=ACCEPTED err={}",
                    dataAugSn, sanitize(e.getMessage()));
        }
        log.info("[Augment] accepted dataAugSn={} actor={}", dataAugSn, sanitize(actor.sub()));
        return AugmentSummaryResponse.from(aug, review);
    }

    /**
     * REVIEWER 가 증강 결과를 반려. PENDING → REJECTED. 사유 필수.
     * LS_DATA_AUG_RVW row INSERT/갱신 + LsDataAug.augProcSttsCd 동기 갱신.
     */
    @Transactional("controlTransactionManager")
    public AugmentSummaryResponse reject(Long dataAugSn, String reason, TokenClaims actor) {
        requireReviewer(actor);
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "반려 사유는 필수입니다.");
        }
        LsDataAug aug = loadOrThrow(dataAugSn);
        // 1) LsDataAug.augProcSttsCd 갱신 (DB 설계서 호환 — PENDING 이외면 CONFLICT)
        aug.applyReviewStatus(LsDataAug.STTS_REJECTED);
        // 2) LS_DATA_AUG_RVW row INSERT/갱신
        LsDataAugRvw review = loadOrCreateReview(aug, actor.sub());
        review.reject(reason, actor.sub(), LocalDateTime.now());
        try {
            externalClient.syncDecision(dataAugSn, LsDataAug.STTS_REJECTED, reason);
        } catch (Exception e) {
            log.warn("[Augment] external sync failed dataAugSn={} decision=REJECTED err={}",
                    dataAugSn, sanitize(e.getMessage()));
        }
        log.info("[Augment] rejected dataAugSn={} actor={} reasonLen={}",
                dataAugSn, sanitize(actor.sub()), reason.length());
        return AugmentSummaryResponse.from(aug, review);
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }

    private AugmentSummaryResponse toResponse(LsDataAug aug) {
        return AugmentSummaryResponse.from(aug,
                reviewRepository.findLatestByDataAugSn(aug.getDataAugSn()).orElse(null));
    }

    private LsDataAugRvw loadOrCreateReview(LsDataAug aug, String actorId) {
        // PJT_SN 0L — Phase 5+7 와 동일 정책 (프로젝트 매핑 컬럼 미연결).
        return reviewRepository.findLatestByDataAugSn(aug.getDataAugSn())
                .orElseGet(() -> reviewRepository.save(
                        LsDataAugRvw.pending(aug.getDataAugSn(), 0L, aug.getSrcSn(),
                                aug.getLblIntgrtPct(), actorId)));
    }

    private LsDataAug loadOrThrow(Long dataAugSn) {
        return repository.findById(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));
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
