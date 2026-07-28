package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@ControlRepo
public interface LsDataSrcRepository extends JpaRepository<LsDataSrc, Long> {

    List<LsDataSrc> findByRawSnOrderByFrameNoAsc(Long rawSn);

    /** 프레임 청크 순회(대용량 다운스케일 — MEDIUM)용 페이징 조회. FRAME_NO 오름차순. */
    org.springframework.data.domain.Page<LsDataSrc> findByRawSnOrderByFrameNoAsc(
            Long rawSn, org.springframework.data.domain.Pageable pageable);

    /**
     * 프레임 필터(srcSn 집합) + 페이징 조회 — 관제 조회 API 의 {@code frameIds} 필터용(B-2).
     *
     * <p>필터를 <b>쿼리 조건으로</b> 내려야 한다. 페이지를 먼저 자르고 메모리에서 거르면 지정 프레임이
     * 첫 페이지 밖에 있을 때 빈 결과가 나오고, {@code totalElements} 는 필터 전 건수라 페이지 메타와
     * 내용이 모순된다. {@code rawSn} 을 함께 조건에 둬 타 영상 프레임 유입도 구조적으로 차단한다.
     */
    org.springframework.data.domain.Page<LsDataSrc> findByRawSnAndSrcSnInOrderByFrameNoAsc(
            Long rawSn, Collection<Long> srcSns, org.springframework.data.domain.Pageable pageable);

    Optional<LsDataSrc> findByRawSnAndFrameNo(Long rawSn, Integer frameNo);

    long countByRawSn(Long rawSn);

    /**
     * 영상별 첫 프레임의 SRC_SN 을 한 번에 조회 (N+1 회피).
     *
     * <p>LS_DATA_SRC.SRC_SN 은 IDENTITY 로 발급되며, FRAME_EXTRACT 단계가 FRAME_NO 오름차순으로
     * insert 하므로 동일 RAW_SN 내에서 MIN(SRC_SN) 은 FRAME_NO=0 의 row 와 동치이다.
     * 결과는 {@code [rawSn, firstSrcSn]} Object 배열 리스트. 빈 인자는 빈 결과를 반환한다.
     */
    @Query("select s.rawSn as rawSn, min(s.srcSn) as firstSrcSn "
            + "from LsDataSrc s where s.rawSn in :rawSns group by s.rawSn")
    List<Object[]> findFirstSrcSnGroupedByRawSn(@Param("rawSns") Collection<Long> rawSns);

    /**
     * 프레임 SRC_SN → 원본영상 RAW_SN 역매핑 일괄 조회 (N+1 회피).
     *
     * <p>증강 잡 카드(영상 단위 그룹)에서 LS_DATA_AUG.SRC_SN(대표프레임) 을 원본영상 RAW_SN 으로
     * 환원하기 위한 용도. 결과는 {@code [srcSn, rawSn]} Object 배열 리스트. 빈 인자는 빈 결과.
     */
    @Query("select s.srcSn as srcSn, s.rawSn as rawSn "
            + "from LsDataSrc s where s.srcSn in :srcSns")
    List<Object[]> findRawSnBySrcSnIn(@Param("srcSns") Collection<Long> srcSns);

    /**
     * 프레임 SRC_SN → FRM_NO 매핑 조회 (관제 수정 통지의 파일명 {@code {FRM_NO 4자리 zero-pad}.json} 산출용).
     *
     * <p>{@code rawSn} 을 함께 조건에 두어 <b>다른 영상의 프레임이 섞이면 결과에서 제외</b>되게 한다
     * (통지 페이로드에 타 영상 파일명이 실리는 것을 구조적으로 차단). 조회되지 않은 srcSn 은
     * caller 가 경고·메트릭으로 처리한다(사일런트 드롭 금지 — S10).
     * 결과는 {@code [srcSn, frameNo]} Object 배열 리스트.
     *
     * <p><b>원천 이미지 경로가 양쪽 다 비어 있는 프레임은 제외한다</b>(B-1) —
     * {@link #findExportableFrameNosByRawSn} 와 동일한 기준. export writer 가 건너뛴 프레임의 JSON
     * 파일명을 통지에 실으면 관제가 없는 파일을 픽업한다.
     */
    @Query("select s.srcSn as srcSn, s.frameNo as frameNo "
            + "from LsDataSrc s where s.rawSn = :rawSn and s.srcSn in :srcSns "
            + "and (coalesce(s.srcFilePathNm, '') <> '' or coalesce(s.deIdntfSrcFilePathNm, '') <> '')")
    List<Object[]> findExportableFrameNoByRawSnAndSrcSnIn(@Param("rawSn") Long rawSn,
                                                          @Param("srcSns") Collection<Long> srcSns);

    /**
     * MED-1(Phase 5C) — <b>원본·비식별 두 벌을 모두 보유</b>한 프레임만 SRC_SN → FRM_NO 로 조회한다.
     *
     * <p>재생성을 동반하지 않은 프레임 단위 수정({@code exportRegenerated=false}, {@link #findExportableFrameNoByRawSnAndSrcSnIn}
     * 의 "둘 중 하나라도 보유" 기준)에서는, 통지 {@code changed_items} 의 파일명 1건이 원본/비식별 <b>두 벌
     * 모두</b>를 가리키는데 한쪽 벌만 산출된 프레임(증강 파생: 원본만, {@code AugmentExtractPersist} — 비식별
     * 경로 null)의 파일명을 실으면 관제가 없는 벌을 픽업해 <b>404</b> 를 맞는다. 두 벌을 다 보유한 프레임만
     * 실어 이 404 를 구조적으로 차단한다. 한쪽 벌만 있는(파생 등 homogeneous) 프레임은 제외되며, 통지 자체는
     * 그대로 발송되어(D-ISSUE-43 유실 금지) 관제는 뷰로 재조회한다.
     *
     * <p>전량 재생성 경로({@link #findExportableFrameNosByRawSn}, {@code buildModifiedForAllFrames})는 이
     * 필터를 쓰지 않는다 — 파생영상(한쪽 벌만 산출)의 통지가 통째로 비지 않도록 "둘 중 하나라도 보유"를
     * 유지한다. 결과는 {@code [srcSn, frameNo]} Object 배열 리스트.
     */
    @Query("select s.srcSn as srcSn, s.frameNo as frameNo "
            + "from LsDataSrc s where s.rawSn = :rawSn and s.srcSn in :srcSns "
            + "and coalesce(s.srcFilePathNm, '') <> '' and coalesce(s.deIdntfSrcFilePathNm, '') <> ''")
    List<Object[]> findBothVelExportableFrameNoByRawSnAndSrcSnIn(@Param("rawSn") Long rawSn,
                                                                 @Param("srcSns") Collection<Long> srcSns);

    /**
     * 영상의 <b>산출 가능한</b> 프레임 FRM_NO 목록 (재승인 시 전체 파일 변경 통지용).
     *
     * <p><b>원천 이미지 경로가 양쪽 다 비어 있는 프레임은 제외한다</b>(B-1): export writer 는 원천
     * 이미지를 해석하지 못한 프레임을 <b>건너뛴다</b>(부분성공). 그런 프레임의 파일명을 통지
     * {@code changed_items} 에 실으면 관제가 <b>존재하지 않는 파일</b>을 픽업해 404 를 맞는다.
     * {@code image_count} 는 javadoc 에 "상한값"이라고 명시해 둘 수 있지만, {@code changed_items} 는
     * 명시적 파일 목록이라 같은 논리가 통하지 않는다.
     *
     * <p><b>필터 기준을 "둘 중 하나라도 보유" 로 잡은 근거</b>: 통지 페이로드에는 산출 종류(원본/비식별)
     * 축이 없어 파일명 1건이 두 벌(orgnl/deid) 모두를 가리킨다. 반면 파생영상(해상도 변경)은 원본
     * 픽셀이 실재하지 않아 {@code SRC_FILE_PATH_NM} 이 전부 비어 있고 비식별 벌만 산출된다 — "둘 다
     * 보유" 를 요구하면 그 영상의 통지가 통째로 비어 관제가 실재하는 산출물을 영영 못 가져간다.
     * 따라서 <b>어느 벌에서도 확실히 skip 되는 프레임(두 경로 모두 부재)만</b> 제외한다. 파일 존재
     * 여부(디스크 I/O)는 통지 조립 시점에 검사하지 않는다 — 통지는 export {@code @Async} 완료 전에
     * 나갈 수 있어 존재 검사 결과가 신뢰되지 않는다.
     */
    @Query("select s.frameNo from LsDataSrc s where s.rawSn = :rawSn "
            + "and (coalesce(s.srcFilePathNm, '') <> '' or coalesce(s.deIdntfSrcFilePathNm, '') <> '') "
            + "order by s.frameNo asc")
    List<Long> findExportableFrameNosByRawSn(@Param("rawSn") Long rawSn);

    /**
     * 영상별 프레임 개수를 한 번에 조회 (N+1 회피).
     *
     * <p>TaskBoardService.list 의 page.map 람다에서 각 row 마다 countByRawSn(...) 을 호출하면
     * 페이지 size 만큼 SELECT COUNT 쿼리가 발생한다 (size 20 기준 20회). 이를 단일 GROUP BY 쿼리로
     * 통합하여 페이지당 1회로 축소한다. 결과는 {@code [rawSn, frameCount]} Object 배열 리스트.
     * 프레임이 0건인 영상은 결과에 포함되지 않으므로 caller 가 0L 폴백 처리해야 한다.
     */
    @Query("select s.rawSn as rawSn, count(s) as frameCount "
            + "from LsDataSrc s where s.rawSn in :rawSns group by s.rawSn")
    List<Object[]> countByRawSnsGrouped(@Param("rawSns") Collection<Long> rawSns);

    /**
     * Phase 3 #5 — 비식별 누락 신고 처리 시 해당 영상 전체 프레임의 개인정보 3필드(익명/가명/개인정보 포함여부)를
     * NULL 로 초기화(파생 폴백 복귀)한다. 재비식별 후 stale '개인정보 없음' 오표기(CWE-359)를 방지한다.
     *
     * <p>{@code clearAutomatically} 미지정 — 신고 트랜잭션이 이 뒤에 부모 RAW 를 dirty-update(markDeidentified)
     * 하므로 영속성 컨텍스트를 비우면 안 된다(기존 라벨 bulk delete 와 동일 정책). 벌크 JPQL 로 즉시 flush 된다.
     *
     * @return 초기화된 프레임 수
     */
    @Modifying
    @Query("update LsDataSrc s set s.anonyInclYn = null, s.psdoInclYn = null, s.prvcInclYn = null, "
            + "s.updDt = CURRENT_TIMESTAMP where s.rawSn = :rawSn")
    int resetPrivacyMetaByRawSn(@Param("rawSn") Long rawSn);

    /**
     * DEV_FIX-B(M5) — {@link #resetPrivacyMetaByRawSn} 로 <b>실제 값이 지워질 프레임</b>의 SRC_SN 목록.
     *
     * <p>개인정보 3필드 리셋은 PII 표기를 되돌리는 행위라 <b>행 단위 감사</b>가 필요하다(OWASP A09).
     * 리셋 <b>직전</b>에 이 쿼리로 대상 프레임을 확정하고, 프레임별 감사 이력 1건을 남긴다. 값이 이미
     * NULL 인 프레임은 변화가 없으므로 대상에서 제외해 감사 잡음을 만들지 않는다.
     *
     * <p>보안: 파라미터 바인딩 JPQL 만 사용(CWE-89). 반환은 식별자뿐이라 PII 를 싣지 않는다.
     */
    @Query("select s.srcSn from LsDataSrc s where s.rawSn = :rawSn "
            + "and (s.anonyInclYn is not null or s.psdoInclYn is not null or s.prvcInclYn is not null) "
            + "order by s.srcSn")
    List<Long> findSrcSnsWithPrivacyMeta(@Param("rawSn") Long rawSn);

    /**
     * 해상도 파생 백필 전용 — 이관된 비식별 프레임 경로를 반영하고 원본 경로를 <b>NULL(원본 부재)</b> 로
     * 정정한다(E-ISSUE-21 파일 이관 + E-ISSUE-41 정책 A). 파일 복사·검증 성공 이후에만 호출된다.
     *
     * <p>{@code clearAutomatically} 미지정 — 같은 트랜잭션에서 다른 엔티티 dirty-update 를 유실시키지
     * 않기 위함(기존 {@link #resetPrivacyMetaByRawSn} 와 동일 정책).
     */
    @Modifying
    @Query("update LsDataSrc s set s.deIdntfSrcFilePathNm = :deidPath, s.srcFilePathNm = null, "
            + "s.updDt = CURRENT_TIMESTAMP where s.srcSn = :srcSn")
    int relocateDerivativeFramePath(@Param("srcSn") Long srcSn, @Param("deidPath") String deidPath);

    /**
     * C-ISSUE-21 — 프레임 행을 <b>비관적 락(SELECT ... FOR UPDATE)으로 잡으면서 그 시점의 실제 DB
     * 라벨셋 버전</b>을 읽는다.
     *
     * <p>라벨 full-replace 저장(PUT)이 "기존 라벨 read → 델타 계산 → 삭제/삽입" 을 수행하는 동안 다른
     * 저장이 끼어들지 못하도록 프레임 행으로 직렬화한다. 앱은 2노드 Active-Active 이므로 JVM 락
     * ({@code synchronized}/{@code ReentrantLock})은 방어가 되지 않아 DB 락으로만 해결한다
     * ({@code LsPortalUldFrmeRepository#findByUldFrmeSnAndOwnerForUpdate} 와 동일 패턴).
     *
     * <h3>왜 엔티티 조회({@code @Lock} + {@code select s from LsDataSrc s})로는 안 되는가 — 1차 캐시 함정</h3>
     * 진입부 인가 검사({@code LabelAccessGuard.verifyAndGet} → {@code findById})가 이미 같은 {@code LsDataSrc}
     * 를 영속성 컨텍스트에 적재하므로, 이어서 {@code @Lock} 엔티티 쿼리를 실행해도 Hibernate 는 <b>관리 중인
     * 인스턴스를 그대로 반환</b>하고 쿼리 결과로 필드를 덮어쓰지 않는다({@code @Lock} 은 SQL 에
     * {@code FOR UPDATE} 를 덧붙일 뿐이다). 그 결과 CAS 기준값이 <b>락 획득 前</b> 값이 되어, 락을 기다리는
     * 동안 다른 트랜잭션이 커밋한 변경을 관측하지 못한 채 stale 요청을 통과시킨다(방어 무력화).
     *
     * <p>스칼라 프로젝션(네이티브 {@code SELECT LBL_VER})은 엔티티 식별자 해석을 거치지 않아 1차 캐시를
     * 태우지 않으므로 <b>항상 DB 현재 값</b>을 돌려준다. 동시에 {@code FOR UPDATE} 로 행 락도 획득하므로
     * 쿼리 1회로 "락 획득 + 락 시점 값 읽기"가 원자적으로 성립한다.
     *
     * <p>파라미터 바인딩만 사용(CWE-89 표면 없음). 행이 없으면 빈 Optional.
     */
    @Query(value = "SELECT LBL_VER FROM LS_DATA_SRC WHERE SRC_SN = :srcSn FOR UPDATE",
            nativeQuery = true)
    Optional<Long> lockAndReadLabelVersion(@Param("srcSn") Long srcSn);

    /**
     * C-ISSUE-21 — 프레임(srcSn) 집합의 라벨셋 버전을 원자적으로 +1 한다.
     *
     * <p>네이티브 UPDATE 인 이유: {@code LsDataSrc.lblVer} 는 엔티티 flush 로 절대 쓰이지 않도록
     * {@code updatable=false} 로 매핑돼 있어(다른 컬럼 dirty-update 가 낡은 버전을 되쓰는 lost update 방지),
     * 값 변경 경로를 이 원자 UPDATE 하나로 고정한다. {@code LBL_VER = LBL_VER + 1} 은 DB 가 현재 값을 읽어
     * 증가시키므로 read-modify-write 경합이 없다(CWE-362).
     *
     * <p>{@code clearAutomatically} 미지정 — 같은 트랜잭션의 다른 엔티티 dirty-update 를 유실시키지 않기
     * 위함(리포 정책: 공유 벌크 쿼리에 무비판적 컨텍스트 초기화 금지). 호출부는 갱신된 값을
     * "잠금 하에 읽은 값 + 1" 로 계산한다. 빈 컬렉션이면 호출하지 않는다(호출부 가드).
     * 파라미터 바인딩만 사용(CWE-89 표면 없음).
     *
     * <h3>락 순서 규약 (DEV_FIX H2① — ABBA 데드락 방지, Critical)</h3>
     * 이 UPDATE 는 대상 프레임 행에 <b>쓰기 락</b>을 잡는다. 따라서 라벨 행을 삭제/수정하는 모든 경로는
     * <b>라벨 행을 건드리기 전에</b> 그 프레임의 bump(또는 {@link #lockAndReadLabelVersion} 의
     * {@code FOR UPDATE})를 먼저 수행해야 한다 — 규약: <b>프레임 락 → 라벨 행 락</b>. 순서를 뒤집으면
     * (라벨 삭제 후 bump) 프레임 락을 먼저 잡는 라벨 저장 경로와 교차해 PostgreSQL 40P01
     * (deadlock detected) → 500 이 발생한다.
     *
     * <p><b>정정(DEV_FIX H4)</b>: 구 주석은 "삽입(INSERT)만 하는 경로는 락을 잡지 않으므로 규약 대상이
     * 아니다"라고 했으나 부정확하다 — bump 문장 자체가 프레임 행 쓰기 락을 잡는다. 삽입 전용 경로가
     * 안전한 진짜 이유는 <b>bump 가 그 트랜잭션의 유일한 프레임 락 획득 지점</b>이고 그 시점까지 기존
     * 라벨 행 락을 쥐고 있지 않아 순환 대기의 구성원이 될 수 없기 때문이다. 반대로 한 트랜잭션이
     * <b>서로 다른 프레임 집합을 2회 이상</b> bump 하면 순서 규약만으로는 막을 수 없는 문장 간 ABBA 가
     * 성립하므로, 그런 경로는 {@link #lockFramesByRawSn} 로 락 획득 지점을 1곳으로 통합해야 한다.
     *
     * @return 갱신된 프레임 수
     */
    @Modifying
    @Query(value = "UPDATE LS_DATA_SRC SET LBL_VER = LBL_VER + 1 WHERE SRC_SN IN (:srcSns)",
            nativeQuery = true)
    int bumpLabelVersionIn(@Param("srcSns") Collection<Long> srcSns);

    /**
     * C-ISSUE-21 — 영상(rawSn) 전 프레임의 라벨셋 버전을 원자적으로 +1 한다.
     * 영상 단위로 라벨을 일괄 변경하는 경로(비식별 신고 전량 삭제·버전 롤백·배치 오토라벨/보간)에서
     * 사용해 프레임별 UPDATE N회를 단일 문장으로 대체한다(N+1 금지).
     * 상세 정책은 {@link #bumpLabelVersionIn} 주석 참조.
     *
     * @return 갱신된 프레임 수
     */
    @Modifying
    @Query(value = "UPDATE LS_DATA_SRC SET LBL_VER = LBL_VER + 1 WHERE RAW_SN = :rawSn",
            nativeQuery = true)
    int bumpLabelVersionByRawSn(@Param("rawSn") Long rawSn);

    /**
     * DEV_FIX(H4) — 영상(rawSn) 전 프레임 행 락을 <b>단일 문장·SRC_SN 오름차순</b>으로 선점한다.
     *
     * <h3>왜 필요한가 — 다중 bump 사이의 ABBA</h3>
     * <p>{@link #bumpLabelVersionIn} 은 대상 프레임 행에 쓰기 락을 잡는다. 그런데 트랙 삭제/분할/병합·
     * 배치 보간은 <b>한 트랜잭션에서 서로 다른 프레임 집합을 2회 이상</b> bump 한다(변경 대상 → 재보간
     * 산출 프레임). 두 트랜잭션의 두 집합이 교차하면
     * ({@code T1: {5,9} → {3,5}}, {@code T2: {3} → {5,7}}) 서로를 기다려 PostgreSQL 40P01
     * (deadlock detected) → 500 이 난다. "프레임 락 → 라벨 락" 순서만으로는 이 <b>문장 간</b> 교차를 막지
     * 못한다.
     *
     * <p>이를 없애기 위해 해당 경로들은 트랜잭션 <b>맨 앞에서 이 메서드 1회</b>로 필요한 프레임 락을
     * 모두(=영상 전 프레임, 이후 bump 집합의 상위집합) 선점한다. 이후의 bump 는 <b>이미 보유한 행</b>만
     * 건드리므로 새 락을 전혀 획득하지 않아 대기 지점이 트랜잭션당 1곳으로 줄고, 그 1곳도 아무 락도
     * 쥐지 않은 상태에서 대기하므로 순환 대기(cycle)가 성립하지 않는다.
     *
     * <h3>왜 {@code FOR UPDATE} 가 아니라 {@code FOR NO KEY UPDATE} 인가</h3>
     * <p>{@code FOR UPDATE} 는 FK 자식 INSERT 가 부모에 잡는 {@code FOR KEY SHARE} 와 충돌해, 기존에는
     * 막히지 않던 라벨 INSERT 까지 새로 차단한다. 비-키 컬럼 UPDATE({@code LBL_VER}) 가 실제로 잡는 락
     * 모드는 {@code FOR NO KEY UPDATE} 이므로 이 모드를 그대로 사용해 <b>기존 동시성 동작을 바꾸지 않고</b>
     * 락 획득 시점만 앞당긴다.
     *
     * <p>{@code ORDER BY SRC_SN} 으로 획득 순서를 결정적으로 고정한다(LockRows 가 Sort 위에서 동작).
     * 파라미터 바인딩만 사용(CWE-89 표면 없음). 프레임이 없으면 빈 리스트.
     *
     * @return 락을 획득한 프레임 SRC_SN 목록(오름차순)
     */
    @Query(value = "SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = :rawSn "
            + "ORDER BY SRC_SN FOR NO KEY UPDATE", nativeQuery = true)
    List<Long> lockFramesByRawSn(@Param("rawSn") Long rawSn);
}
