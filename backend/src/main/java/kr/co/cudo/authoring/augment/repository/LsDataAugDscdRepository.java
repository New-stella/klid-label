package kr.co.cudo.authoring.augment.repository;

import jakarta.persistence.LockModeType;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 증강 파생영상 폐기 원장 + <b>실삭제 집행 SQL</b> (Phase 7).
 *
 * <h2>왜 삭제 SQL 이 전부 여기 모여 있는가</h2>
 * <p>파생 1건을 지우려면 <b>FK 가 없는</b> 테이블 3개(라벨 속성값·라벨맵·라벨)를 손으로 지운 뒤
 * RAW 를 지워야 한다(DB 가 막아주지 않아 <b>조용히 고아</b>가 남는다). 삭제 순서와 조건이 흩어지면
 * 한 곳만 갱신돼 고아가 부활하므로, 순서를 코드 상수({@code AugmentDiscardPurgeTxService.DELETE_ORDER})로
 * 고정하고 문장 자체를 한 파일에 모은다.
 *
 * <h2>모든 삭제는 "개별 rawSn 단위 조건부 DELETE" 다 (H3)</h2>
 * <p>{@code deleteAllById}/{@code deleteAllInBatch} 같은 일괄 API 는 <b>쓰지 않는다</b> — 개별 WHERE 를
 * 걸 수 없어 클레임 이후 복구된 행까지 함께 지운다. 후보를 순회하며 건별로 조건부 DELETE 한다.
 */
@ControlRepo
public interface LsDataAugDscdRepository extends JpaRepository<LsDataAugDscd, Long> {

    /** 이 증강에 <b>열린</b>(복구·삭제되지 않은) 폐기 표식. 부분 유니크(V156)로 최대 1건이다. */
    Optional<LsDataAugDscd> findByDataAugSnAndRstrDtIsNullAndDelDtIsNull(Long dataAugSn);

    /**
     * 복구 처리용 — 열린 표식을 행 잠금으로 읽는다.
     *
     * <p>복구와 실삭제 클레임이 동시에 들어오면 read-then-act 가 갈라지므로 잠근다. 잠금 순서는
     * 다른 증강 경로와 동일하게 <b>증강 행({@code LS_DATA_AUG}) → 폐기 원장</b> 이다(데드락 방지).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM LsDataAugDscd d WHERE d.dataAugSn = :dataAugSn "
            + "AND d.rstrDt IS NULL AND d.delDt IS NULL")
    Optional<LsDataAugDscd> findOpenForUpdate(@Param("dataAugSn") Long dataAugSn);

    /** 그 증강의 최신 폐기 이력 1건(복구/삭제 여부 무관) — 안내 메시지 분기용. */
    Optional<LsDataAugDscd> findFirstByDataAugSnOrderByDataAugDscdSnDesc(Long dataAugSn);

    /**
     * 여러 증강의 폐기 이력을 <b>배치 1회</b>로 읽는다 — 결과 조회 화면의 폐기 축 구성용.
     *
     * <p>항목마다 {@link #findFirstByDataAugSnOrderByDataAugDscdSnDesc} 를 부르면 N+1 이 된다
     * (결과 화면은 한 영상에 항목이 최대 {@code itemSize}=100 건까지 실린다). 호출부는 정렬된 결과를
     * 훑어 {@code dataAugSn} 별 <b>첫 행</b>(= 최신)만 취한다.
     *
     * <p><b>정렬을 리포지토리에서 확정</b>하는 이유: "최신 1행" 선택이 결정론적이어야 한다. 반려마다 새
     * 행이 쌓이므로({@code mark}), 순서가 흔들리면 <b>이미 복구된 옛 표식</b>을 최신으로 잘못 골라
     * 멀쩡한 항목을 "곧 삭제됨" 으로 표시한다. PK 는 IDENTITY 라 삽입 순서와 단조 일치한다.
     *
     * <p>파생 쿼리(파라미터 바인딩)라 문자열 연결이 없다(CWE-89).
     */
    List<LsDataAugDscd> findByDataAugSnInOrderByDataAugDscdSnDesc(Collection<Long> dataAugSns);

    /**
     * 실삭제 후보 — 유예가 지난 <b>열린</b> 표식.
     *
     * <p>{@code NEW_RAW_SN IS NOT NULL} 은 컬럼 제약과 <b>중복</b>이지만 명시한다: 매핑 없는
     * 그랜드퍼더링 증강을 시각 기반으로 역추정해 지우는 일이 절대 없어야 한다(V155 가 이미 폐기한
     * 방법 — 같은 영상×종류 재요청이 허용된 뒤로는 <b>다른 요청의 파생본</b>을 지운다).
     *
     * <p>{@code DEL_PRCS_DT} 는 "미클레임 또는 오래된 클레임" 만 후보로 삼는다 — 클레임 직후 프로세스가
     * 죽으면 그 표식이 영원히 집행되지 않기 때문이다(스트랜드 클레임 회수).
     */
    @Query(value = """
            SELECT d.DATA_AUG_DSCD_SN
              FROM LS_DATA_AUG_DSCD d
             WHERE d.RSTR_DT IS NULL
               AND d.DEL_DT IS NULL
               AND d.NEW_RAW_SN IS NOT NULL
               AND d.DSCD_DT <= :cutoff
               AND (d.DEL_PRCS_DT IS NULL OR d.DEL_PRCS_DT <= :claimStaleCutoff)
             ORDER BY d.DSCD_DT
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findPurgeCandidates(@Param("cutoff") LocalDateTime cutoff,
                                   @Param("claimStaleCutoff") LocalDateTime claimStaleCutoff,
                                   @Param("limit") int limit);

    /**
     * <b>원자 클레임</b> (H7) — 조건부 UPDATE 1문장. 갱신 1건을 얻은 노드만 집행한다.
     *
     * <p>Quartz 클러스터링은 트리거 중복 발화만 막고 잡 내부 레이스는 막지 않으므로, 집행 자격은
     * 이 UPDATE 로만 획득한다. 유예·복구·삭제 조건을 여기서도 다시 평가해 후보 조회 이후 상태가
     * 바뀐 행을 배제한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_AUG_DSCD
               SET DEL_PRCS_DT = :now
             WHERE DATA_AUG_DSCD_SN = :dscdSn
               AND RSTR_DT IS NULL
               AND DEL_DT IS NULL
               AND NEW_RAW_SN IS NOT NULL
               AND DSCD_DT <= :cutoff
               AND (DEL_PRCS_DT IS NULL OR DEL_PRCS_DT <= :claimStaleCutoff)
            """, nativeQuery = true)
    int claimForPurge(@Param("dscdSn") Long dscdSn,
                      @Param("cutoff") LocalDateTime cutoff,
                      @Param("claimStaleCutoff") LocalDateTime claimStaleCutoff,
                      @Param("now") LocalDateTime now);

    /**
     * 집행이 중단된 클레임을 되돌린다 — 다음 tick 이 즉시 재시도할 수 있게 한다.
     * 이미 삭제된 행은 건드리지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_AUG_DSCD
               SET DEL_PRCS_DT = NULL
             WHERE DATA_AUG_DSCD_SN = :dscdSn
               AND DEL_DT IS NULL
            """, nativeQuery = true)
    int releaseClaim(@Param("dscdSn") Long dscdSn);

    /**
     * 파일 정리가 남은 비석 — DB 는 지웠는데 파일 삭제가 실패/미완인 행(재시도 축).
     *
     * <p><b>{@code FILE_DEL_FAIL_DT IS NULL} 조건이 핵심이다 (V157 · FIX-3)</b>: 파생 프레임 트리에
     * 우리가 <b>의도적으로 지우지 않는</b> 항목(심링크·비정규 파일)이 있으면 정리는 재시도해도 영원히
     * 완료되지 않는다. 이 큐는 {@code DEL_DT} 오름차순이라 그런 비석이 <b>항상 앞자리를 점유</b>하고,
     * batch-size 만큼 쌓이면 이후 생성되는 모든 비석의 파일 정리가 전면 정지한다(head-of-line
     * blocking). 그래서 상한 초과·수렴 불가로 종결된 비석은 큐에서 뺀다(사람이 수동 정리).
     */
    @Query(value = """
            SELECT d.DATA_AUG_DSCD_SN
              FROM LS_DATA_AUG_DSCD d
             WHERE d.DEL_DT IS NOT NULL
               AND d.FILE_DEL_DT IS NULL
               AND d.FILE_DEL_FAIL_DT IS NULL
             ORDER BY d.DEL_DT
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findFileCleanupPending(@Param("limit") int limit);

    // ────────────────────────────────────────────────────────────────────────
    // 실삭제 집행 SQL — 순서는 AugmentDiscardPurgeTxService.DELETE_ORDER 가 고정한다.
    // ────────────────────────────────────────────────────────────────────────

    /**
     * ① 라벨 <b>속성값</b>({@code LS_DATA_LBL_ATTR_VAL}) — {@code LS_DATA_LBL} 로 FK 가 걸려 있어
     * <b>반드시 라벨보다 먼저</b> 지워야 한다(아니면 FK 위반으로 삭제 전체가 실패).
     *
     * <p>⚠ 지우는 것은 <b>라벨 속성값</b>이지 라벨 마스터의 속성 <b>정의</b>({@code LS_LABEL_ATTR},
     * FK → {@code LS_LABEL})가 아니다. 이름이 비슷하지만 후자를 지우면 전체 프로젝트의 라벨 속성
     * 정의가 사라진다 — 절대 대상 아님.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_DATA_LBL_ATTR_VAL
             WHERE LBL_SN IN (
                    SELECT l.LBL_SN FROM LS_DATA_LBL l
                     WHERE l.SRC_SN IN (SELECT s.SRC_SN FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn))
            """, nativeQuery = true)
    int deleteDerivativeLabelAttrValues(@Param("rawSn") Long rawSn);

    /**
     * ② 증강 라벨 매핑({@code LS_DATA_AUG_LBL_MAP}) — FK 가 없어 CASCADE 로 정리되지 않는다.
     * 이 파생을 만든 증강 행 기준 + 파생 라벨 기준 <b>양쪽</b>으로 지운다(둘 다 이 파생 스코프).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_DATA_AUG_LBL_MAP
             WHERE DATA_AUG_SN = :dataAugSn
                OR DATA_LBL_SN IN (
                    SELECT l.LBL_SN FROM LS_DATA_LBL l
                     WHERE l.SRC_SN IN (SELECT s.SRC_SN FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn))
            """, nativeQuery = true)
    int deleteDerivativeLabelMaps(@Param("rawSn") Long rawSn, @Param("dataAugSn") Long dataAugSn);

    /**
     * ③ 라벨 이력({@code LS_DATA_LBL_HSTRY}) — {@code SRC_SN} 에 FK 가 없어(V58 의도) 프레임이
     * CASCADE 로 사라져도 남는다. 파생이 통째로 사라지는 마당에 그 프레임 이력만 남기면 고아다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_DATA_LBL_HSTRY
             WHERE SRC_SN IN (SELECT s.SRC_SN FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
            """, nativeQuery = true)
    int deleteDerivativeLabelHistory(@Param("rawSn") Long rawSn);

    /**
     * ④ 라벨({@code LS_DATA_LBL}) — {@code SRC_SN} 에 FK 가 <b>없어</b> 프레임이 CASCADE 로 지워져도
     * 조용히 남는다(FK 위반으로 시끄럽게 실패하지 않는 쪽이 더 위험하다).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_DATA_LBL
             WHERE SRC_SN IN (SELECT s.SRC_SN FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
            """, nativeQuery = true)
    int deleteDerivativeLabels(@Param("rawSn") Long rawSn);

    /**
     * ⑤ 프레임 생성/변경 이력({@code LS_DATA_SRC_HSTRY}) — {@code SRC_SN} 에 <b>FK 가 없고</b>(V4)
     * {@code RAW_SN} 컬럼도 없어 V146 의 RAW CASCADE 대상에도 들어가지 않는다. 즉 <b>어떤 자동 경로로도
     * 정리되지 않는다</b>.
     *
     * <p>증강 파생은 프레임 1장마다 이 이력을 1행 남기므로({@code AugmentExtractPersist} 의
     * {@code LsDataSrcHstry.recordCreated}), 이 문장이 없으면 파생을 실삭제한 뒤 <b>존재하지 않는
     * SRC_SN 을 가리키는 이력 N건</b>이 조용히 남는다 — 이 파일이 첫 줄부터 경고하는 바로 그 실패 클래스다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_DATA_SRC_HSTRY
             WHERE SRC_SN IN (SELECT s.SRC_SN FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
            """, nativeQuery = true)
    int deleteDerivativeFrameHistory(@Param("rawSn") Long rawSn);

    /**
     * ⑥ 검수 행({@code LS_DATA_AUG_RVW}) — 이 행의 {@code DATA_RAW_SN} 은 <b>원본</b> 영상을 가리켜
     * (FK CASCADE) 파생 RAW 삭제로는 정리되지 않는다. ⑦에서 증강 행이 사라지면 참조가 붕 뜨므로
     * 함께 지운다. 결정 사실(사유·시각·결정자)은 폐기 원장 비석에 스냅샷으로 남아 감사가 끊기지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = :dataAugSn", nativeQuery = true)
    int deleteDerivativeReviews(@Param("dataAugSn") Long dataAugSn);

    /**
     * ⑦ 증강 요청 행({@code LS_DATA_AUG}) — 위탁 job/job file 은 FK CASCADE(V140/V141)로 함께 사라진다.
     * {@code NEW_RAW_SN} 일치를 조건에 포함해 <b>이 파생을 만든 그 요청</b>만 지운다.
     *
     * <p><b>0건은 불변식 위반이다 (FIX-6)</b>: 이 조건({@code DATA_AUG_SN} + {@code NEW_RAW_SN} 동시
     * 일치)과 ⑩의 RAW DELETE({@code dataAugSn} 무관)가 드리프트로 어긋나면 <b>증강 행은 0건 삭제인데
     * RAW 삭제는 성공</b>해 삭제된 RAW 를 가리키는 증강 행이 (FK 가 없어) 조용히 잔존한다. 호출측은
     * 0건을 중단(전체 롤백)으로 처리한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_DATA_AUG
             WHERE DATA_AUG_SN = :dataAugSn
               AND NEW_RAW_SN = :rawSn
            """, nativeQuery = true)
    int deleteDerivativeAugment(@Param("dataAugSn") Long dataAugSn, @Param("rawSn") Long rawSn);

    /**
     * ⑧ 이슈 <b>댓글</b>({@code LS_ISSUE_COMMENT}) — <b>부모 이슈보다 먼저</b> 지워야 한다.
     *
     * <h3>왜 이 문장이 필요한가 (V10)</h3>
     * <p>이 테이블의 부모는 {@code LS_DATA_ISSUE} 이고, 그 이슈는 {@code fk_ls_data_issue_raw}
     * (ON DELETE CASCADE)로 <b>⑩의 RAW 삭제와 함께 사라진다</b>. 그런데 댓글의 FK 는 설계
     * (ERD-023)가 {@code ON DELETE RESTRICT} 로 규정하므로 <b>CASCADE 를 타고 내려오지 않는다</b> —
     * 손으로 먼저 지우지 않으면 ⑩이 FK 위반으로 실패해 <b>폐기 스윕 전체가 롤백</b>된다.
     * (V10 이전에는 FK 자체가 없어 실패 대신 <b>조용한 고아</b>가 남았다 — 이 파일이 첫 줄부터
     * 경고하는 바로 그 실패 클래스다.)
     *
     * <p>범위는 <b>그 영상에 매달린 이슈의 댓글만</b>이다({@code DATA_ISSUE_SN} 서브쿼리). 댓글 행에는
     * {@code RAW_SN} 컬럼이 없어 영상으로 직접 좁힐 수 없으므로 이슈를 경유한다. 파라미터 바인딩만
     * 쓰며 문자열 연결이 없다(CWE-89).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_ISSUE_COMMENT
             WHERE DATA_ISSUE_SN IN (
                    SELECT i.DATA_ISSUE_SN FROM LS_DATA_ISSUE i WHERE i.DATA_RAW_SN = :rawSn)
            """, nativeQuery = true)
    int deleteDerivativeIssueComments(@Param("rawSn") Long rawSn);

    /**
     * ⑨ 이벤트 어노테이션 <b>검토 행</b>({@code LS_EVNT_ANNO_REVIEW}) — <b>부모 어노테이션보다 먼저</b>
     * 지워야 한다.
     *
     * <h3>왜 이 문장이 필요한가 (⑧과 같은 실패 클래스)</h3>
     * <p>이 테이블의 부모는 {@code LS_EVNT_ANNO} 이고, 그 어노테이션은 {@code fk_ls_evnt_anno_raw}
     * (ON DELETE CASCADE)로 <b>⑩의 RAW 삭제와 함께 사라진다</b>. 그런데 검토 행의 FK
     * ({@code fk_ls_evnt_anno_review_anno}, V1 baseline)에는 <b>{@code ON DELETE} 절이 없어</b>
     * 기본값 {@code NO ACTION} 이다 — RESTRICT 와 마찬가지로 <b>CASCADE 를 타고 내려오지 않으므로</b>
     * 손으로 먼저 지우지 않으면 ⑩이 FK 위반으로 실패해 <b>폐기 스윕 전체가 롤백</b>된다.
     *
     * <p>도달 조건은 실재한다 — {@code EvntAnnoService.upsertOnce} 는 {@code rawSn} 만 받고 파생영상을
     * 배제하지 않으며, <b>최초 저장 시 검토 행을 항상 함께</b> 만든다({@code createAuto}). 즉 파생영상에
     * 이벤트 어노테이션을 한 번이라도 저장한 뒤 반려·유예가 지나면 그 스윕 tick 이 통째로 실패한다.
     *
     * <p>범위는 <b>그 영상의 어노테이션에 매달린 검토 행만</b>이다({@code EVNT_ANNO_SN} 서브쿼리). 검토
     * 행에는 {@code RAW_SN} 컬럼이 없어 영상으로 직접 좁힐 수 없으므로 어노테이션을 경유한다. 파라미터
     * 바인딩만 쓰며 문자열 연결이 없다(CWE-89).
     *
     * <p>⚠ 부모 {@code LS_EVNT_ANNO} 자체는 <b>삭제 대상이 아니다</b> — 검토 행이 사라지고 나면 ⑩의 RAW
     * CASCADE 가 정리한다. 목록에 넣으면 CASCADE 와 중복이고, 삭제 대상 집합만 근거 없이 넓어진다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_EVNT_ANNO_REVIEW
             WHERE EVNT_ANNO_SN IN (
                    SELECT a.EVNT_ANNO_SN FROM LS_EVNT_ANNO a WHERE a.RAW_SN = :rawSn)
            """, nativeQuery = true)
    int deleteDerivativeEventAnnotationReviews(@Param("rawSn") Long rawSn);

    /**
     * ⑩ <b>파생 영상 본체</b>({@code LS_DATA_RAW}) — 이 프로젝트에서 가장 위험한 문장이다.
     * V146 FK(ON DELETE CASCADE)가 자식 27개를 함께 정리한다.
     *
     * <h3>조건을 SQL 문장 자체에 리터럴로 박는 이유 (C1 · H7 · H8)</h3>
     * <ul>
     *   <li>{@code ORGNL_RAW_SN IS NOT NULL} — <b>원본 영상은 어떤 경로로도 이 문장에 걸리지 않는다.</b>
     *       서비스 레이어 사전검사만 두면 리팩터링·신규 호출 한 번에 원본이 지워진다. 기존
     *       {@code VideoRepository.deleteFailedDerivative} 와 동일 원칙(트리거 조건만 다른 신규 쿼리).</li>
     *   <li>{@code NOT EXISTS(APPROVED)} — "검수 완료·통지 건에 대한 관제 접근은 무조건 보장"(구속 정책).
     *       상위 게이트가 있어도 <b>최종 집행 지점</b>에서 독립적으로 재확인한다(단일 방어선 금지).</li>
     *   <li>폐기 표식 EXISTS(열림 + 유예 경과) — 클레임 이후 복구가 표식을 닫았으면 0건에 그친다.
     *       호출측은 0건을 <b>중단(전체 롤백)</b>으로 처리하므로 앞 단계 삭제도 되돌아간다.</li>
     * </ul>
     * <p>메서드명에 {@code Derivative} 를 박아 "원본 rawSn 만 받는 삭제 메서드" 와 시그니처가
     * 섞이지 않게 한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM LS_DATA_RAW
             WHERE RAW_SN = :rawSn
               AND ORGNL_RAW_SN IS NOT NULL
               AND NOT EXISTS (
                    SELECT 1 FROM LS_RAW_DATA_STATUS st
                     WHERE st.RAW_DATA_ID = :rawSn AND st.DATA_STTS_CD = 'APPROVED')
               AND EXISTS (
                    SELECT 1 FROM LS_DATA_AUG_DSCD d
                     WHERE d.DATA_AUG_DSCD_SN = :dscdSn
                       AND d.NEW_RAW_SN = :rawSn
                       AND d.RSTR_DT IS NULL
                       AND d.DEL_DT IS NULL
                       AND d.DSCD_DT <= :cutoff)
            """, nativeQuery = true)
    int deleteDiscardedDerivativeRaw(@Param("rawSn") Long rawSn,
                                     @Param("dscdSn") Long dscdSn,
                                     @Param("cutoff") LocalDateTime cutoff);

    /** 승인(APPROVED) 여부 사전 확인 — ⑩의 SQL 조건과 <b>같은 축</b>의 추가 방어층(H8). */
    @Query(value = """
            SELECT COUNT(1) FROM LS_RAW_DATA_STATUS st
             WHERE st.RAW_DATA_ID = :rawSn AND st.DATA_STTS_CD = 'APPROVED'
            """, nativeQuery = true)
    long countApprovedStatus(@Param("rawSn") Long rawSn);
}
