package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★★<b>영상 제외의 경계</b> 회귀 가드 — 화면 목록에서만 빠지고 <b>그 밖은 전부 그대로다</b>.
 * [@design ADR-069] [@design AC-1121]
 *
 * <h2>이 클래스의 목적은 「되는 것」이 아니라 「안 되는 것」이다</h2>
 * <p>제외 결정의 무게는 「무엇을 하는가」보다 <b>「어디까지만 하는가」</b>에 있다. 그런데 경계를 적어
 * 두기만 해서는, 다음 사람이 「목록에서 빼는데 배치와 관제에도 빼는 것이 일관되지 않나」라고 생각하는
 * 것을 막지 못한다 — 그 생각은 자연스럽다. <b>막는 것은 설명이 아니라 어겼을 때 실패하는 검증</b>이다.
 *
 * <h2>판정 방법 — 제외 직전 기준값과 제외 후 값을 견준다</h2>
 * <p>「영향을 주지 않는다」는 서술만으로는 시험이 되지 않으므로, 무엇을 관측해 같음을 판정하는지를
 * 항목마다 적었다.
 *
 * <h2>여기서 다루지 못하는 경계는 소스 스캔이 덮는다</h2>
 * <p>관제 통지 조립 · 학습데이터 산출물 · 콘텐츠 해시 · 통계 · 포털은 그 경로를 이 IT 에서 실제로
 * 돌리기 어렵다(승인·동결·산출까지 필요). 그쪽은
 * {@code VideoExclusionBoundarySourceGuardTest} 가 <b>그 패키지에 제외 술어가 한 글자도 없음</b>을
 * 기계로 고정한다 — 두 가드는 서로를 대신하지 못한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VideoExclusionBoundaryIT {

    /** 이 시험 전용 검수자 — 인가의 진실원은 {@code LS_USER_ROLE} 이라 직접 심고 반드시 지운다. */
    private static final long REVIEWER_NO = 969_300_031L;

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private kr.co.cudo.authoring.common.security.UserRoleResolver userRoleResolver;

    /** 저작도구(control) 데이터소스 — 이 저장소는 {@code JdbcTemplate} 빈을 두지 않는다. */
    private final JdbcTemplate jdbcTemplate;
    /** 배치 클레임은 벌크 UPDATE 라 트랜잭션이 필요하다 — 시험 메서드는 트랜잭션 밖이므로 직접 연다. */
    private final org.springframework.transaction.support.TransactionTemplate tx;

    VideoExclusionBoundaryIT(
            @Qualifier("controlDataSource") DataSource dataSource,
            @Qualifier("controlTransactionManager")
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private Long rawSn;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", REVIEWER_NO);
        userRoleResolver.evict(REVIEWER_NO);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(
                secret, String.valueOf(REVIEWER_NO), "REVIEWER", "INTERNAL", issuer, 60);
        jdbcTemplate.update("DELETE FROM LS_USER_ROLE WHERE USER_NO = ?", REVIEWER_NO);
        jdbcTemplate.update(
                "INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES (?, 'REVIEWER', CURRENT_TIMESTAMP)",
                REVIEWER_NO);
        userRoleResolver.evict(REVIEWER_NO);
        rawSn = videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-bnd-" + System.nanoTime(), "bnd" + System.nanoTime(), "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn();
    }

    private void exclude() throws Exception {
        mockMvc.perform(post("/v1/videos/" + rawSn + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(APPLICATION_JSON)
                        .content("{\"reason\":\"경계 시험\"}"))
                .andExpect(status().isOk());
    }

    /**
     * ★<b>배치 파이프라인</b> — 진행도 회수도 제외 전후가 같다.
     *
     * <p>회수 스윕의 1차 필터가 {@code MDFCN_DT} 라, 제외가 그 값을 밀면 고착된 영상이 <b>방금 선점된
     * 것처럼 보여 후보 집합에서 빠진다</b>. 그러면 그 영상은 영영 회수되지 않는다 — 조용히 새는 축이라
     * 후보 집합을 <b>제외 전후로 견주어</b> 판정한다.
     */
    @Test
    @DisplayName("★★경계_배치_제외해도_단계_선점이_되고_회수_스윕_후보집합이_그대로다")
    void batchPipelineIsUntouched() throws Exception {
        // given: 고착 후보가 되도록 PROCESSING + 오래된 갱신시각으로 만든다.
        jdbcTemplate.update("UPDATE ls_data_raw SET data_stts_cd = ?, mdfcn_dt = ? WHERE raw_sn = ?",
                LsDataRaw.DATA_STTS_PROCESSING, LocalDateTime.now().minusHours(3), rawSn);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        List<Long> before = videoRepository.findStaleProcessingRawSns(
                LsDataRaw.DATA_STTS_PROCESSING, cutoff, 0L, PageRequest.of(0, 5000));
        assertThat(before).as("기준값 자체가 성립해야 시험이 의미를 갖는다").contains(rawSn);

        // when
        exclude();

        // then: 회수 스윕이 고르는 후보 집합이 제외 전후 같다 — 제외된 영상이 빠지지 않는다.
        List<Long> after = videoRepository.findStaleProcessingRawSns(
                LsDataRaw.DATA_STTS_PROCESSING, cutoff, 0L, PageRequest.of(0, 5000));
        assertThat(after)
                .as("제외가 회수 스윕 후보 집합을 바꾸면 고착된 영상이 영영 회수되지 않는다")
                .containsExactlyElementsOf(before);

        // then: 단계 진행(원자 클레임)도 제외와 무관하게 성립한다.
        jdbcTemplate.update("UPDATE ls_data_raw SET data_stts_cd = ? WHERE raw_sn = ?",
                LsDataRaw.DATA_STTS_MARKING_READY, rawSn);
        Integer claimed = tx.execute(s ->
                videoRepository.claimForProcessing(rawSn, LsDataRaw.DATA_STTS_PROCESSING));
        assertThat(claimed)
                .as("제외된 영상도 배치 단계를 종전과 똑같이 흐른다")
                .isEqualTo(1);
    }

    /**
     * ★<b>데이터마트 조회 뷰</b> — 관제가 보는 계약면에 제외 조건이 붙지 않는다.
     *
     * <p>근거는 ADR-037(검수 완료·통지 건에 대한 관제 접근 무조건 보장)이다. 여기서 한 건이라도
     * 사라지면 그 결정이 깨진 것이다. 승인·동결까지 만들지 않고 <b>뷰 정의 자체</b>를 읽어 판정하는
     * 이유는, 그것이 위반이 나타날 유일한 자리이자 데이터 상태와 무관하게 결정적이기 때문이다.
     */
    @Test
    @DisplayName("★★경계_데이터마트_뷰_4종의_정의에_제외_조건이_한_글자도_없다")
    void datamartViewsDoNotFilterByExclusion() {
        List<String> views = List.of("v_completed_video", "v_completed_frame",
                "v_completed_label_change", "v_completed_meta");
        for (String view : views) {
            String def = jdbcTemplate.queryForObject(
                    "SELECT pg_get_viewdef(to_regclass(?), true)", String.class, view);
            assertThat(def)
                    .as("데이터마트 뷰 %s 가 존재해야 한다(관제 계약면)", view)
                    .isNotBlank();
            assertThat(def.toLowerCase(Locale.ROOT))
                    .as("★%s 에 제외 조건이 붙었다 — 관제가 보던 행이 예고 없이 사라져 ADR-037"
                            + "(검수 완료·통지 건의 관제 접근 무조건 보장)을 정면으로 깬다", view)
                    .doesNotContain("excl_yn");
        }
    }

    /**
     * ★<b>라벨링 화면 진입은 막지 않는다</b> — 알고 받아들인 비대칭이다.
     *
     * <p>막으면 「제외분만 보기」에서 상세로 들어가는 길까지 함께 닫힌다. 결함으로 다시 보고하지 말 것.
     */
    @Test
    @DisplayName("★경계_제외된_영상도_상세_진입이_열린다_인지수용한_비대칭")
    void detailStaysAccessible() throws Exception {
        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());

        exclude();

        mockMvc.perform(get("/v1/videos/" + rawSn)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
    }

    /**
     * ★<b>제외를 켰다 끄는 것만으로는 영상 행의 다른 어떤 칸도 바뀌지 않는다.</b>
     *
     * <p>제외는 가시성 축이라 개인정보 성질 축·배치 단계 축·갱신시각을 건드리지 않는다. 특히
     * {@code MDFCN_DT} 는 회수 스윕의 필터라 밀리면 위 배치 경계가 깨진다.
     */
    @Test
    @DisplayName("★★경계_제외_토글은_영상_행의_다른_칸을_하나도_바꾸지_않는다")
    void togglingChangesOnlyTheExclusionFlag() throws Exception {
        List<?> before = rowSnapshot();

        exclude();
        assertThat(rowSnapshot()).as("제외가 다른 칸을 밀었다").isEqualTo(before);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/v1/videos/" + rawSn + "/exclusion")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk());
        assertThat(rowSnapshot()).as("복원이 다른 칸을 밀었다").isEqualTo(before);
    }

    /**
     * 제외여부를 <b>뺀</b> 영상 행 전체를 값 목록으로 뜬다.
     *
     * <p>칸 이름을 손으로 나열하지 않는다 — 나열하면 그 목록이 두 번째 진실원이 되어 칸이 늘었을 때
     * 조용히 검사 밖으로 빠진다. 시스템 카탈로그에서 칸 목록을 <b>생성</b>한다.
     */
    private List<?> rowSnapshot() {
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + " WHERE table_schema = current_schema() AND table_name = 'ls_data_raw'"
                        + "   AND column_name <> 'excl_yn' ORDER BY ordinal_position",
                String.class);
        assertThat(columns).as("영상 원장 칸 목록을 읽어야 한다").isNotEmpty();
        String select = columns.stream().reduce((a, b) -> a + ", " + b).orElseThrow();
        return jdbcTemplate.queryForList(
                "SELECT " + select + " FROM ls_data_raw WHERE raw_sn = ?", rawSn);
    }
}
