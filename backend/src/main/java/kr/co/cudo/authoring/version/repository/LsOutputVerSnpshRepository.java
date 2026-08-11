package kr.co.cudo.authoring.version.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.version.dto.SnapshotVersionRef;
import kr.co.cudo.authoring.version.entity.LsOutputVerSnpsh;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 산출 회차 ↔ 라벨 버전 스냅샷 매핑 (V183) 조회·기록.
 *
 * @design D5
 * @req R6
 */
@ControlRepo
public interface LsOutputVerSnpshRepository extends JpaRepository<LsOutputVerSnpsh, Long> {

    /**
     * 이 회차의 <b>내용이 된 스냅샷 전량</b>을 기록한다 (산출 마감 트랜잭션 전용, 멱등).
     *
     * <h3>왜 ACTIVE 전량인가 (미채번 행만이 아니라)</h3>
     * {@code LS_LABEL_VERSION.VER_NO} 채번은 <b>번호가 없는 행만</b> 대상이다("그 내용이 처음 산출
     * 내용이 된 회차"라는 그 컬럼의 의미상 옳다). 그러나 <b>회차의 내용</b>은 이미 번호가 찍힌
     * 스냅샷일 수도 있다 — 내용 무변경 회차, 그리고 롤백으로 옛 스냅샷이 다시 ACTIVE 가 된 회차가
     * 그렇다. 그 대응을 여기 남기지 않으면 「시작 버전 선택」이 그 회차의 내용을 알 방법이 없다.
     *
     * <h3>재마감은 <b>갱신</b>이다 — 첫 시도 값에 고정하지 않는다 (DEV_FIX 2라운드 이슈 2)</h3>
     * 산출은 실패 후 <b>같은 {@code OUTPUT_VER_NO} 로 재시도</b>된다
     * ({@code DatasetExportTxService.claimForRetry} → {@code finalizeUnlessUnderDeidentReport} 재진입).
     * 그 사이 ACTIVE 스냅샷이 바뀌면 <b>산출 폴더는 새 내용으로 재생성되는데</b> 매핑만 첫 시도 값에
     * 머문다 — 이 테이블이 없애려던 결함(매핑과 실제 산출 내용의 불일치)이 좁은 형태로 남는 것이다.
     * 그래서 {@code DO UPDATE} 로 <b>그 회차의 최신 ACTIVE 스냅샷</b>으로 덮는다.
     *
     * <h3>왜 네이티브 INSERT … SELECT 인가</h3>
     * <ul>
     *   <li><b>CWE-770</b> — 프레임 수만큼의 엔티티(스냅샷 본문 최대 10MB/건)를 힙에 올리지 않는다.
     *       DB 안에서 끝난다.</li>
     *   <li><b>멱등</b> — UK{@code (DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO)} 충돌을 예외 대신 갱신으로
     *       흡수한다. 예외를 던지면 산출 마감 트랜잭션 전체가 롤백되어 <b>산출은 성공했는데 마감이 안
     *       되는</b> 상태가 된다. <b>값이 같으면 {@code WHERE … IS DISTINCT FROM} 이 걸러내 쓰기 자체가
     *       없다</b> — 재실행이 dead tuple·불필요한 행 잠금을 만들지 않고 {@code REG_DT}(그 매핑을 기록한
     *       시각)도 흔들리지 않는다.</li>
     *   <li><b>{@code DISTINCT ON}</b> — 한 프레임에 ACTIVE 행이 둘 이상인 오염 상태에서도 충돌 키가
     *       중복되지 않게 한다. 없으면 {@code DO UPDATE} 가 "cannot affect row a second time"(21000)로
     *       <b>산출 마감을 통째로 롤백</b>시킨다({@code DO NOTHING} 시절엔 조용히 흡수되던 입력이다).
     *       {@code LBL_VERSION_SN DESC} 로 <b>가장 최근 스냅샷</b>을 결정적으로 고른다 —
     *       {@code StartVersionService.resolveTargets} 의 동률 처리(행 PK 가 큰 쪽)와 같은 축이다.</li>
     *   <li>파라미터 바인딩만 사용한다(CWE-89 — 문자열 연결 없음).</li>
     * </ul>
     *
     * <p>{@code DATA_SRC_SN IS NULL} 인 레거시 영상 스코프 스냅샷(구 비식별 신고 경로)은 제외한다 —
     * 프레임 대응이 아니라 매핑 대상이 아니다.
     *
     * @return 새로 기록되거나 <b>다른 스냅샷으로 갱신된</b> 매핑 수 (내용 무변경 재실행이면 0 — 정상)
     */
    @Modifying
    @Query(value = """
            INSERT INTO LS_OUTPUT_VER_SNPSH
                   (DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO, LBL_VER_SN, REG_DT)
            SELECT DISTINCT ON (v.DATA_RAW_SN, v.DATA_SRC_SN)
                   v.DATA_RAW_SN, v.DATA_SRC_SN, :verNo, v.LBL_VERSION_SN, CURRENT_TIMESTAMP
              FROM LS_LABEL_VERSION v
             WHERE v.DATA_RAW_SN = :rawSn
               AND v.ACTVTN_YN = :activeYn
               AND v.DATA_SRC_SN IS NOT NULL
             ORDER BY v.DATA_RAW_SN, v.DATA_SRC_SN, v.LBL_VERSION_SN DESC
            ON CONFLICT (DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO) DO UPDATE
               SET LBL_VER_SN = EXCLUDED.LBL_VER_SN,
                   REG_DT     = EXCLUDED.REG_DT
             WHERE LS_OUTPUT_VER_SNPSH.LBL_VER_SN IS DISTINCT FROM EXCLUDED.LBL_VER_SN
            """, nativeQuery = true)
    int recordActiveSnapshots(@Param("rawSn") Long rawSn,
                              @Param("verNo") Integer verNo,
                              @Param("activeYn") String activeYn);

    /**
     * 「시작 버전 선택」 해석 입력 — 요청 회차 <b>이하</b>의 매핑 전량.
     *
     * <p>프레임마다 "회차 ≤ N 중 최대"를 고르는 판정은 호출부({@code StartVersionService})가 수행한다.
     * 본문({@code LBL_PAYLOAD})은 여기서 싣지 않고 최종 선택된 1건만 재조회한다(CWE-770) — 그래서
     * 반환 타입이 경량 프로젝션이다.
     *
     * @return {@code (프레임, 회차, 스냅샷 PK)} 목록
     */
    @Query("select new kr.co.cudo.authoring.version.dto.SnapshotVersionRef("
            + "m.dataSrcSn, m.outputVerNo, m.lblVerSn) "
            + "from LsOutputVerSnpsh m "
            + "where m.dataRawSn = :rawSn and m.outputVerNo <= :verNo")
    List<SnapshotVersionRef> findRefsUpTo(@Param("rawSn") Long rawSn,
                                          @Param("verNo") Integer verNo);
}
