package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.config.AugmentDiscardProperties;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 유예가 지난 파생영상 <b>실삭제 1건</b>의 트랜잭션 경계 — Phase 7.
 *
 * <p>스윕 루프({@link AugmentDiscardPurgeSweeper})는 트랜잭션 밖에서 돌고 건별로 이 빈을 호출한다 —
 * 자기호출이면 프록시가 적용되지 않아 {@code @Transactional} 이 통째로 무효가 되므로 <b>반드시 별도
 * 빈</b>이어야 한다(이 리포에 자기호출로 트랜잭션 경계가 유실된 실사고가 있다).
 *
 * <h2>왜 DB 를 먼저 지우고 파일을 나중에 지우는가 (H5)</h2>
 * <p>파일 I/O(NAS)와 DB 트랜잭션은 원자적으로 묶이지 않는다. 파일을 먼저 지우면 DB 커밋이 실패했을 때
 * <b>"행은 살아있는데 파일이 없다"</b>(작업목록에 뜨는데 열리지 않는 영상)가 된다. 반대 순서면 최악이
 * "지워진 파생의 파일이 남는다"(고아 파일)이고, 그마저도 비석({@code FILE_DEL_DT IS NULL})이 재시도
 * 대상으로 잡아준다. 그래서 <b>DB 커밋 → 파일 삭제</b> 순서이며, 커밋 <b>전에</b> 비디오 경로를 비석에
 * 기록한다(RAW 행이 사라지면 경로를 재구성할 단서가 없다).
 *
 * <h2>중단은 전체 롤백이다</h2>
 * <p>자식 테이블을 먼저 지우고 마지막에 RAW 를 지우므로, 최종 DELETE 가 0건(=클레임 이후 복구됨)이면
 * 예외로 트랜잭션을 되돌린다. 그래야 "라벨만 지워진 살아있는 파생" 이 생기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentDiscardPurgeTxService {

    /**
     * ★ 삭제 순서 고정 (H4) — FK 가 없는 테이블이 섞여 있어 순서를 틀리면 <b>조용히 고아</b>가 남거나
     * FK 위반으로 전량 실패한다. 아래 순서는 코드 상수이자 문서다.
     */
    public static final List<String> DELETE_ORDER = List.of(
            "LS_DATA_LBL_ATTR_VAL",   // ① FK → LS_DATA_LBL (반드시 라벨보다 먼저)
            "LS_DATA_AUG_LBL_MAP",    // ② FK 없음
            "LS_DATA_LBL_HSTRY",      // ③ FK 없음(V58 의도)
            "LS_DATA_LBL",            // ④ FK 없음(SRC_SN 에 FK 미설정)
            "LS_DATA_SRC_HSTRY",      // ⑤ FK 없음 + RAW_SN 컬럼도 없어 V146 CASCADE 대상 밖(FIX-2)
            "LS_DATA_AUG_RVW",        // ⑥ FK 는 원본 RAW 를 가리켜 파생 삭제로 정리되지 않는다
            "LS_DATA_AUG",            // ⑦ 위탁 job/job file 은 FK CASCADE 로 동반 삭제
            "LS_ISSUE_COMMENT",       // ⑧ FK(V10)가 ON DELETE RESTRICT 라 CASCADE 를 타고 내려오지
                                      //    않는다 — 부모 이슈(LS_DATA_ISSUE)는 ⑩의 RAW CASCADE 로
                                      //    사라지므로, 먼저 지우지 않으면 ⑩이 FK 위반으로 실패해
                                      //    스윕 전체가 롤백된다(V10 이전엔 조용한 고아였다)
            "LS_EVNT_ANNO_REVIEW",    // ⑨ ⑧과 같은 실패 클래스 — FK(fk_ls_evnt_anno_review_anno,
                                      //    V1 baseline)에 ON DELETE 절이 없어 기본값 NO ACTION 이라
                                      //    CASCADE 를 타고 내려오지 않는다. 부모 어노테이션
                                      //    (LS_EVNT_ANNO)은 ⑩의 RAW CASCADE 로 사라지므로 먼저 지우지
                                      //    않으면 ⑩이 FK 위반으로 실패해 스윕 전체가 롤백된다.
                                      //    ⚠ 부모 LS_EVNT_ANNO 는 이 목록에 넣지 않는다 — 검토 행이
                                      //    사라지면 ⑩의 CASCADE 가 정리하므로 중복이다
            "LS_DATA_RAW"             // ⑩ V146 FK CASCADE 가 자식 전량 정리(FK 를 건 신규 자식도 자동
                                      //    편입 — 예: LS_LABEL_VERSION·LS_DATASET_EXPORT·
                                      //    LS_OUTPUT_VER_SNPSH(V183) 는 그래서 이 목록에 없다.
                                      //    ⚠ RESTRICT 로 건 FK 는 편입되지 않아 ⑧처럼 명시 대상이다)
    );

    private final LsDataAugDscdRepository discardRepository;
    private final VideoRepository videoRepository;
    private final AugmentDiscardProperties properties;

    /** 실삭제 집행 결과. */
    public enum Result {
        /** DB 행 삭제 완료 — 호출측이 파일 삭제를 이어서 수행한다. */
        PURGED,
        /** 대상 아님(이미 닫힌 표식·클레임 미보유 등). */
        SKIPPED,
        /** 방어 조건 위반으로 거부(원본·승인분 등) — 사람이 봐야 한다. */
        REFUSED
    }

    /**
     * @param result    집행 결과
     * @param rawSn     파생 RAW_SN (파일 삭제용)
     * @param videoPath 삭제 직전 기록한 파생 비디오 경로(nullable)
     */
    public record Outcome(Result result, Long rawSn, String videoPath) {
        public static Outcome skipped() {
            return new Outcome(Result.SKIPPED, null, null);
        }
    }

    /**
     * <b>원자 클레임</b> — 조건부 UPDATE 1문장으로 집행 자격을 얻는다 (H7).
     *
     * <p>클레임을 별도 트랜잭션으로 커밋하는 이유: 집행 트랜잭션과 한 덩어리로 묶으면 클레임 행에
     * 잠금이 걸린 채라 <b>복구 요청이 대기하다 삭제 이후에 실패</b>한다. 유예 내 복구가 항상 이기려면
     * 클레임과 집행 사이에 복구가 끼어들 수 있어야 하고, 최종 DELETE 가 그 결과를 다시 평가한다.
     *
     * <p><b>cutoff 는 진입부에서 클램프된다</b>({@link AugmentDiscardProperties#clampCutoff}) — 유예를
     * 좁히는 값을 넘겨도 하드 컷오프({@code now - graceDays})로 되돌아간다(FIX-1).
     *
     * @return true = 이번 호출이 클레임을 얻음
     */
    @Transactional("controlTransactionManager")
    public boolean claim(Long dscdSn, LocalDateTime cutoff, LocalDateTime claimStaleCutoff) {
        LocalDateTime safeCutoff = properties.clampCutoff(cutoff);
        return discardRepository.claimForPurge(dscdSn, safeCutoff, claimStaleCutoff, LocalDateTime.now()) == 1;
    }

    /** 집행이 중단된 클레임 반환 — 다음 tick 이 재시도한다. */
    @Transactional("controlTransactionManager")
    public void releaseClaim(Long dscdSn) {
        discardRepository.releaseClaim(dscdSn);
    }

    /**
     * DB 행 실삭제 1건. 클레임을 얻은 뒤에만 호출한다.
     *
     * @param cutoff 유예 경과 기준 시각(= now - graceDays). <b>진입부에서 클램프</b>되어 유예를 좁히는
     *               방향의 값은 하드 컷오프로 되돌아간다(FIX-1). 최종 DELETE 조건에도 그대로 들어간다.
     * @throws DiscardPurgeAbortedException 최종 DELETE 0건(클레임 이후 복구됨) 또는 불변식 위반 — 전체 롤백
     */
    @Transactional("controlTransactionManager")
    public Outcome purge(Long dscdSn, LocalDateTime requestedCutoff) {
        LocalDateTime cutoff = properties.clampCutoff(requestedCutoff);
        LsDataAugDscd discard = discardRepository.findById(dscdSn).orElse(null);
        if (discard == null || !discard.isClaimed()) {
            return Outcome.skipped();
        }
        Long rawSn = discard.getNewRawSn();
        Long dataAugSn = discard.getDataAugSn();

        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        if (raw == null) {
            return purgeWithoutRaw(discard, dscdSn, rawSn, dataAugSn);
        }

        if (!isPurgeAllowed(raw, dscdSn, rawSn)) {
            return new Outcome(Result.REFUSED, rawSn, null);
        }

        // H5 — 커밋 전에 파일 경로를 비석에 남긴다(RAW 가 사라지면 재구성 단서가 없다).
        discard.recordVideoPath(raw.getRawFilePathNm());

        String childCounts = deleteChildren(dscdSn, rawSn, dataAugSn);

        int rawDeleted = discardRepository.deleteDiscardedDerivativeRaw(rawSn, dscdSn, cutoff);
        if (rawDeleted == 0) {
            // 클레임 이후 복구되어 표식이 닫혔거나(정상), 승인·원본·유예 조건에 걸렸다(비정상).
            // 앞 단계 삭제까지 되돌려야 "라벨만 사라진 살아있는 파생" 이 생기지 않는다.
            throw DiscardPurgeAbortedException.raceLost(dscdSn, rawSn);
        }

        discard.markDbDeleted(null, LocalDateTime.now());
        log.warn("[Augment][Discard][Purge] 파생영상 DB 삭제 dscdSn={} rawSn={} orgnlRawSn={} dataAugSn={} {}",
                dscdSn, rawSn, discard.getOrgnlRawSn(), dataAugSn, childCounts);
        return new Outcome(Result.PURGED, rawSn, discard.getVdoFilePath());
    }

    /**
     * 파생 RAW 가 이미 없는 경우의 집행 (FIX-E).
     *
     * <h3>왜 "지울 것이 없다" 로 넘기면 안 되는가</h3>
     * <p>구 구현은 RAW 부재를 <b>DB 는 더 지울 것이 없다</b>로 읽고 자식 삭제를 통째로 건너뛴 뒤 비석을
     * 닫았다. 그러나 {@code LS_DATA_AUG} 를 비롯한 자식들은 <b>RAW 에 FK 가 없어 함께 사라지지 않는다</b> —
     * {@link #deleteChildren} 이 "증강 행 0건 삭제" 를 <b>불변식 위반(전체 롤백)</b>으로 규정한 바로 그
     * 고아를, 이 분기는 정상 종결로 처리하고 있었다(두 분기의 판정이 정반대).
     *
     * <p>그래서 여기서도 <b>자식 정리를 수행</b>한다. 다만 불변식({@code augments > 0})은 <b>강제하지
     * 않는다</b> — RAW 가 이미 없는 상태는 "삭제를 원자적으로 성립시키는" 국면이 아니라 <b>남은 고아를
     * 걷어내는</b> 국면이고, 증강 행까지 이미 정리된 정상적인 반복 실행에서 0건이 나오는 것이 당연하기
     * 때문이다(0건을 위반으로 보면 그 비석은 영원히 닫히지 않는다).
     *
     * <p>승인 방어({@code NOT EXISTS(APPROVED)})는 RAW 없이도 평가 가능하므로 <b>그대로 유지</b>한다 —
     * 관제 접근 보장은 구속 정책이고, 이 국면에서만 느슨해질 이유가 없다.
     */
    private Outcome purgeWithoutRaw(LsDataAugDscd discard, Long dscdSn, Long rawSn, Long dataAugSn) {
        if (discardRepository.countApprovedStatus(rawSn) > 0) { // H8 — 관제 접근 보장(구속 정책)
            log.error("[Augment][Discard][Purge] 삭제 거부 — RAW 는 없는데 검수 승인 상태가 남아 있다 "
                    + "dscdSn={} rawSn={}", dscdSn, rawSn);
            return new Outcome(Result.REFUSED, rawSn, null);
        }
        String childCounts = deleteChildren(dscdSn, rawSn, dataAugSn, false);
        discard.markDbDeleted(null, LocalDateTime.now());
        log.warn("[Augment][Discard][Purge] 파생 RAW 부재 — 잔여 자식만 정리 dscdSn={} rawSn={} {}",
                dscdSn, rawSn, childCounts);
        return new Outcome(Result.PURGED, rawSn, discard.getVdoFilePath());
    }

    /**
     * 최종 SQL 과 <b>같은 축</b>의 추가 방어층 — 위반이면 ERROR 로 드러내고 집행하지 않는다.
     * (SQL 조건이 유일한 방어선이 아니어야 하고, SQL 이 0건으로 조용히 넘어가는 것과 달리 여기서는
     * 사람이 볼 수 있는 신호가 남아야 한다.)
     */
    private boolean isPurgeAllowed(LsDataRaw raw, Long dscdSn, Long rawSn) {
        if (!raw.isDerivative()) { // C1
            log.error("[Augment][Discard][Purge] 삭제 거부 — 대상이 파생영상이 아님 dscdSn={} rawSn={} "
                    + "orgnlRawSn={}", dscdSn, rawSn, raw.getOrgnlRawSn());
            return false;
        }
        if (discardRepository.countApprovedStatus(rawSn) > 0) { // H8 — 관제 접근 보장(구속 정책)
            log.error("[Augment][Discard][Purge] 삭제 거부 — 검수 승인된 파생 dscdSn={} rawSn={}",
                    dscdSn, rawSn);
            return false;
        }
        return true;
    }

    /**
     * 자식 행 삭제 — {@link #DELETE_ORDER} 순서 그대로. 단계별 건수를 로그 문자열로 돌려준다
     * (무엇이 몇 건 지워졌는지 남지 않으면 사후에 고아 여부를 판별할 수 없다).
     */
    private String deleteChildren(Long dscdSn, Long rawSn, Long dataAugSn) {
        return deleteChildren(dscdSn, rawSn, dataAugSn, true);
    }

    /**
     * @param enforceAugmentInvariant 증강 행 0건 삭제를 불변식 위반으로 볼 것인가. RAW 를 함께 지우는
     *                                정상 집행에서는 {@code true}(고아 방지), RAW 가 이미 없어 잔여
     *                                자식만 걷어내는 국면에서는 {@code false}(0건이 정상, FIX-E).
     */
    private String deleteChildren(Long dscdSn, Long rawSn, Long dataAugSn,
                                  boolean enforceAugmentInvariant) {
        int attrValues = discardRepository.deleteDerivativeLabelAttrValues(rawSn);
        int labelMaps = discardRepository.deleteDerivativeLabelMaps(rawSn, dataAugSn);
        int labelHistory = discardRepository.deleteDerivativeLabelHistory(rawSn);
        int labels = discardRepository.deleteDerivativeLabels(rawSn);
        int frameHistory = discardRepository.deleteDerivativeFrameHistory(rawSn);
        int reviews = discardRepository.deleteDerivativeReviews(dataAugSn);
        int augments = discardRepository.deleteDerivativeAugment(dataAugSn, rawSn);
        // ⑧ 이슈 댓글 — RESTRICT FK(V10)라 RAW CASCADE 로 정리되지 않는다. 반드시 ⑩보다 먼저.
        int issueComments = discardRepository.deleteDerivativeIssueComments(rawSn);
        // ⑨ 이벤트 어노테이션 검토 행 — FK 에 ON DELETE 절이 없어(NO ACTION) 역시 CASCADE 밖이다.
        int eventAnnoReviews = discardRepository.deleteDerivativeEventAnnotationReviews(rawSn);
        if (augments == 0 && enforceAugmentInvariant) {
            // 불변식 위반 (FIX-6) — ⑦(DATA_AUG_SN + NEW_RAW_SN)과 ⑩(rawSn 만)의 조건이 어긋났다.
            // 그대로 두면 RAW 는 지워지고 그 RAW 를 가리키는 증강 행만 (FK 가 없어) 조용히 남는다.
            log.error("[Augment][Discard][Purge] 삭제 중단 — 증강 행 0건 삭제(불변식 위반) "
                    + "dscdSn={} rawSn={} dataAugSn={}", dscdSn, rawSn, dataAugSn);
            throw DiscardPurgeAbortedException.invariantViolated(dscdSn, rawSn);
        }
        return "attrValues=" + attrValues + " labelMaps=" + labelMaps + " labelHistory=" + labelHistory
                + " labels=" + labels + " frameHistory=" + frameHistory
                + " reviews=" + reviews + " augments=" + augments
                + " issueComments=" + issueComments + " eventAnnoReviews=" + eventAnnoReviews;
    }

    /** 파일 정리 완료 표시 — 부분 실패면 호출하지 않아 다음 tick 이 재시도한다. */
    @Transactional("controlTransactionManager")
    public void markFilesDeleted(Long dscdSn) {
        discardRepository.findById(dscdSn).ifPresent(d -> d.markFilesDeleted(LocalDateTime.now()));
    }

    /**
     * 파일 정리 <b>시도 실패</b> 기록 — 재시도 횟수를 올리고, 상한을 넘으면 데드레터로 종결한다 (FIX-3).
     *
     * <p>상한이 없으면 "재시도해도 결과가 같은" 비석이 오래된 순 배치의 앞자리를 영구 점유해 이후 비석의
     * 파일 정리를 전면 정지시킨다. 종결은 <b>삭제 실패의 은폐가 아니라 자동 재시도의 중단</b>이며,
     * 사유·횟수가 비석과 WARN 로그 양쪽에 남아 사람이 수동 정리할 수 있다.
     *
     * <p><b>종결분 재개 절차는 V157 주석에 있다</b> — {@code FILE_DEL_FAIL_DT} 만 지우고
     * {@code FILE_DEL_RTRY_NMTM} 을 0 으로 되돌리지 않으면 아래 {@code attempts >= max} 가 즉시 참이라
     * <b>첫 재시도 실패에 곧바로 재종결</b>된다.
     *
     * @param unresolvable true = 재시도해도 동일한 구조적 잔존(심링크 등) → 상한 이전이라도 즉시 종결
     * @return true = 이번 호출로 종결(데드레터)됨
     */
    @Transactional("controlTransactionManager")
    public boolean recordFileCleanupFailure(Long dscdSn, boolean unresolvable, String reason) {
        LsDataAugDscd discard = discardRepository.findById(dscdSn).orElse(null);
        if (discard == null || discard.isFileCleanupAbandoned()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int attempts = discard.recordFileCleanupAttempt(now);
        boolean exhausted = attempts >= properties.fileCleanupMaxAttempts();
        if (!unresolvable && !exhausted) {
            return false;
        }
        String detail = (unresolvable ? "자동 정리 불가(심링크·비정규 항목 등 우리가 지우지 않는 항목 잔존)"
                : "재시도 상한 초과") + " attempts=" + attempts;
        discard.markFileCleanupAbandoned(detail, now);
        log.warn("[Augment][Discard] 파일 정리 포기(사람 수동 정리 필요) dscdSn={} rawSn={} attempts={} "
                        + "maxAttempts={} unresolvable={}",
                dscdSn, discard.getNewRawSn(), attempts, properties.fileCleanupMaxAttempts(), unresolvable);
        return true;
    }

    /** 파일 정리 재시도용 비석 스냅샷. */
    public record FileCleanupRef(Long dscdSn, Long rawSn, String videoPath) { }

    /** 파일 정리가 남은 비석 1건을 읽는다(트랜잭션 밖 파일 I/O 를 위해 값만 뽑는다). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public FileCleanupRef loadCleanupRef(Long dscdSn) {
        return discardRepository.findById(dscdSn)
                .map(d -> new FileCleanupRef(d.getDataAugDscdSn(), d.getNewRawSn(), d.getVdoFilePath()))
                .orElse(null);
    }

    /**
     * 집행을 되돌렸음을 알리는 신호. 두 갈래이며 <b>운영 대응이 다르다</b>:
     * <ul>
     *   <li>{@link #raceLost} — 최종 DELETE 0건(복구가 이겼다). <b>정상 흐름</b>, 사람 개입 불요.</li>
     *   <li>{@link #invariantViolated} — 삭제 조건들이 서로 어긋났다(FIX-6). <b>사람이 봐야 한다.</b></li>
     * </ul>
     */
    public static class DiscardPurgeAbortedException extends RuntimeException {

        private final boolean invariantViolation;

        private DiscardPurgeAbortedException(String detail, Long dscdSn, Long rawSn,
                                             boolean invariantViolation) {
            super("파생영상 실삭제가 중단됐습니다 (" + detail + "). dscdSn="
                    + LogSanitizer.sanitize(String.valueOf(dscdSn))
                    + " rawSn=" + LogSanitizer.sanitize(String.valueOf(rawSn)));
            this.invariantViolation = invariantViolation;
        }

        /** 클레임 이후 복구/방어조건으로 최종 DELETE 가 0건 — 정상 흐름. */
        public static DiscardPurgeAbortedException raceLost(Long dscdSn, Long rawSn) {
            return new DiscardPurgeAbortedException("복구 또는 방어조건", dscdSn, rawSn, false);
        }

        /** 삭제 대상 조건 드리프트 — 그대로 두면 고아가 남는다. */
        public static DiscardPurgeAbortedException invariantViolated(Long dscdSn, Long rawSn) {
            return new DiscardPurgeAbortedException("불변식 위반 — 증강 행 0건 삭제", dscdSn, rawSn, true);
        }

        /** 사람이 확인해야 하는 중단인가. */
        public boolean isInvariantViolation() {
            return invariantViolation;
        }
    }
}
