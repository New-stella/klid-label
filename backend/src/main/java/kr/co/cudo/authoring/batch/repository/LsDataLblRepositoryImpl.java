package kr.co.cudo.authoring.batch.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link LsDataLblRepositoryCustom} 구현 — {@code LBL_SN} 명시 지정 복원 삽입(단일 JDBC 배치).
 *
 * <p>Spring Data JPA 명명 규약({@code <RepositoryName>Impl})으로 {@link LsDataLblRepository} 에 병합된다.
 * {@code control} 영속 유닛 {@link EntityManager} 를 사용하며, 배치는 <b>세션의 동일 JDBC 커넥션·트랜잭션</b>
 * ({@link Session#doWork})에서 실행돼 호출자의 활성 트랜잭션에 원자적으로 참여한다
 * ({@link LsDataMetaRepositoryImpl} 과 동일 패턴).
 */
public class LsDataLblRepositoryImpl implements LsDataLblRepositoryCustom {

    /** 명시 PK 복원 삽입 — {@code ?} 파라미터 바인딩만(CWE-89). 중복 PK 는 건너뛴다. */
    private static final String INSERT_SQL =
            "INSERT INTO LS_DATA_LBL (LBL_SN, SRC_SN, LBL_TYPE_CD, LBL_ID, LBL_NM, POINT_CN, TRCK_ID, REG_DT) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) "
                    + "ON CONFLICT (LBL_SN) DO NOTHING";

    /** 삽입 성공 확정 — 이 프레임이 소유하게 된 LBL_SN 재조회(드라이버 배치 카운트 비의존). */
    private static final String OWNED_SQL =
            "SELECT LBL_SN FROM LS_DATA_LBL WHERE SRC_SN = ? AND LBL_SN = ANY(?)";

    /**
     * DEV_FIX-B(H1) — 시퀀스 동기화 구간 직렬화용 <b>advisory lock 키</b>(고정 상수).
     *
     * <p>{@code LS_DATA_LBL} IDENTITY 시퀀스 동기화 전용 예약 키다. 다른 용도로 재사용 금지.
     * 값 자체에 의미는 없고 "이 프로세스만 쓰는 고정 키"라는 점만 중요하다.
     */
    public static final long SEQ_SYNC_ADVISORY_LOCK_ID = 7_310_101L;

    /**
     * DEV_FIX-B(H1) — 시퀀스 동기화 직렬화 락.
     *
     * <p><b>왜 필요한가</b>: 아래 {@code SEQ_SYNC_SQL} 은 단일 SQL 문이지만 원자적이지 않다
     * (인자 평가 → {@code setval} 실행의 2단계). 2노드 Active-Active 에서 T1 이
     * {@code pg_sequence_last_value} 를 낮은 값으로 읽은 뒤 T2 가 더 높은 값으로 {@code setval} 을
     * 끝내면, T1 이 스테일한 낮은 값으로 {@code setval} 을 실행해 <b>시퀀스가 역행</b>한다. 시퀀스는
     * non-transactional 이라 T1 트랜잭션의 성패와 무관하게 영구 반영되고, 이후 {@code nextval()} 이
     * T2 가 이미 삽입한 PK 와 충돌(23505)한다.
     *
     * <p><b>왜 xact 락인가</b>: {@code pg_advisory_xact_lock} 은 트랜잭션 종료(커밋/롤백) 시 자동
     * 해제되므로 명시적 unlock 누락으로 인한 락 누수가 없다.
     *
     * <p><b>락 순서(데드락 방지)</b>: 이 락은 호출 시점 기준 <b>항상 마지막</b>에 취득한다 —
     * 프레임(LS_DATA_SRC) 행 락 → 라벨(LS_DATA_LBL) 행 락 → 시퀀스 advisory 락 순서다.
     *
     * <p><b>근거 정정(LOW)</b>: 구 주석은 "이 락을 잡은 상태에서 새로운 락을 요구하는 문장을 실행하지
     * 않는다(setval 1회 후 {@code doWork} 종료)"라고 했으나 <b>사실과 다르다</b>.
     * {@code pg_advisory_xact_lock} 은 {@code doWork} 종료가 아니라 <b>트랜잭션 종료까지</b> 유지되며,
     * 실제로 이 뒤에 같은 트랜잭션이 다른 락을 더 잡는다(예: 롤백 복원 흐름에서
     * {@code VersionService.activateRollbackTarget} 이 {@code LS_LABEL_VERSION} 행에 UPDATE 락을 취득).
     * 따라서 "leaf 라서 순환 불가"라는 근거는 성립하지 않는다.
     *
     * <p>데드락이 없는 실제 근거는 <b>취득 순서의 전역 단일성</b>이다: 이 advisory 락을 잡는 지점은
     * {@code insertRestoredWithExplicitIds} 의 시퀀스 동기화 구간 한 곳뿐이고 <b>고정 키 1개</b>
     * ({@link #SEQ_SYNC_ADVISORY_LOCK_ID})만 쓰므로
     * advisory 락끼리는 서로 대기할 수 없다. 그리고 이 락을 잡은 뒤 취득하는 후속 락들(라벨 버전 행 등)은
     * 어느 경로에서도 <b>advisory 락보다 먼저</b> 잡힌 상태로 이 락을 요구하지 않는다 — 즉 "행 락 → 고정
     * advisory 락 → 후속 행 락" 이라는 단일 방향 순서만 존재해 대기 그래프가 DAG 로 유지된다.
     */
    private static final String SEQ_SYNC_LOCK_SQL = "SELECT pg_advisory_xact_lock(?)";

    /**
     * IDENTITY 시퀀스 동기화 — 시퀀스명은 추측하지 않고 {@code pg_get_serial_sequence} 로 조회한다.
     * {@code GREATEST(MAX(LBL_SN), 현재 시퀀스 값)} 이라 <b>절대 낮추지 않는다</b>(낮추면 기존 행과 충돌).
     * 단, 이 "낮추지 않음" 보장은 위 advisory 락으로 동시 실행을 직렬화했을 때만 성립한다(H1).
     */
    private static final String SEQ_SYNC_SQL =
            "SELECT setval(pg_get_serial_sequence('ls_data_lbl', 'lbl_sn'), "
                    + "GREATEST(COALESCE((SELECT MAX(LBL_SN) FROM LS_DATA_LBL), 1), "
                    + "COALESCE(pg_sequence_last_value(pg_get_serial_sequence('ls_data_lbl', 'lbl_sn')), 1)), true)";

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    @Override
    public Set<Long> insertRestoredWithExplicitIds(Long srcSn, List<RestoreRow> rows) {
        if (srcSn == null || rows == null || rows.isEmpty()) {
            return Set.of();
        }
        // 네이티브 SQL 은 Hibernate auto-flush 대상이 아니므로, 선행 삭제/수정을 먼저 DB 에 반영한다
        // (같은 LBL_SN 을 삭제 후 재삽입하는 복원이 성립하려면 삭제가 먼저 도달해야 한다).
        entityManager.flush();

        Long[] requestedIds = rows.stream().map(RestoreRow::lblSn).toArray(Long[]::new);
        Set<Long> inserted = new LinkedHashSet<>();

        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                for (RestoreRow r : rows) {
                    ps.setLong(1, r.lblSn());
                    ps.setLong(2, srcSn);
                    ps.setString(3, r.lblTypeCd());
                    setNullableLong(ps, 4, r.labelId());
                    ps.setString(5, r.labelNm());
                    ps.setString(6, r.pointCn());
                    ps.setString(7, r.trackId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = connection.prepareStatement(OWNED_SQL)) {
                ps.setLong(1, srcSn);
                ps.setArray(2, connection.createArrayOf("bigint", requestedIds));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        inserted.add(rs.getLong(1));
                    }
                }
            }
            if (!inserted.isEmpty()) {
                // H1 — setval 은 "인자 평가 → 실행" 2단계라 동시 실행 시 시퀀스가 역행할 수 있다.
                //   동기화 구간 전체를 advisory 락으로 직렬화한다(트랜잭션 종료 시 자동 해제).
                //   ★ 이 락은 항상 마지막에 취득하고, 보유 상태에서 새 락을 요구하지 않는다(데드락 방지).
                try (PreparedStatement ps = connection.prepareStatement(SEQ_SYNC_LOCK_SQL)) {
                    ps.setLong(1, SEQ_SYNC_ADVISORY_LOCK_ID);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                    }
                }
                // 명시 PK 삽입 후 시퀀스가 그 값을 모르면 이후 라벨 저장이 전면 PK 충돌한다 — 1회만 동기화.
                try (PreparedStatement ps = connection.prepareStatement(SEQ_SYNC_SQL);
                     ResultSet rs = ps.executeQuery()) {
                    // setval 결과값은 사용하지 않는다 (실행만으로 동기화 완료).
                    rs.next();
                }
            }
        });
        return inserted;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }
}
