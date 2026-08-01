package kr.co.cudo.authoring.augment;

import com.jayway.jsonpath.JsonPath;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>{@code restoreEligible}(GET) ↔ {@code POST /restore}(실제 수용) 결합 검증</b> — DEV_FIX 2차 ③.
 *
 * <h2>왜 "짝지어" 단언해야 하는가 (이 결함 클래스의 유일한 구조적 가드)</h2>
 * <p>1차 DEV_FIX 가 만든 {@code restoreEligible} 테스트 7건은 <b>GET 만</b> 호출했다. 두 판정
 * (화면 가시성 · 복구 API 수용)은 <b>주석으로만</b> 결합돼 있어서, 복구 사전조건이 바뀌어도 조회 쪽을
 * 함께 고치지 않으면 <b>테스트는 전부 초록인 채 죽은 버튼이 되살아난다</b>. 실제로 그랬다 —
 * "열린 표식 있음 → 복구 가능" 이라는 계산은 {@code reopen} 가드(검수 행이 {@code REJECTED} 여야
 * 한다)를 반영하지 않아 409 죽은 버튼을 남겼고(LOW-②), "최신 검수 행" 정의가 조회·복구에 각각
 * 있어서 중복 행 형상에서 서로 다른 행을 봤다(MEDIUM-①).
 *
 * <p>그래서 이 클래스는 형상마다 <b>같은 테스트 안에서</b> GET 의 {@code restoreEligible} 과
 * POST 의 실제 응답을 함께 단언한다 — {@code true} 면 200, {@code false} 면 4xx. 새 사전조건이
 * 생기면 <b>여기부터 깨진다</b>.
 *
 * <h2>테스트 위생</h2>
 * <p>공유 PostgreSQL(Testcontainers) 오염 방지 — 시드 clipId 는 {@code AUGREL-} 고유 접두사를 쓰고
 * {@code @AfterEach} 가 <b>자기 시드 PK 만</b> FK 역순으로 정리한다. 라벨 마스터
 * ({@code LS_LABEL}·{@code LS_LABEL_ATTR})는 시드하지도 삭제하지도 않는다(전 프로젝트 공유 자산).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AugmentRestoreEligibilityCouplingTest {

    private static final String DISCARD_REASON = "결과물 품질 미달";
    private static final String RESTORE_REASON = "오판이라 되돌립니다";

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugDscdRepository discardRepository;
    @Autowired private LsDataAugRvwRepository reviewRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private final JdbcTemplate jdbc;

    private final List<Long> rawSns = new ArrayList<>();
    private final List<Long> dataAugSns = new ArrayList<>();

    private String reviewerToken;

    AugmentRestoreEligibilityCouplingTest(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setup() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
    }

    /** 자기 시드만 FK 역순으로 되돌린다(파생 → 부모 — {@code ORGNL_RAW_SN} 은 비-CASCADE). */
    @AfterEach
    void cleanup() {
        for (Long dataAugSn : dataAugSns) {
            jdbc.update("DELETE FROM LS_DATA_AUG_DSCD WHERE DATA_AUG_SN = ?", dataAugSn);
            jdbc.update("DELETE FROM LS_DATA_AUG_RVW WHERE DATA_AUG_SN = ?", dataAugSn);
            jdbc.update("DELETE FROM LS_DATA_AUG WHERE DATA_AUG_SN = ?", dataAugSn);
        }
        for (int i = rawSns.size() - 1; i >= 0; i--) {
            Long rawSn = rawSns.get(i);
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
        dataAugSns.clear();
        rawSns.clear();
    }

    // ============================================================
    // 시드 헬퍼
    // ============================================================

    private record Fixture(LsDataRaw parent, LsDataSrc parentFrame, LsDataAug aug, LsDataRaw derivative) { }

    /** 부모 영상 + 대표 프레임 1장. */
    private LsDataRaw seedParent(String suffix) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGREL-" + suffix + "-" + UUID.randomUUID(), "CCTV-AUGREL-" + suffix, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/videos/AUGREL-" + suffix + "-orig.mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        LsDataRaw saved = videoRepository.save(parent);
        rawSns.add(saved.getRawSn());
        return saved;
    }

    private LsDataSrc seedParentFrame(Long rawSn, String suffix) {
        LsDataSrc pf = LsDataSrc.create(rawSn, 0L, 0L,
                "/nas/frames/raw/" + suffix + "/f0.jpg", LocalDateTime.now());
        pf.attachDeidPath("/nas/frames/deid/" + suffix + "/f0.jpg");
        return srcRepository.save(pf);
    }

    /**
     * <b>생성이 영구 실패</b>(dead-letter)로 종결된 외부 위탁 증강 — 검수 행도 폐기 표식도 파생도 없다.
     *
     * <p>이 형상이 1차 DEV_FIX 의 재현 케이스다: {@code decision} 은 {@code REJECTED} 로 나가지만
     * 복구 API 는 되돌릴 결정이 없어 <b>항상 404</b> 다.
     */
    private Fixture seedDeadLetter(String suffix) {
        LsDataRaw parent = seedParent(suffix);
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), suffix);
        LsDataAug aug = augRepository.save(LsDataAug.createPending(pf.getSrcSn(),
                LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        aug.applyGenerationResult(LsDataAug.STTS_REJECTED);
        aug.markDeadLetter();
        LsDataAug saved = augRepository.saveAndFlush(aug);
        dataAugSns.add(saved.getDataAugSn());
        return new Fixture(parent, pf, saved, null);
    }

    /** 부모 + 프레임 + 생성 성공 외부 위탁 증강 + 파생(NEW_RAW_SN 매핑) 완전 시드. */
    private Fixture seedExternal(String suffix) {
        LsDataRaw parent = seedParent(suffix);
        LsDataSrc pf = seedParentFrame(parent.getRawSn(), suffix);

        LsDataAug aug = augRepository.save(LsDataAug.createPending(pf.getSrcSn(),
                LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        aug.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        aug = augRepository.saveAndFlush(aug);
        dataAugSns.add(aug.getDataAugSn());

        LsDataRaw derivative = LsDataRaw.createFromAugment(parent,
                "/nas/videos/" + parent.getRawSn() + "/WINTER.mp4",
                aug.getAugTypeCd(), aug.getDataAugSn());
        derivative.markDeidentified("Y");
        derivative.markCompleted();
        derivative = videoRepository.save(derivative);
        rawSns.add(derivative.getRawSn());
        aug.assignDerivativeRawSn(derivative.getRawSn());
        augRepository.saveAndFlush(aug);

        srcRepository.save(LsDataSrc.create(derivative.getRawSn(), 0L, 0L, null,
                "/nas/frames/deid/" + suffix + "/r0.jpg", LocalDateTime.now()));
        return new Fixture(parent, pf, aug, derivative);
    }

    /** 사람이 반려한 검수 행(REJECTED). */
    private LsDataAugRvw seedRejectedReview(Fixture fx) {
        return reviewRepository.saveAndFlush(LsDataAugRvw.createRejected(
                fx.aug().getDataAugSn(), fx.parent().getRawSn(), fx.parentFrame().getSrcSn(),
                DISCARD_REASON, "1", LocalDateTime.now().minusDays(1)));
    }

    /** 열린 폐기 표식 — 운영에서는 {@code reject()} 트랜잭션 안에서만 생긴다. */
    private LsDataAugDscd seedDiscardMark(Fixture fx, LocalDateTime at) {
        return discardRepository.saveAndFlush(LsDataAugDscd.mark(fx.aug().getDataAugSn(),
                fx.derivative().getRawSn(), fx.derivative().getOrgnlRawSn(), DISCARD_REASON, "1", at,
                fx.aug().getAugTypeCd(), fx.aug().getPromptCn()));
    }

    private void markDbDeleted(LsDataAugDscd row) {
        jdbc.update("UPDATE LS_DATA_AUG_DSCD SET DEL_DT = CURRENT_TIMESTAMP "
                + "WHERE DATA_AUG_DSCD_SN = ?", row.getDataAugDscdSn());
    }

    // ============================================================
    // 판정 읽기 / 복구 호출
    // ============================================================

    /** GET 이 이 항목에 대해 내려준 {@code restoreEligible}. */
    private boolean restoreEligible(Fixture fx) throws Exception {
        String json = mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.results[0].restoreEligible");
    }

    private String decision(Fixture fx) throws Exception {
        String json = mockMvc.perform(get("/v1/augments/{jobId}/result", fx.parent().getRawSn())
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.results[0].decision");
    }

    /** 실제 복구 호출 — 응답 상태코드를 그대로 돌려준다. */
    private int callRestore(Fixture fx) throws Exception {
        return mockMvc.perform(post("/v1/augments/{id}/restore", fx.aug().getDataAugSn())
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + RESTORE_REASON + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    // ============================================================
    // 형상별 결합 단언 — true ↔ 200 / false ↔ 4xx
    // ============================================================

    @Test
    @DisplayName("열린표식_검수행REJECTED_는_restoreEligible_true_이고_복구가_성공한다")
    void openMarkWithRejectedReviewIsEligibleAndRestores() throws Exception {
        // given — 운영의 정상 반려 형상(표식은 reject 트랜잭션에서만 생기므로 검수 행이 항상 동반한다)
        Fixture fx = seedExternal("OPENOK");
        seedRejectedReview(fx);
        seedDiscardMark(fx, LocalDateTime.now().minusDays(1));

        // when / then — 두 판정이 같은 방향
        assertThat(restoreEligible(fx)).isTrue();
        assertThat(callRestore(fx)).isEqualTo(200);
        // 복구 후에는 다시 결정 대기이며(더는 되돌릴 것이 없다) 버튼도 사라진다.
        assertThat(decision(fx)).isEqualTo(LsDataAugRvw.STTS_PENDING);
        assertThat(restoreEligible(fx)).isFalse();
    }

    @Test
    @DisplayName("표식없는_사람의_반려_그랜드퍼더링_은_restoreEligible_true_이고_복구가_성공한다")
    void rejectedReviewWithoutMarkIsEligibleAndRestores() throws Exception {
        // given — markDiscarded 가 표식 없이 반환한 형상(매핑 없음·파생 행 부재)
        Fixture fx = seedExternal("NOMARK");
        seedRejectedReview(fx);

        // when / then
        assertThat(restoreEligible(fx)).isTrue();
        assertThat(callRestore(fx)).isEqualTo(200);
        assertThat(decision(fx)).isEqualTo(LsDataAugRvw.STTS_PENDING);
    }

    @Test
    @DisplayName("dead_letter_는_restoreEligible_false_이고_복구가_404_다")
    void deadLetterIsNotEligibleAndRestoreIs404() throws Exception {
        // given — 생성 영구 실패. 검수 행도 폐기 표식도 없다(decision 은 REJECTED 로 보인다).
        Fixture fx = seedDeadLetter("DEADLTR");

        // when / then — 화면은 REJECTED 로 보이지만 복구는 받지 않는다(무한 재시도의 원형).
        assertThat(decision(fx)).isEqualTo(LsDataAugRvw.STTS_REJECTED);
        assertThat(restoreEligible(fx)).isFalse();
        assertThat(callRestore(fx)).isEqualTo(404);
    }

    @Test
    @DisplayName("실삭제된_항목은_restoreEligible_false_이고_복구가_409_다")
    void purgedIsNotEligibleAndRestoreIs409() throws Exception {
        // given
        Fixture fx = seedExternal("PURGED");
        seedRejectedReview(fx);
        markDbDeleted(seedDiscardMark(fx, LocalDateTime.now().minusDays(10)));

        // when / then
        assertThat(restoreEligible(fx)).isFalse();
        assertThat(callRestore(fx)).isEqualTo(409);
    }

    @Test
    @DisplayName("아직_결정하지_않은_항목은_restoreEligible_false_이고_복구가_404_다")
    void undecidedIsNotEligibleAndRestoreIs404() throws Exception {
        // given — 되돌릴 결정 자체가 없다.
        Fixture fx = seedExternal("PENDING");

        // when / then
        assertThat(decision(fx)).isEqualTo(LsDataAugRvw.STTS_PENDING);
        assertThat(restoreEligible(fx)).isFalse();
        assertThat(callRestore(fx)).isEqualTo(404);
    }

    /**
     * <b>열린 표식만으로는 복구가 성립하지 않는다</b> — DEV_FIX LOW-② 재현.
     *
     * <p>{@code restore()} 는 표식을 닫은 <b>뒤</b> 검수 행을 찾아 재오픈하는데, 그 행이 없으면
     * {@code "복구할 검수 이력이 없습니다."} 로 <b>409</b> 다. 구 계산은 "열린 표식 있음 → true" 라
     * 이 형상에서 <b>누르면 반드시 409 인 버튼</b>을 그렸다.
     *
     * <p>(스윕이 검수 행을 먼저 지운 좁은 창에서 실재하는 형상이다.)
     */
    @Test
    @DisplayName("열린표식이어도_검수행이_없으면_restoreEligible_false_이고_복구가_409_다")
    void openMarkWithoutReviewRowIsNotEligibleAndRestoreIs409() throws Exception {
        // given — 표식만 열려 있고 검수 행은 없다.
        Fixture fx = seedExternal("MARKONLY");
        seedDiscardMark(fx, LocalDateTime.now().minusDays(1));

        // when / then
        assertThat(restoreEligible(fx)).isFalse();
        assertThat(callRestore(fx)).isEqualTo(409);
    }

    /**
     * <b>중복 검수 행이 있어도 조회와 복구가 같은 행을 본다</b> — DEV_FIX MEDIUM-① 재현.
     *
     * <p>{@code LS_DATA_AUG_RVW} 에는 {@code DATA_AUG_SN} 유니크가 없고 중복 행 정리 마이그레이션도
     * 금지돼 있어 한 증강에 검수 행이 2건 이상 공존할 수 있다. 구 구현은 조회가 {@code RVW_DT} 최대,
     * 복구가 {@code REG_DT} 최대를 골라 <b>다른 행</b>을 봤다:
     * <pre>
     *   X  REJECTED  REG_DT .100  RVW_DT .400   ← 구 조회가 고르던 행(RVW_DT 최대)
     *   Y  ACCEPTED  REG_DT .200  RVW_DT .300   ← 복구가 고르던 행(REG_DT 최대)
     * </pre>
     * 화면은 X 기준으로 "반려됨 + 복구 가능" 을 그리는데 복구는 Y 를 보고 404 를 냈고, 재조회해도
     * 같은 값이라 <b>무한 재시도</b>였다. 정의를 {@code REG_DT} 축으로 통일했으므로 두 판정 모두
     * Y 를 본다 — {@code decision=ACCEPTED} · {@code restoreEligible=false} · 복구 404 가 <b>서로
     * 정합</b>하다.
     *
     * <p>이 테스트는 정합만 요구하지 않고 <b>어느 행을 보는지</b>({@code decision})까지 고정한다.
     * 정합만 보면 "둘 다 X 를 본다" 로도 통과하는데, 그러면 화면이 <b>BE 가 행동할 대상이 아닌 행</b>
     * 을 보여주게 되어 정본 선택이 조용히 뒤집힌다.
     */
    @Test
    @DisplayName("중복_검수행이_있어도_조회와_복구가_같은_행_REG_DT최대_을_본다")
    void duplicateReviewRowsAreResolvedByOneDefinition() throws Exception {
        // given — 같은 증강에 검수 행 2건(REG_DT 와 RVW_DT 의 순서가 서로 반대)
        Fixture fx = seedExternal("DUPROWS");
        LocalDateTime base = LocalDateTime.now().minusDays(1).withNano(0);
        LsDataAugRvw older = reviewRepository.saveAndFlush(LsDataAugRvw.createRejected(
                fx.aug().getDataAugSn(), fx.parent().getRawSn(), fx.parentFrame().getSrcSn(),
                DISCARD_REASON, "1", base));
        LsDataAugRvw newer = reviewRepository.saveAndFlush(LsDataAugRvw.createAccepted(
                fx.aug().getDataAugSn(), fx.parent().getRawSn(), fx.parentFrame().getSrcSn(),
                new BigDecimal("90.00"), "1", base));
        // X: REG_DT 이르고 RVW_DT 늦다 / Y: REG_DT 늦고 RVW_DT 이르다
        setTimes(older, base.plusNanos(100_000_000L), base.plusNanos(400_000_000L));
        setTimes(newer, base.plusNanos(200_000_000L), base.plusNanos(300_000_000L));

        // when / then — REG_DT 최대(Y=ACCEPTED)를 두 판정이 함께 본다.
        assertThat(decision(fx))
                .as("조회도 복구 경로와 같은 행(REG_DT 최대)을 봐야 한다")
                .isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
        assertThat(restoreEligible(fx)).isFalse();
        assertThat(callRestore(fx))
                .as("restoreEligible=false 면 복구는 반드시 거부여야 한다")
                .isEqualTo(404);
    }

    private void setTimes(LsDataAugRvw row, LocalDateTime regDt, LocalDateTime rvwDt) {
        jdbc.update("UPDATE LS_DATA_AUG_RVW SET REG_DT = ?, RVW_DT = ? WHERE DATA_AUG_RVW_SN = ?",
                Timestamp.valueOf(regDt), Timestamp.valueOf(rvwDt), row.getDataAugRvwSn());
    }
}
