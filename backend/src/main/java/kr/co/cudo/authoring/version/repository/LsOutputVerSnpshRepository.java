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
     * <h3>★한 번 쓰인 회차 매핑은 <b>불변</b>이다 (2026-08-12 사용자 확정, 구속)</h3>
     * 각 회차는 이전 회차들과 간섭해선 안 된다 — 그래야 검수 완료 시점마다 만들어진 데이터마트가 각각
     * 유지된다. 이 매핑이 "회차 N 의 내용은 어느 스냅샷이었는가"의 <b>단일 원천</b>이므로, 나중 쓰기가
     * 과거 회차의 행을 덮으면 그 회차의 산출물 기준이 소급해 바뀐다. 그래서 {@code DO NOTHING} 으로
     * <b>DB 문장 자체가</b> 불변을 강제한다.
     *
     * <p><b>구 동작({@code DO UPDATE} — 재마감 시 최신 ACTIVE 스냅샷으로 덮음)은 폐기됐다.</b> 그 근거는
     * *"산출은 실패 후 같은 {@code OUTPUT_VER_NO} 로 재시도된다"* 였는데 <b>채번을 따라가면 성립하지
     * 않는다</b>: 실패 회수({@code DatasetExportFailureRecoverer})는 {@code runApprovalAsync} 로 산출을
     * <b>처음부터 재진입</b>하고, 그 안의 {@code DatasetExportTxService.insertNextVersion} 이
     * {@code countByDataRawSn() + 1} 로 <b>새 번호</b>를 받는다(실패 행도 건수에 남는다). 즉 같은 번호로
     * 다시 마감되는 프로덕션 경로를 찾지 못했다.
     *
     * <p>다만 "찾지 못했다"는 "없다"가 아니므로, 충돌로 삽입이 건너뛰어지면 호출부
     * ({@code OutputVersionStamper.stamp})가 <b>WARN 으로 가시화</b>한다 — 그 경로가 실재하면 운영 로그에
     * 드러나고, 없으면 아무 소리도 나지 않는다. 조용한 stale 로 남기지 않기 위한 관측이다.
     *
     * <h3>왜 네이티브 INSERT … SELECT 인가</h3>
     * <ul>
     *   <li><b>CWE-770</b> — 프레임 수만큼의 엔티티(스냅샷 본문 최대 10MB/건)를 힙에 올리지 않는다.
     *       DB 안에서 끝난다.</li>
     *   <li><b>멱등</b> — UK{@code (DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO)} 충돌을 예외 대신
     *       {@code DO NOTHING} 으로 흡수한다. 예외를 던지면 산출 마감 트랜잭션 전체가 롤백되어 <b>산출은
     *       성공했는데 마감이 안 되는</b> 상태가 된다. 기존 행은 값도 {@code REG_DT}(그 매핑을 기록한
     *       시각)도 건드리지 않으므로 재실행이 dead tuple·불필요한 행 잠금을 만들지 않는다.</li>
     *   <li><b>{@code DISTINCT ON}</b> — 한 프레임에 ACTIVE 행이 둘 이상인 오염 상태에서도 충돌 키가
     *       중복되지 않게 한다. {@code LBL_VERSION_SN DESC} 로 <b>가장 최근 스냅샷</b>을 결정적으로
     *       고른다 — {@code StartVersionService.resolveTargets} 의 동률 처리(행 PK 가 큰 쪽)와 같은 축이다.
     *       ⚠ {@code DO NOTHING} 이라 21000("cannot affect row a second time")은 나지 않지만, 이 절은
     *       <b>어느 스냅샷이 기록되는지를 결정론으로 고정</b>하는 별개 역할이라 제거하지 말 것.</li>
     *   <li>파라미터 바인딩만 사용한다(CWE-89 — 문자열 연결 없음).</li>
     * </ul>
     *
     * <p>{@code DATA_SRC_SN IS NULL} 인 레거시 영상 스코프 스냅샷(구 비식별 신고 경로)은 제외한다 —
     * 프레임 대응이 아니라 매핑 대상이 아니다.
     *
     * @return <b>새로 기록된</b> 매핑 수. 이미 그 회차의 매핑이 있는 프레임은 건너뛰므로, 기대 건수보다
     *         작으면 호출부가 WARN 으로 알린다(재마감·중복 마감 신호)
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
            ON CONFLICT (DATA_RAW_SN, DATA_SRC_SN, OUTPUT_VER_NO) DO NOTHING
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
