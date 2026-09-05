package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentSummaryResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 증강 파생영상 <b>폐기 표식(소프트 삭제) · 복구</b> — Phase 7.
 *
 * <h2>역할 경계</h2>
 * <ul>
 *   <li>표식({@link #markDiscarded}) — 반려 트랜잭션에 <b>참여</b>한다(별도 빈이라 프록시 유효).
 *       반려가 롤백되면 표식도 함께 사라져야 하므로 새 트랜잭션을 열지 않는다.</li>
 *   <li>복구({@link #restore}) — REVIEWER 전용 API. 폐기 표식을 닫고 <b>검수를 재오픈</b>한다.</li>
 *   <li>실삭제 집행은 {@code AugmentDiscardPurgeTxService} 가 담당한다(여기서 하지 않는다).</li>
 * </ul>
 *
 * <h2>★ 복구는 "표식 해제" 가 아니라 "반려를 되돌리는 것" (사용자 확정 설계)</h2>
 * <p>표식만 지우면 검수 행이 {@code REJECTED} 로 남아 등재 게이트
 * ({@code DerivativeWorkEligibility} = {@code EXISTS(RVW_STTS_CD='ACCEPTED')})가 계속 닫혀 있다 —
 * "복구했는데 여전히 안 보이는 반쪽 복구". 그래서 검수 행을 {@code PENDING} 으로 되돌려
 * <b>다시 채택/반려를 고를 수 있게</b> 한다({@link LsDataAugRvw#reopen}).
 *
 * <h3>이력 보존 방식 = "검수 행은 되돌리고, 되돌린 이력은 폐기 원장에 남긴다" (새 행 적층 금지)</h3>
 * <p>검수 이력을 <b>새 행</b>으로 쌓는 방식은 채택하지 않았다. 근거:
 * <ol>
 *   <li>{@code findLatestByDataAugSn} 은 "1 aug = 1 review row" 전제로 동작한다. 행이 늘면 결과 화면과
 *       결정 경로가 서로 다른 행을 볼 수 있다 — 실제로 그런 적이 있고(DEV_FIX MEDIUM ①), 지금은
 *       두 경로가 {@link LsDataAugRvw#RECENCY_ORDER} <b>단일 정의</b>를 공유해 같은 행을 본다.
 *       그 공유는 "행이 늘어도 안전하다" 는 뜻이 아니라 <b>두 판정이 어긋나지 않는다</b>는 뜻이다.</li>
 *   <li>더 치명적으로 <b>등재 게이트는 {@code EXISTS(ACCEPTED)}</b> 다. 과거 ACCEPTED 행이 한 건이라도
 *       남으면 이후 반려해도 게이트가 열린 채로 있다 — "사람은 폐기했는데 파생이 작업목록에 있다".</li>
 * </ol>
 * 그래서 되돌린 이력(누가·언제·왜)과 원래 반려 사유는 {@code LS_DATA_AUG_DSCD}(비석)에 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentDiscardService {

    private final LsDataAugDscdRepository discardRepository;
    private final LsDataAugRepository augRepository;
    private final LsDataAugRvwRepository reviewRepository;
    private final VideoRepository videoRepository;

    /**
     * 반려된 증강의 파생영상에 <b>폐기 표식</b>을 찍는다 — 반려 트랜잭션 안에서 호출한다.
     *
     * <h3>C2 — 원본 영상에는 표식을 찍을 수 없다</h3>
     * <p>배치의 최종 DELETE 가 {@code ORGNL_RAW_SN IS NOT NULL} 을 SQL 에 박고 있어도, 애초에 잘못된
     * 행이 큐에 들어가면 안 된다. 대상 RAW 를 읽어 {@link LsDataRaw#isDerivative()} 를 <b>재확인</b>하고
     * 아니면 거부한다. 다른 테이블을 참조하는 조건이라 DB {@code CHECK} 제약이 불가능하므로 이 앱
     * 가드가 유일한 입구 방어이며, 그래서 <b>성공·거부 양쪽 모두 {@code orgnlRawSn} 값을 로그에 남겨</b>
     * 사후 검출이 가능하게 한다(H10).
     *
     * <h3>확정 설계 3 — 매핑 없는 그랜드퍼더링 증강은 대상 제외</h3>
     * <p>{@code NEW_RAW_SN} 이 없으면 어느 파생을 지워야 하는지 알 수 없다. 시각 기반 역추정은
     * 중복 요청 허용 이후 <b>다른 요청의 파생본</b>을 지운다(V155 가 이미 폐기한 방법). 표식을 만들지
     * 않고 "수동 정리 필요" 만 남긴다. 표식이 없어도 <b>복구는 가능하다</b> — {@link #restore} 가
     * 검수 재오픈으로 폴백한다(FIX-7).
     *
     * <h3>{@code MANDATORY} — 반드시 반려 트랜잭션 <b>안에서만</b> 호출한다 (FIX-4)</h3>
     * <p>이 클래스는 {@code readOnly = true} 라, 트랜잭션 밖에서 호출되면 <b>읽기 전용 트랜잭션이 새로
     * 열려 표식 INSERT 가 조용히 유실</b>된다(예외도 없이 "반려는 됐는데 폐기 표식만 없는" 상태). 전파를
     * 명시해 그 오배선을 <b>기동/호출 즉시 실패</b>로 드러낸다 — 표식 유실은 무증상이라 사후에 발견되지
     * 않는다. 또한 반려가 롤백되면 표식도 함께 사라져야 하므로 새 트랜잭션을 열어서도 안 된다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.MANDATORY)
    public void markDiscarded(LsDataAug aug, String reason, String actorId) {
        Long newRawSn = aug.getNewRawSn();
        if (newRawSn == null) {
            log.warn("[Augment][Discard] 파생 매핑 없음 — 폐기 표식 생략(수동 정리 필요) dataAugSn={} augType={}",
                    aug.getDataAugSn(), LogSanitizer.sanitize(aug.getAugTypeCd()));
            return;
        }
        LsDataRaw derivative = videoRepository.findById(newRawSn).orElse(null);
        if (derivative == null) {
            log.warn("[Augment][Discard] 파생 영상 행 없음 — 폐기 표식 생략 dataAugSn={} newRawSn={}",
                    aug.getDataAugSn(), newRawSn);
            return;
        }
        if (!derivative.isDerivative()) {
            // 여기에 도달하면 데이터 정합이 깨진 것이다(원본 영상이 증강의 산출물로 연결됨).
            // 표식을 남기면 배치 큐에 원본이 들어가므로 즉시 거부하고 사후 검출용 감사 로그를 남긴다.
            log.error("[Augment][Discard] 폐기 거부 — 대상이 파생영상이 아님 dataAugSn={} rawSn={} orgnlRawSn={}",
                    aug.getDataAugSn(), newRawSn, derivative.getOrgnlRawSn());
            throw new CustomException(ErrorCode.CONFLICT, "원본 영상은 폐기 대상이 아닙니다.");
        }
        if (discardRepository.findByDataAugSnAndRstrDtIsNullAndDelDtIsNull(aug.getDataAugSn()).isPresent()) {
            log.info("[Augment][Discard] 이미 폐기 표식 존재 — 멱등 skip dataAugSn={}", aug.getDataAugSn());
            return;
        }
        // 증강 종류·생성 조건(프롬프트)을 함께 스냅샷한다 — LS_DATA_AUG 행은 실삭제로 사라지고,
        // 중복 증강 허용 이후 결과물을 구분하는 유일한 축이 PROMPT_CN 이다(구속 정책, FIX-8).
        discardRepository.save(LsDataAugDscd.mark(aug.getDataAugSn(), newRawSn,
                derivative.getOrgnlRawSn(), reason, actorId, LocalDateTime.now(),
                aug.getAugTypeCd(), aug.getPromptCn()));
        log.info("[Augment][Discard] 폐기 표식 기록 dataAugSn={} newRawSn={} orgnlRawSn={} augType={} actor={}",
                aug.getDataAugSn(), newRawSn, derivative.getOrgnlRawSn(),
                LogSanitizer.sanitize(aug.getAugTypeCd()), LogSanitizer.sanitize(actorId));
    }

    /**
     * 유예 내 <b>복구</b> — 폐기를 되돌리고 검수를 재오픈한다 (REVIEWER 전용).
     *
     * <p>잠금 순서는 다른 증강 경로와 동일하게 <b>증강 행 → 폐기 원장</b> 이다(데드락 방지). 실삭제
     * 클레임이 이미 잡혀 있어도 복구는 가능하다 — 최종 DELETE 가 표식 상태를 다시 평가하므로
     * 복구가 이기고 삭제는 0건에 그친다(H7).
     *
     * <h3>★ 표식이 없는 반려도 되돌릴 수 있다 (FIX-7)</h3>
     * <p>{@link #markDiscarded} 는 두 경우에 <b>표식 없이</b> 반환한다 — 매핑 없는 그랜드퍼더링
     * ({@code NEW_RAW_SN == null})과 파생 RAW 행 부재. 둘 다 <b>실삭제 대상이 아니라는 확정 정책</b>이라
     * 그대로 두지만, 표식이 없으면 이 메서드가 404 를 내 <b>그 반려만 영영 되돌릴 수 없었다</b>(검수 행은
     * REJECTED 로 굳고 재결정은 409). 실삭제 제외는 유지하되 <b>복구 경로만</b> 연다 — 표식이 없으면
     * 폐기 원장은 건드리지 않고 <b>검수 재오픈만</b> 수행한다(지울 표식이 없으니 되돌릴 표식도 없다).
     *
     * <h3>수용 조건은 두 경로가 <b>같다</b> — 화면 가시성({@code restoreEligible})의 정본</h3>
     * <p>진입 분기(표식 유무)만 다를 뿐, 마지막에 둘 다 검수 행을 {@code REJECTED → PENDING} 으로
     * 되돌린다. 따라서 <b>최신 검수 행이 {@code REJECTED}</b> 가 아니면 어느 경로로 와도 거부다
     * (여기는 409, {@link #restoreWithoutMark} 는 404 — 진입 시점에 관측한 사실이 달라서다).
     * {@code AugmentResultViewService#resolveRestoreEligible} 이 이 조건을 그대로 계산해 내려주며,
     * 그쪽에서 조건을 <b>재유도</b>하면(예: "열린 표식이 있으면 복구 가능") 누르면 반드시 409 인
     * 버튼이 다시 생긴다.
     *
     * @param reason 되돌린 이유(감사). 필수.
     */
    @Transactional("controlTransactionManager")
    public AugmentSummaryResponse restore(Long dataAugSn, String reason, TokenClaims actor) {
        requireReviewer(actor);
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "복구 사유는 필수입니다.");
        }
        LsDataAug aug = augRepository.findByDataAugSnForUpdate(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));

        LsDataAugDscd discard = discardRepository.findOpenForUpdate(dataAugSn).orElse(null);
        if (discard == null) {
            return restoreWithoutMark(aug, reason, actor);
        }

        LocalDateTime now = LocalDateTime.now();
        discard.restore(actor.sub(), reason, now);

        LsDataAugRvw review = reviewRepository.findLatestByDataAugSn(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.CONFLICT,
                        "복구할 검수 이력이 없습니다."));
        review.reopen(actor.sub(), now);

        log.info("[Augment][Discard] 폐기 복구(검수 재오픈) dataAugSn={} newRawSn={} orgnlRawSn={} "
                        + "actor={} reasonLen={}",
                dataAugSn, discard.getNewRawSn(), discard.getOrgnlRawSn(),
                LogSanitizer.sanitize(actor.sub()), reason.length());
        return AugmentSummaryResponse.from(aug, review);
    }

    /**
     * 표식이 없는 반려의 복구 — <b>검수 재오픈만</b> 수행한다 (FIX-7).
     *
     * <p>이미 실삭제된 건은 되돌릴 대상 자체가 없으므로 여기서도 거부한다(그 표식은 {@code DEL_DT} 로
     * 닫혀 있어 {@code findOpenForUpdate} 에 걸리지 않는다). 반려되지 않은 증강은 종전과 동일하게 404 다
     * — 되돌릴 결정이 없다.
     */
    private AugmentSummaryResponse restoreWithoutMark(LsDataAug aug, String reason, TokenClaims actor) {
        Long dataAugSn = aug.getDataAugSn();
        boolean purged = discardRepository.findFirstByDataAugSnOrderByDataAugDscdSnDesc(dataAugSn)
                .map(d -> d.getDelDt() != null)
                .orElse(false);
        if (purged) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "유예 기간이 지나 이미 삭제된 파생영상입니다. 필요하면 증강을 다시 요청해 주세요.");
        }
        LsDataAugRvw review = reviewRepository.findLatestByDataAugSn(dataAugSn)
                .filter(r -> LsDataAugRvw.STTS_REJECTED.equals(r.getRvwSttsCd()))
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "복구할 폐기 이력이 없습니다."));

        LocalDateTime now = LocalDateTime.now();
        review.reopen(actor.sub(), now);
        log.info("[Augment][Discard] 폐기 표식 없는 반려 복구(검수 재오픈만) dataAugSn={} newRawSn={} "
                        + "actor={} reasonLen={}",
                dataAugSn, aug.getNewRawSn(), LogSanitizer.sanitize(actor.sub()), reason.length());
        return AugmentSummaryResponse.from(aug, review);
    }

    private void requireReviewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // 「검수자 전용」은 「검수자 이상」이다 — 관리자가 계층으로 물려받아 미사용 파생 폐기·복구에 들어간다.
        // Spring 의 RoleHierarchy 는 권한(authority) 축에만 걸리므로 여기서 역할을 동등 비교하면
        // 관리자가 이 창구에서만 403 이 되어 계층이 반쪽만 성립한다.
        // [design: ADR-055] [design: ROLE-004] [design: AC-125]
        if (!actor.hasRole(Role.REVIEWER)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
    }
}
