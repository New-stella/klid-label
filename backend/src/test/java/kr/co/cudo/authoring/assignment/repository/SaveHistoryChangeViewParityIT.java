package kr.co.cudo.authoring.assignment.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.version.entity.QLsDataLblHstry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>드리프트 가드</b> — '작업중' 판정의 라벨 델타 판별식(Java)과 데이터마트 뷰
 * {@code V_COMPLETED_LABEL_CHANGE}(V139)의 SQL 술어가 <b>같은 데이터에 같은 답</b>을 내는지 고정한다.
 *
 * <p><b>왜 필요한가</b> — 두 판별식은 같은 테이블({@code LS_DATA_LBL_HSTRY})에서 "라벨 본문이 실제로
 * 바뀐 저장 이벤트" 를 골라내는 <b>동일한 개념</b>인데, 한쪽은 QueryDSL 표현식
 * ({@link AssignmentQueryRepository#labelDeltaExists})이고 다른 쪽은 마이그레이션 SQL 이라 컴파일러가
 * 묶어 주지 못한다. 뷰 술어만 바뀌면(예: 임계값 변경, AND 로 교체) 작업목록의 '작업중' 판정이
 * <b>조용히</b> 관제 데이터마트와 달라진다.
 *
 * <p><b>텍스트 비교가 아니라 결과 비교</b>인 이유 — SQL 문자열을 대조하면 공백·주석 변경만으로도
 * 깨지고(거짓 실패), 반대로 의미가 같은 재작성(예: {@code COALESCE} 제거)은 통과시킬 수 없다.
 * 여기서는 <b>같은 행 집합</b>에 두 판별식을 각각 적용해 결과가 일치하는지 본다 — 뷰 술어가 의미상
 * 달라지면 반드시 실패한다.
 *
 * <p>표본은 경계(0/0/0 · 각 축 단독 1건 · 복합 · 큰 값)를 모두 덮는다. {@code ADD_CNT/MDFCN_CNT/DEL_CNT}
 * 는 스키마상 {@code NOT NULL DEFAULT 0}(V115)이라 null 표본은 넣을 수 없다 — 뷰의 {@code COALESCE} 는
 * 방어적 표기다.
 *
 * <p>공유 Testcontainer 오염 방지를 위해 시드는 고유 RAW_SN 으로 넣고 {@link #cleanup()} 에서 역순 제거한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class SaveHistoryChangeViewParityIT {

    private final JdbcTemplate jdbc;

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    private final List<Long> seededRawSns = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong frameSeq = new java.util.concurrent.atomic.AtomicLong();

    SaveHistoryChangeViewParityIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATA_LBL_HSTRY WHERE SRC_SN IN "
                    + "(SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ?)", rawSn);
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
        seededRawSns.clear();
    }

    @Test
    @DisplayName("작업중_판별식은_V139_뷰_술어와_같은_행을_고른다")
    void javaPredicateAgreesWithDatamartViewPredicate() {
        // given: 검수 승인(APPROVED) 영상 1건 — 뷰의 APPROVED 게이트를 상수로 고정해 판별식 축만 남긴다.
        long rawSn = seedApprovedVideo();

        // 라벨 델타 (add, mdfcn, del) → 이 저장 이벤트가 "변경 있음" 인지의 기대값
        Map<Long, Boolean> expected = new LinkedHashMap<>();
        expected.put(seedSaveEvent(rawSn, 0, 0, 0), false);   // 롤백/감사 이벤트 — 라벨 본문 무변경
        expected.put(seedSaveEvent(rawSn, 1, 0, 0), true);    // 추가만
        expected.put(seedSaveEvent(rawSn, 0, 1, 0), true);    // 수정만
        expected.put(seedSaveEvent(rawSn, 0, 0, 1), true);    // 삭제만
        expected.put(seedSaveEvent(rawSn, 2, 3, 4), true);    // 복합
        expected.put(seedSaveEvent(rawSn, 0, 0, 0), false);   // 0건 이벤트가 여러 건이어도 동일

        List<Long> srcSns = List.copyOf(expected.keySet());

        // when
        List<Long> viewRows = jdbc.queryForList(
                "SELECT SRC_SN FROM V_COMPLETED_LABEL_CHANGE WHERE RAW_SN = ? ORDER BY SRC_SN",
                Long.class, rawSn);
        List<Long> javaRows = selectBySaveHistoryPredicate(srcSns);

        // then: ① 두 판별식이 정확히 같은 행을 고른다(뷰 술어가 바뀌면 여기서 깨진다)
        assertThat(javaRows)
                .as("Java '작업중' 판별식과 V_COMPLETED_LABEL_CHANGE(V139) 술어가 서로 다른 행을 골랐다 — "
                        + "작업목록의 '작업중' 표시가 관제 데이터마트의 변경점 정의와 어긋난다")
                .containsExactlyInAnyOrderElementsOf(viewRows);

        // ② 표본이 실제로 판별력을 가진다(둘 다 전건/0건이면 ①이 무의미하게 통과한다)
        List<Long> expectedTrue = expected.entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        assertThat(viewRows).containsExactlyInAnyOrderElementsOf(expectedTrue);
        assertThat(javaRows).hasSize(4).isNotEqualTo(srcSns);
    }

    /** 델타 축이 하나라도 양수면 "변경 있음" — 합 &gt; 0 과 OR 분해가 동치임을 각 축 단독으로 고정한다. */
    @Test
    @DisplayName("델타가_한_축이라도_양수면_양쪽_모두_변경있음으로_판정한다")
    void singleAxisDeltaIsChangeOnBothSides() {
        long rawSn = seedApprovedVideo();
        long addOnly = seedSaveEvent(rawSn, 5, 0, 0);
        long mdfcnOnly = seedSaveEvent(rawSn, 0, 5, 0);
        long delOnly = seedSaveEvent(rawSn, 0, 0, 5);
        long none = seedSaveEvent(rawSn, 0, 0, 0);

        List<Long> viewRows = jdbc.queryForList(
                "SELECT SRC_SN FROM V_COMPLETED_LABEL_CHANGE WHERE RAW_SN = ?", Long.class, rawSn);
        List<Long> javaRows = selectBySaveHistoryPredicate(List.of(addOnly, mdfcnOnly, delOnly, none));

        assertThat(viewRows).containsExactlyInAnyOrder(addOnly, mdfcnOnly, delOnly);
        assertThat(javaRows).containsExactlyInAnyOrderElementsOf(viewRows);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 프로덕션 판별식({@link AssignmentQueryRepository#labelDeltaExists})을 <b>그대로</b> 적용해
     * 대상 이력 행을 고른다 — 테스트가 조건을 다시 쓰면 가드로서 의미가 없다.
     */
    private List<Long> selectBySaveHistoryPredicate(List<Long> srcSns) {
        QLsDataLblHstry history = QLsDataLblHstry.lsDataLblHstry;
        return new JPAQueryFactory(entityManager)
                .select(history.srcSn)
                .from(history)
                .where(history.srcSn.in(srcSns),
                        AssignmentQueryRepository.labelDeltaExists(history))
                .fetch();
    }

    /** 검수 승인(APPROVED) 영상 1건 — 뷰 노출 조건. */
    private long seedApprovedVideo() {
        long nano = System.nanoTime();
        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "DATA_STTS_CD, REG_DT) "
                        + "VALUES (?, ?, 'EVT01', '1111000000', 'PRVC', 'Y', 'Y', ?, ?, 30, 'COMPLETED', ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                "CLIP-PARITY-" + nano, "CCTV-PARITY-" + nano, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', ?, 1)", rawSn, LocalDateTime.now());
        return rawSn;
    }

    /**
     * 프레임 1건 + 그 프레임의 저장 이벤트 1건을 만든다.
     * 프레임을 이벤트마다 새로 만드는 이유는 SRC_SN 으로 두 판별식의 결과를 1:1 대조하기 위해서다.
     *
     * @return 생성된 프레임 PK (= 이 저장 이벤트의 식별자로 사용)
     */
    private long seedSaveEvent(long rawSn, int addCnt, int mdfcnCnt, int delCnt) {
        // UK (RAW_SN, FRM_NO) — 영상은 테스트마다 새로 만들므로 인스턴스 카운터로 충분하다.
        long frameNo = frameSeq.incrementAndGet();
        Long srcSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, SHT_DT, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING SRC_SN",
                Long.class,
                rawSn, frameNo, "/nas/frames/raw/" + rawSn + "/" + frameNo + ".jpg",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        jdbc.update(
                "INSERT INTO LS_DATA_LBL_HSTRY (SRC_SN, REG_DT, REG_ID, ADD_CNT, MDFCN_CNT, DEL_CNT, CHG_DTL_CN) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                srcSn, LocalDateTime.now(), "100", addCnt, mdfcnCnt, delCnt, "[]");
        return srcSn;
    }
}
