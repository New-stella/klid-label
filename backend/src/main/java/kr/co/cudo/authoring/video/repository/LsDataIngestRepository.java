package kr.co.cudo.authoring.video.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 관제 인입 리포지토리 (LS_DATA_INGEST, V147).
 *
 * <p>인입 행은 <b>영구 보존</b>한다(감사 추적) — 삭제하면 "분명 넣었다" 분쟁의 대조 근거가 사라지고
 * 중복 INSERT 감지도 못 한다(설계 §6-1). 여기에 삭제 쿼리를 추가하지 않으며, 상속받은
 * {@code JpaRepository} 의 {@code delete*} 도 호출하지 않는다.
 *
 * <p>파생 쿼리 + 파라미터 바인딩만 사용하므로 SQL 문자열 조립이 없다(CWE-89 표면 없음).
 */
@ControlRepo
public interface LsDataIngestRepository extends JpaRepository<LsDataIngest, Long> {

    /**
     * 폴링 후보 — 미처리({@code PENDING}) 행 중 <b>재시도 예정 시각이 도래한</b> 행을 수신일시
     * 오름차순(FIFO)으로 상한만큼 조회한다.
     *
     * <h3>왜 예정 시각 조건이 필요한가 (설계 §6-0-1-a ㉢ — backoff)</h3>
     * <p>미도착 대기 <b>상한</b>만으로는 무한 정지가 <b>최대 상한(기본 24h) 정지</b>로 유계화될 뿐이다.
     * 미도착 행이 스캔 상한만큼 FIFO 앞자리에 있으면 그동안 뒤의 정상 인입은 <b>한 건도</b> 픽업되지
     * 않는다. 미도착 관측 시 {@code NEXT_RTRY_DT} 를 뒤로 밀면 그 행이 후보에서 빠지고 커서가 전진한다.
     *
     * <p>{@code NEXT_RTRY_DT IS NULL} 도 후보다 — 관제가 INSERT 한 신규 행은 이 값이 없다(즉시 대상).
     *
     * <h3>인덱스 정합</h3>
     * <p>부분 인덱스 {@code IX_LS_DATA_INGEST_POLL (RCPTN_DT, RCPTN_SN) INCLUDE (NEXT_RTRY_DT)
     * WHERE PROC_STTS_CD='PENDING'} 과 술어·정렬이 일치한다. 예정 시각 비교값({@code now})은 immutable
     * 이 아니라 인덱스 <b>술어</b>에 넣을 수 없어 {@code INCLUDE} 로 실었다 — 고착 행이 많아도 힙 방문
     * 없이 인덱스에서 걸러진다.
     *
     * <p>{@code Pageable} 상한 필수(전량 조회 금지, CWE-770). 상한이 걸린 상태에서 순서가 흔들리면 특정
     * 행이 영원히 굶으므로, 같은 수신일시가 겹칠 때를 대비해 PK 를 2차 정렬키로 고정한다.
     *
     * @param now 판정 기준 시각 — <b>우리 시계</b>다(관제 수신값 아님). 테스트 결정성을 위해 주입받는다.
     */
    @Query("""
            SELECT i FROM LsDataIngest i
             WHERE i.procSttsCd = :status
               AND (i.nextRtryDt IS NULL OR i.nextRtryDt <= :now)
             ORDER BY i.rcptnDt ASC, i.rcptnSn ASC
            """)
    List<LsDataIngest> findPollCandidates(@Param("status") String procSttsCd,
                                          @Param("now") LocalDateTime now,
                                          Pageable pageable);

    /** {@link #findPollCandidates} 의 상태 고정 래퍼 — 호출부가 상태 어휘를 재선언하지 않게 한다. */
    default List<LsDataIngest> findPendingReadyForPolling(LocalDateTime now, Pageable pageable) {
        return findPollCandidates(LsDataIngest.PROC_STTS_PENDING, now, pageable);
    }

    /** 상태별 인입 행 수 — 재큐 진입점이 "아직 남은 종결 건수"를 응답에 담기 위한 집계. */
    long countByProcSttsCd(String procSttsCd);

    /**
     * 적재 결과 영상({@code RAW_SN}) 로 인입 행을 <b>역조회</b>한다 — 기술메타 소스 조달 전용
     * ({@code VideoMetaService#loadIngestMeta}).
     *
     * <h3>왜 {@code RAW_SN} 축인가 (VMS_CLIP_ID 아님)</h3>
     * <p>{@code RAW_SN} 은 적재 트랜잭션이 {@code markDone(rawSn)} 으로 <b>같은 커밋에</b> 기록하고,
     * 기술메타 러너는 그 커밋 이후(AFTER_COMMIT → {@code @Async})에 실행되므로 항상 보인다.
     * {@code VMS_CLIP_ID} 로 조회하면 <b>파생영상(증강·해상도)의 마커 접미가 붙은 clipId</b> 가 인입에
     * 없어 매칭되지 않고, 마커 문자열에 의존하는 판정을 되살리게 된다(Phase 5 가 제거한 축).
     *
     * <h3>단건 보장을 문법에 기대지 않는다</h3>
     * <p>{@code RAW_SN} 에는 UNIQUE 가 없다({@code VMS_CLIP_ID} 만 UK). 실제로는 클립 1건 = 인입 1행 =
     * 영상 1건이라 1행이지만, 파생 시나리오·수기 정정으로 2행이 생기면 {@code Optional} 파생 쿼리는
     * {@code NonUniqueResultException} 으로 <b>기술메타 적재 전체를 실패</b>시킨다. 최신(수신 PK 역순)
     * 1행만 상한을 걸어 읽는다(CWE-770 — 무제한 조회 금지).
     *
     * @param rawSn 적재 결과 영상 PK. 파생영상 등 인입 행이 없는 영상은 빈 결과.
     */
    @Query("""
            SELECT i FROM LsDataIngest i
             WHERE i.rawSn = :rawSn
             ORDER BY i.rcptnSn DESC
            """)
    List<LsDataIngest> findByRawSnLatestFirst(@Param("rawSn") Long rawSn, Pageable pageable);

    /** {@link #findByRawSnLatestFirst} 의 단건 래퍼 — 호출부가 상한·정렬을 재선언하지 않게 한다. */
    default Optional<LsDataIngest> findLatestByRawSn(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }
        return findByRawSnLatestFirst(rawSn, PageRequest.of(0, 1)).stream().findFirst();
    }

    /**
     * 클립 단위 멱등 조회 — 관제 재송신·중복 적재 판정의 진입점.
     * {@code UK_LS_DATA_INGEST_CLIP} 이 걸려 있어 최대 1행이다.
     */
    Optional<LsDataIngest> findByVmsClipId(String vmsClipId);

    /**
     * 처리 착수 <b>원자 클레임</b> — 미처리({@code PENDING}) 행 하나를 {@code PROCESSING} 으로
     * 전이시키며 잡는다. 인입 행의 착수 전이는 <b>이 메서드가 유일한 통로</b>다.
     *
     * <h3>왜 엔티티 setter 가 아니라 조건부 UPDATE 인가 (CWE-362)</h3>
     * <p>구 구현({@code LsDataIngest#markProcessing} — 제거됨)은 필드만 바꾸는 "읽고-쓰기"였다.
     * {@link #findPendingReadyForPolling} 로 같은 후보 목록을 받은 두 실행이 같은 행을 각자
     * {@code PROCESSING} 으로 쓰고 <b>둘 다 "내가 잡았다"고 착각해 같은 클립을 중복 적재</b>한다.
     *
     * <p>{@code @DynamicUpdate} 는 이를 막지 못한다 — SET 절을 dirty 컬럼으로 좁힐 뿐
     * UPDATE-UPDATE 충돌을 감지하지 않는다({@code @Version} 도 이 엔티티에 없다). Quartz
     * 클러스터링도 <b>트리거 중복 발화만</b> 막으며 잡 내부 레이스는 각 잡의 원자 클레임이
     * 별도로 막는다(CLAUDE.md '배치 성능' — 서로 대체하지 않는다). 배포가 2노드
     * Active-Active 이므로 DB 레벨 배타성이 유일한 근거다.
     *
     * <p>PostgreSQL 은 UPDATE 시 행 락을 얻은 뒤 <b>갱신된 최신 버전으로 WHERE 를 재평가</b>하므로,
     * 두 노드가 같은 행을 동시에 노려도 <b>한쪽만 1행</b>을 얻는다(별도 잠금 컬럼 불요).
     * {@code PROC_STTS_CD = 'PENDING'} 술어가 그 사이 종결(DONE/FAILED)된 행의 재착수도 막는다
     * (fail-safe). 파라미터 바인딩만 사용한다(CWE-89 표면 없음).
     *
     * <p><b>{@code PRCS_DT} 는 찍지 않는다</b> — 그 컬럼은 "적재 완료 또는 종결 시각"이라 착수
     * 시점에 채우면 종결 시각이 앞당겨진다. 처리 주체(worker) 식별자도 남기지 않는다: V147 은
     * 35컬럼 확정이라 소유자 컬럼이 없다.
     *
     * <h3>영속성 컨텍스트 주의</h3>
     * <p>{@code claimExpired} 관례대로 {@code flushAutomatically} 만 켜고
     * <b>{@code clearAutomatically} 는 켜지 않는다</b>. 그것을 켜면 이 리포지토리와 무관한 엔티티까지
     * <b>영속성 컨텍스트 전체가 detach</b> 되어, 호출자가 들고 있던 인입 엔티티의 이후
     * {@code markDone}/{@code markFailed} 가 조용히 영속되지 않는다.
     *
     * <p>대신 이 UPDATE 는 영속성 컨텍스트를 우회하므로, 이미 로드된 엔티티의
     * {@code procSttsCd} 는 {@code PENDING} 인 채 남는다(DB 는 {@code PROCESSING}). 착수 여부는
     * <b>반환값으로만 판정</b>하고 엔티티의 상태값으로 판정하지 않는다. 이후
     * {@code markDone}/{@code markFailed} 는 상태를 절대값으로 덮어쓰므로 이 stale 값의 영향을
     * 받지 않는다.
     *
     * @param rcptnSn 클레임 대상 인입 행 PK
     * @return 클레임에 성공한 행 수 — <b>1 = 이 실행이 잡았다</b> / <b>0 = 다른 실행이 이미 가져갔거나
     *         그 사이 종결됐다</b>(적재를 진행하면 안 된다)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_INGEST
               SET PROC_STTS_CD = 'PROCESSING'
             WHERE RCPTN_SN = :rcptnSn
               AND PROC_STTS_CD = 'PENDING'
            """, nativeQuery = true)
    int claimForProcessing(@Param("rcptnSn") Long rcptnSn);

    /**
     * 미처리 <b>복귀</b> — 클레임으로 {@code PROCESSING} 이 된 행을 {@code PENDING} 으로 되돌려
     * <b>다음 주기가 다시 집게</b> 한다. {@link #claimForProcessing} 의 역방향이며 유일한 복귀 통로다.
     *
     * <h3>왜 필요한가 (설계 §6-0 / R4)</h3>
     * <p>파일이 아직 NAS 에 도착하지 않은 경우는 <b>실패가 아니라 미처리</b>다. 그런데 클레임 뒤에
     * 되돌릴 통로가 없으면 결말이 둘뿐인데 <b>둘 다 그 영상이 다시는 처리되지 않는다</b>:
     * <ul>
     *   <li>{@code markFailed} 를 부르면 {@code FAILED} 가 되어 폴링 술어({@code PENDING})에서
     *       빠진다 → <b>영구 미적재</b>.</li>
     *   <li>아무것도 부르지 않으면 {@code PROCESSING} 인 채 남는다 → <b>영구 좀비</b>.</li>
     * </ul>
     *
     * <h3>재시도 횟수({@code RTY_CNT})는 증가시키지 않는다</h3>
     * <p>실패가 아니라 <b>미처리 복귀</b>이기 때문이다. 이 카운터는 {@code markFailed} 가 누적하는
     * "종결까지의 실패 시도 이력"이라, 파일 대기로 부풀리면 실패 이력과 대기 이력이 섞여 실패 원인
     * 추적이 불가능해진다.
     *
     * <h3>원자성 (CWE-362)</h3>
     * <p>{@link #claimForProcessing} 과 동일한 조건부 UPDATE 다 — 술어
     * {@code PROC_STTS_CD = 'PROCESSING'} 이 있어 <b>종결된 행(DONE/FAILED)을 되살리지 않고</b>,
     * 이미 {@code PENDING} 인 행을 중복 복귀시키지도 않는다. PostgreSQL 이 행 락 획득 후 최신
     * 버전으로 WHERE 를 재평가하므로 클레임과 복귀가 동시에 같은 행을 노려도 한쪽만 1행을 얻는다.
     * 파라미터 바인딩만 사용한다(CWE-89 표면 없음).
     *
     * <h3>대기 예산 앵커 + backoff 를 <b>같은 UPDATE 에서</b> 찍는다 (설계 §6-0-1-a)</h3>
     * <ul>
     *   <li>{@code PRCS_DT = COALESCE(PRCS_DT, :observedAt)} — <b>최초 미도착 관측 시각</b>을 우리
     *       시계로 스탬프한다. 대기 상한은 관제가 준 {@code RCPTN_DT} 가 아니라 이 값 기준으로 잰다
     *       ({@code RCPTN_DT} 는 INSERT 주체가 관제라 과거 시각이 오면 도착 즉시 종결된다).
     *       {@code COALESCE} 라 두 번째 관측부터는 앵커가 <b>움직이지 않는다</b> — 갱신하면 상한이
     *       영원히 오지 않아 ①(상한)이 무의미해진다.</li>
     *   <li>{@code NEXT_RTRY_DT = :nextRtryAt} — 다음 시도를 뒤로 밀어 이 행을 폴링 후보에서 빼고
     *       커서를 전진시킨다(head-of-line blocking 차단).</li>
     * </ul>
     * <p>분리해서 두 번 쓰지 않는 이유: 상태 복귀와 예산 스탬프 사이에 다른 실행이 끼면 앵커 없는
     * {@code PENDING} 행(= 상한이 영원히 시작되지 않는 행)이 생긴다.
     *
     * <p><b>{@code ERR_MSG} 는 건드리지 않는다</b> — 복귀는 종결이 아니므로 이전 실패 사유 이력을
     * 지우지 않는다.
     *
     * <h3>영속성 컨텍스트 주의</h3>
     * <p>{@link #claimForProcessing} 관례대로 {@code flushAutomatically} 만 켜고
     * <b>{@code clearAutomatically} 는 켜지 않는다</b>(영속성 컨텍스트 전체 detach → 호출자의 후속
     * flush 유실). 이 UPDATE 도 영속성 컨텍스트를 우회하므로 이미 로드된 엔티티의
     * {@code procSttsCd} 는 stale 이다 — 복귀 여부는 <b>반환값으로만</b> 판정한다.
     *
     * @param rcptnSn    복귀 대상 인입 행 PK
     * @param observedAt 이번 미도착 관측 시각(우리 시계) — 앵커가 비어 있을 때만 채워진다
     * @param nextRtryAt 다음 재시도 예정 시각(우리 시계 + backoff)
     * @return 되돌린 행 수 — <b>1 = 복귀 성공</b>(예정 시각 이후 폴링 대상) / <b>0 = 이 실행이 클레임한
     *         행이 아니거나 그 사이 다른 상태가 됐다</b>
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_INGEST
               SET PROC_STTS_CD = 'PENDING',
                   PRCS_DT = COALESCE(PRCS_DT, :observedAt),
                   NEXT_RTRY_DT = :nextRtryAt
             WHERE RCPTN_SN = :rcptnSn
               AND PROC_STTS_CD = 'PROCESSING'
            """, nativeQuery = true)
    int revertToPendingForRetry(@Param("rcptnSn") Long rcptnSn,
                                @Param("observedAt") LocalDateTime observedAt,
                                @Param("nextRtryAt") LocalDateTime nextRtryAt);

    /**
     * <b>종결(실패) 재큐</b> — {@code FAILED} 로 종결된 행을 {@code PENDING} 으로 되돌려 다음 스캔이
     * 다시 집게 한다. {@code FAILED} 를 벗어나는 <b>유일한 통로</b>다.
     *
     * <h3>왜 필요한가 (설계 §6-0-1 ② — 종결은 반드시 가역이다)</h3>
     * <p>이 통로가 없으면 <b>한 번 {@code FAILED} 가 된 클립은 수동 SQL 없이 영원히 적재되지 않는다</b>:
     * <ul>
     *   <li>{@code UK_LS_DATA_INGEST_CLIP(VMS_CLIP_ID)} 때문에 관제가 같은 클립을 다시 INSERT 할 수 없다.</li>
     *   <li>인입 행은 <b>삭제 금지</b>(감사 추적)라 지우고 다시 넣을 수도 없다.</li>
     *   <li>관제 수신 컬럼에는 setter 가 없어 JPA 로 고칠 수도 없다.</li>
     * </ul>
     * <p>종결 사유가 <b>설정·환경 오류</b>일 수 있다는 점이 결정적이다 — 경로 검증
     * ({@code authoring.storage.raw-mount-roots} 가 관제 NAS 실경로와 어긋남)이나 미도착 대기 상한
     * 초과는 사람이 고치면 통과한다. 되돌릴 수 없는 차단은 그 자체가 가용성 결함이다.
     *
     * <h3>무엇을 바꾸는가</h3>
     * <ul>
     *   <li>{@code ERR_MSG} 를 <b>비운다</b> — 종결 사유는 이번 재큐로 무효가 됐고, 남겨두면 이후
     *       성공 종결({@code markDone})까지 사유가 살아 있어 오판을 부른다.</li>
     *   <li>{@code RTY_CNT} 를 <b>증가시킨다</b> — 재큐는 <b>실패 이력</b>의 연장이다(미도착 복귀와 반대).
     *       이 카운터로 "몇 번 실패시켰다 되살렸는지"가 남아 반복 실패를 식별할 수 있다.</li>
     *   <li><b>{@code PRCS_DT}·{@code NEXT_RTRY_DT} 를 비운다 — 대기 예산 리셋</b>(설계 §6-0-1-a ㉡).
     *       예산 앵커를 그대로 두면 <b>상한 초과로 종결된 행을 재큐해도 다음 tick(≤60s)에 즉시
     *       재종결</b>된다(재시도 창 0초). 그러면 이 통로 자체가 무의미해진다. 예정 시각도 함께 비워야
     *       되살린 행이 곧바로 폴링 후보가 된다.</li>
     * </ul>
     *
     * <h3>원자성 (CWE-362)</h3>
     * <p>{@link #claimForProcessing} 과 동일한 조건부 UPDATE 다. 술어 {@code PROC_STTS_CD = 'FAILED'}
     * 가 있어 <b>처리 중({@code PROCESSING})인 행을 뺏지 않고</b>, 성공 종결({@code DONE})을 되살려
     * 중복 적재를 유발하지도 않는다. PostgreSQL 이 행 락 획득 후 최신 버전으로 WHERE 를 재평가하므로
     * 재큐와 클레임이 동시에 같은 행을 노려도 순서에 관계없이 한쪽만 1행을 얻는다. 파라미터 바인딩만
     * 사용한다(CWE-89 표면 없음). 영속성 컨텍스트 규약은 위 두 메서드와 동일하다
     * ({@code flushAutomatically} 만 켜고 {@code clearAutomatically} 는 켜지 않는다).
     *
     * <p><b>운영 진입점</b>: {@code ControlIngestRequeueService}(REVIEWER 전용, 설계 §6-0-1-b).
     * 쓰기 쿼리라 <b>호출자 트랜잭션</b>({@code controlTransactionManager})이 필요하다.
     *
     * @param rcptnSn 재큐 대상 인입 행 PK
     * @return 재큐된 행 수 — <b>1 = 재큐 성공</b>(다음 스캔 대상) / <b>0 = {@code FAILED} 가 아니다</b>
     *         (이미 재큐됐거나 처리 중이거나 성공 종결됐다)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_INGEST
               SET PROC_STTS_CD = 'PENDING',
                   RTY_CNT = RTY_CNT + 1,
                   ERR_MSG = NULL,
                   PRCS_DT = NULL,
                   NEXT_RTRY_DT = NULL
             WHERE RCPTN_SN = :rcptnSn
               AND PROC_STTS_CD = 'FAILED'
            """, nativeQuery = true)
    int requeueFailedForRetry(@Param("rcptnSn") Long rcptnSn);

    /**
     * <b>종결(실패) 일괄 재큐</b> — {@code FAILED} 행을 오래된 수신일시 순으로 <b>최대 {@code limit} 건</b>
     * {@code PENDING} 으로 되돌린다. 갱신 내용은 {@link #requeueFailedForRetry} 와 <b>완전히 동일</b>하다.
     *
     * <h3>왜 일괄이 필요한가 (설계 §6-0-1-b)</h3>
     * <p>실사용 시나리오가 <b>대량 오설정 회수</b>다 — {@code authoring.storage.raw-mount-roots} 가 관제
     * NAS 실경로와 어긋나면 tick 당 상한(100)만큼 {@code FAILED} 가 양산된다. 이걸 단건 API 로 하나씩
     * 되살리라고 하면 결국 운영자가 수동 SQL 을 쓰게 되어 통로를 만든 의미가 없다.
     *
     * <h3>무제한 조회·갱신 금지 (CWE-770)</h3>
     * <p>대상 선정 서브쿼리에 <b>{@code LIMIT} 을 강제</b>한다(호출 측이 상한을 검증해 넘긴다). 상한이
     * 없으면 한 트랜잭션이 수십만 행을 잠가 인입 폴링·관제 INSERT 가 함께 멈춘다. 정렬을
     * {@code RCPTN_DT, RCPTN_SN} 으로 고정해 <b>오래된 것부터</b> 결정적으로 회수하고(같은 값 겹침 시
     * 굶는 행이 생기지 않게 PK 2차 정렬), 남은 건수는 호출 측이 재호출로 이어서 처리한다.
     *
     * <h3>원자성 (CWE-362)</h3>
     * <p>서브쿼리로 고른 뒤에도 <b>바깥 UPDATE 에 {@code PROC_STTS_CD='FAILED'} 술어를 다시</b> 건다 —
     * 선정과 갱신 사이에 다른 실행이 상태를 바꿨다면 그 행은 갱신되지 않는다(처리 중 행 탈취·성공 종결
     * 되살리기 차단). 파라미터 바인딩만 사용한다(CWE-89 표면 없음).
     *
     * @param limit 이번 호출이 되살릴 최대 행 수(호출 측에서 1 이상 상한 이하로 검증)
     * @return 실제로 재큐된 행 수
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE LS_DATA_INGEST
               SET PROC_STTS_CD = 'PENDING',
                   RTY_CNT = RTY_CNT + 1,
                   ERR_MSG = NULL,
                   PRCS_DT = NULL,
                   NEXT_RTRY_DT = NULL
             WHERE RCPTN_SN IN (
                       SELECT RCPTN_SN
                         FROM LS_DATA_INGEST
                        WHERE PROC_STTS_CD = 'FAILED'
                        ORDER BY RCPTN_DT ASC, RCPTN_SN ASC
                        LIMIT :limit)
               AND PROC_STTS_CD = 'FAILED'
            """, nativeQuery = true)
    int requeueFailedBatch(@Param("limit") int limit);
}
