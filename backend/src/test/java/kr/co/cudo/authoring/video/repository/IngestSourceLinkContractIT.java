package kr.co.cudo.authoring.video.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.QLsDataIngest;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 영상↔인입 <b>연결 규칙의 두 표현이 동치</b>임을 DB 에서 대조하는 계약 테스트 (MED-1).
 *
 * <h3>왜 필요한가</h3>
 * <p>같은 규칙({@code i.RAW_SN = COALESCE(v.ORGNL_RAW_SN, v.RAW_SN)})이 기술 제약 때문에
 * <b>세 표현</b>으로 존재한다:
 * <ul>
 *   <li>{@link IngestSourceLink#SQL_LATERAL_JOIN} — native SQL(LATERAL)</li>
 *   <li>{@link IngestSourceLink#JPQL_MATCHES_SOURCE} — {@code @Query} 어노테이션 <b>문자열</b>
 *       (메서드 호출 불가라 상수로만 재사용 가능)</li>
 *   <li>{@link IngestSourceLink#matchesSourceOf} — Querydsl {@code BooleanExpression}</li>
 * </ul>
 * 텍스트 복제는 상수화로 없앴지만 <b>JPQL 표현과 Querydsl 표현은 서로 다른 코드</b>라, 한쪽만
 * 고치면 조용히 갈라진다. 이 테스트가 그 갈라짐을 실패로 만든다 — 규칙을 바꾸려면 두 표현을
 * <b>함께</b> 고쳐야 한다.
 *
 * <h3>대조 방식</h3>
 * <p>같은 데이터에 대해 두 표현으로 "인입 행이 매칭되는 영상"을 각각 조회해 결과 집합이 같은지 본다.
 * 경우의 수를 모두 심는다 — ①인입 있는 원본 ②인입 없는 원본 ③인입 있는 원본의 파생(부모 폴백 성립)
 * ④인입 없는 원본의 파생(폴백해도 매칭 없음).
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class IngestSourceLinkContractIT {

    private static final String CLIP_PREFIX = "LINKCONTRACT-IT-";

    @PersistenceContext(unitName = "control")
    private EntityManager em;

    @Autowired private VideoRepository videoRepository;

    private final JdbcTemplate jdbc;

    IngestSourceLinkContractIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    @DisplayName("JPQL_문자열_규칙과_QueryDSL_규칙이_동일한_영상집합을_고른다")
    void jpqlAndQuerydslExpressionsSelectTheSameVideos() {
        // given — 네 경우의 수를 모두 심는다
        LsDataRaw withIngest = saveOrigin("WITH");
        insertIngest(withIngest.getRawSn(), CLIP_PREFIX + "WITH");
        LsDataRaw withoutIngest = saveOrigin("WITHOUT");
        LsDataRaw derivedOfWith = saveDerived(withIngest);        // 부모 폴백 성립
        LsDataRaw derivedOfWithout = saveDerived(withoutIngest);  // 폴백해도 매칭 없음
        em.flush();

        // when — 같은 규칙의 두 표현으로 각각 조회
        List<Long> viaJpql = selectViaJpql();
        List<Long> viaQuerydsl = selectViaQuerydsl();

        // then — ★두 표현의 결과가 완전히 같아야 한다(순서 무관, 집합 동치)
        assertThat(viaJpql)
                .as("JPQL 문자열 규칙과 QueryDSL 규칙이 갈라졌다 — IngestSourceLink 의 두 표현을"
                        + " 함께 갱신해야 한다")
                .containsExactlyInAnyOrderElementsOf(viaQuerydsl);

        // then — 규칙 자체가 의도대로 동작하는지도 함께 고정한다(둘 다 틀렸는데 같기만 한 경우 배제)
        assertThat(viaJpql)
                .as("인입 있는 원본과 그 파생(부모 폴백)이 매칭돼야 한다")
                .contains(withIngest.getRawSn(), derivedOfWith.getRawSn())
                .doesNotContain(withoutIngest.getRawSn(), derivedOfWithout.getRawSn());
    }

    @Test
    @DisplayName("파생영상은_부모_인입행으로_매칭되고_자기_인입행은_없다")
    void derivedMatchesParentIngestWithoutOwningOne() {
        // given
        LsDataRaw origin = saveOrigin("PARENT");
        insertIngest(origin.getRawSn(), CLIP_PREFIX + "PARENT");
        LsDataRaw derived = saveDerived(origin);
        em.flush();

        // then — 파생은 자기 인입 행이 없다(불변식). 그럼에도 1단계 폴백으로 매칭된다.
        assertThat(countIngestOf(derived.getRawSn())).isZero();
        assertThat(selectViaQuerydsl()).contains(derived.getRawSn());
        assertThat(selectViaJpql()).contains(derived.getRawSn());
    }

    // ------------------------------------------------------------------ 두 표현

    /** {@link IngestSourceLink#JPQL_MATCHES_SOURCE} 를 <b>그대로</b> 끼워 실행한다. */
    private List<Long> selectViaJpql() {
        return em.createQuery(
                        "SELECT v.rawSn FROM LsDataRaw v"
                                + " WHERE v.vmsClipId LIKE :prefix"
                                + "   AND EXISTS (SELECT 1 FROM LsDataIngest i WHERE "
                                + IngestSourceLink.JPQL_MATCHES_SOURCE + ")", Long.class)
                .setParameter("prefix", CLIP_PREFIX + "%")
                .getResultList();
    }

    /** {@link IngestSourceLink#matchesSourceOf} 를 <b>그대로</b> 써서 실행한다. */
    private List<Long> selectViaQuerydsl() {
        QLsDataRaw v = QLsDataRaw.lsDataRaw;
        QLsDataIngest i = QLsDataIngest.lsDataIngest;
        return new JPAQueryFactory(em)
                .select(v.rawSn)
                .from(v)
                .where(v.vmsClipId.like(CLIP_PREFIX + "%"),
                        com.querydsl.jpa.JPAExpressions.selectOne()
                                .from(i)
                                .where(IngestSourceLink.matchesSourceOf(i, v))
                                .exists())
                .fetch();
    }

    // ---------------------------------------------------------------- fixtures

    private LsDataRaw saveOrigin(String suffix) {
        return videoRepository.saveAndFlush(LsDataRaw.createFromIngest(
                CLIP_PREFIX + suffix, "CCTV-LINK-" + suffix, "EV01000101", "11110",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/" + CLIP_PREFIX + suffix + ".mp4",
                LocalDateTime.of(2026, 5, 10, 0, 0), 30));
    }

    private LsDataRaw saveDerived(LsDataRaw parent) {
        return videoRepository.saveAndFlush(LsDataRaw.createFromResolution(
                parent, "/var/raw/deriv-" + parent.getRawSn() + ".mp4", "RESL_480P"));
    }

    private long countIngestOf(Long rawSn) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_INGEST WHERE RAW_SN = ?", Long.class, rawSn);
        return n == null ? 0L : n;
    }

    private void insertIngest(Long rawSn, String clipId) {
        jdbc.update("INSERT INTO LS_DATA_INGEST "
                        + "(RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, "
                        + " SRC_TYPE, RCPTN_DT, PROC_STTS_CD, CCTV_NM) "
                        + "VALUES (?, ?, 'CCTV-LINK', 'f.mp4', '/var/raw/f.mp4', 'ORIGINAL', "
                        + "        CURRENT_TIMESTAMP, 'DONE', 'CCTV 이름')",
                rawSn, clipId + "-ING");
    }
}
