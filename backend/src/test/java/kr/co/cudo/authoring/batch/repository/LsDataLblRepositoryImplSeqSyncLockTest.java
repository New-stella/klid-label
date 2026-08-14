package kr.co.cudo.authoring.batch.repository;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEV_FIX-B(H1) — IDENTITY 시퀀스 동기화가 <b>advisory lock 으로 직렬화</b>되는지 검증.
 *
 * <p>구 구현의 {@code setval(seq, GREATEST(MAX(LBL_SN), pg_sequence_last_value(seq)), true)} 는 단일 SQL
 * 문이지만 원자적이지 않다("인자 평가 → 함수 실행" 2단계). 2노드 Active-Active 에서 T1 이 스테일한 낮은
 * 값을 읽은 사이 T2 가 더 높은 값으로 {@code setval} 을 끝내면, T1 이 시퀀스를 <b>뒤로 밀어</b> 이후
 * {@code nextval()} 이 이미 삽입된 PK 와 충돌(23505)한다. 시퀀스는 non-transactional 이라 롤백되지도 않는다.
 *
 * <p>2노드 레이스 자체는 단위테스트로 재현할 수 없으므로, <b>배선</b>(락 SQL 이 setval 직전에 실행되고
 * 락을 잡은 뒤에는 새 락을 요구하는 문장을 실행하지 않음)을 실행 순서로 단언한다.
 */
class LsDataLblRepositoryImplSeqSyncLockTest {

    private static final Long SRC_SN = 7L;
    private static final Long LBL_SN = 42L;

    private LsDataLblRepositoryImpl repository;
    private EntityManager entityManager;
    private Connection connection;
    /** prepareStatement 로 넘어온 SQL 을 실행 순서대로 담는다. */
    private final List<String> preparedSql = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        repository = new LsDataLblRepositoryImpl();
        entityManager = mock(EntityManager.class);
        Session session = mock(Session.class);
        connection = mock(Connection.class);
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);

        when(entityManager.unwrap(Session.class)).thenReturn(session);
        // doWork 로 넘어온 Work 를 mock Connection 으로 즉시 실행한다.
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(0, Work.class).execute(connection);
            return null;
        }).when(session).doWork(org.mockito.ArgumentMatchers.any(Work.class));

        when(connection.createArrayOf(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(Array.class));
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            preparedSql.add(sql);
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            // OWNED_SQL 만 "삽입 성공 1건" 을 돌려준다(시퀀스 동기화 조건 성립).
            if (sql.startsWith("SELECT LBL_SN")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getLong(1)).thenReturn(LBL_SN);
            } else {
                when(rs.next()).thenReturn(true);
            }
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        });
    }

    private static List<LsDataLblRepositoryCustom.RestoreRow> rows() {
        return List.of(new LsDataLblRepositoryCustom.RestoreRow(
                LBL_SN, "BBOX", null, "person", "[[0.0,0.0],[1.0,1.0]]", null,
                null, null, null));
    }

    @Test
    @DisplayName("시퀀스_동기화가_advisory_lock_으로_직렬화된다")
    void seqSyncSerializedByAdvisoryLock() throws Exception {
        // given / when
        Set<Long> inserted = repository.insertRestoredWithExplicitIds(SRC_SN, rows());

        // then — 삽입 확정 1건.
        assertThat(inserted).containsExactly(LBL_SN);

        // 1) advisory lock SQL 이 setval SQL <b>직전</b>에 준비·실행된다.
        int lockIdx = indexOfSqlContaining("pg_advisory_xact_lock");
        int setvalIdx = indexOfSqlContaining("setval(");
        assertThat(lockIdx).as("advisory lock SQL 이 실행되어야 한다").isGreaterThanOrEqualTo(0);
        assertThat(setvalIdx).as("setval SQL 이 실행되어야 한다").isGreaterThan(lockIdx);
        // 2) 락 취득 후 실행되는 문장은 setval 하나뿐 — 락 보유 상태에서 새 락을 요구하지 않는다(데드락 방지).
        assertThat(preparedSql.subList(lockIdx + 1, preparedSql.size())).containsExactly(
                preparedSql.get(setvalIdx));

        // 3) 락 키는 고정 상수를 파라미터 바인딩으로 전달한다(CWE-89 — 문자열 연결 금지).
        InOrder order = inOrder(connection);
        order.verify(connection).prepareStatement(org.mockito.ArgumentMatchers.contains("pg_advisory_xact_lock"));
        order.verify(connection).prepareStatement(org.mockito.ArgumentMatchers.contains("setval("));
        assertThat(preparedSql.get(lockIdx)).contains("?").doesNotContain(
                String.valueOf(LsDataLblRepositoryImpl.SEQ_SYNC_ADVISORY_LOCK_ID));
    }

    @Test
    @DisplayName("삽입이_0건이면_락도_시퀀스_동기화도_하지_않는다")
    void noLockWhenNothingInserted() throws Exception {
        // given — OWNED_SQL 이 0건(전부 PK 충돌로 미삽입).
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            preparedSql.add(sql);
            PreparedStatement ps = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(false);
            when(ps.executeQuery()).thenReturn(rs);
            return ps;
        });

        // when
        Set<Long> inserted = repository.insertRestoredWithExplicitIds(SRC_SN, rows());

        // then — 불필요한 전역 직렬화(락 경합)를 만들지 않는다.
        assertThat(inserted).isEmpty();
        assertThat(indexOfSqlContaining("pg_advisory_xact_lock")).isEqualTo(-1);
        assertThat(indexOfSqlContaining("setval(")).isEqualTo(-1);
        verify(entityManager).flush();
    }

    @Test
    @DisplayName("복원_삽입은_파라미터_바인딩만_사용한다")
    void restoreInsertUsesParameterBindingOnly() throws Exception {
        repository.insertRestoredWithExplicitIds(SRC_SN, rows());

        // CWE-89 — 값이 SQL 문자열에 섞이지 않는다(전부 ? 바인딩).
        String insertSql = preparedSql.get(0);
        // V6 — 생산이력 3컬럼(AUTO_LBL_YN·CONF_SCORE·LBL_SRC_CD)이 같은 INSERT 로 들어와 자리표시자가 7→10 개다.
        assertThat(insertSql).startsWith("INSERT INTO LS_DATA_LBL")
                .contains("VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())")
                .doesNotContain("person");
    }

    private int indexOfSqlContaining(String fragment) {
        for (int i = 0; i < preparedSql.size(); i++) {
            if (preparedSql.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }
}
