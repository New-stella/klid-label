package kr.co.cudo.authoring.aiserver;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import kr.co.cudo.authoring.aiserver.service.AiSrvrHealthTxService;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrSlotLoad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태점검 결과 반영을 <b>실제 DB</b> 로 검증한다 (Testcontainers PostgreSQL). [@design ADR-057]
 *
 * <h3>왜 단위 시험으로 충분하지 않은가</h3>
 * <p>단위 시험은 저장소를 목으로 세워 "어느 쿼리를 불렀는가"만 고정한다. 그런데 이 경로의 두 축은
 * <b>SQL 자체가 규칙</b>이다 — 마지막 가용 노드 보호(잠금 CTE + 조건부 UPDATE)와 복합키 upsert 는
 * 실제 DB 없이는 증명되지 않는다. 특히 강등 쿼리는 목이 돌려주는 숫자만 보면 <b>항상 성공</b>한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrHealthApplyIT {

    @Autowired private AiSrvrHealthTxService txService;
    @Autowired private LsAiSrvrRepository repository;
    @Autowired private LsAiSrvrUsgRepository usgRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 1, 4, 12, 33);

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_usg");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("연속_세번_실패한_노드는_원장에서_이용불가가_된다")
    void 연속_세번_실패한_노드는_원장에서_이용불가가_된다() {
        // given — 가용 노드가 둘이라 마지막 하나 보호에 걸리지 않는다
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));

        // when
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        // then
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.UNAVAILABLE.name());
        assertThat(statusOf("gpu02")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
    }

    @Test
    @DisplayName("마지막_가용노드는_연속_실패해도_내려가지_않는다")
    void 마지막_가용노드는_연속_실패해도_내려가지_않는다() {
        // given — 노드가 하나뿐이다. 내리면 AI 기능 전체가 멈춘다.
        repository.saveAndFlush(node("gpu01"));

        // when
        for (int i = 0; i < 5; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        // then — 상태는 그대로이고 연속 실패만 쌓인다(로그로 시끄럽게 남는다)
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
        assertThat(failNocsOf("gpu01")).isEqualTo(5);
    }

    @Test
    @DisplayName("이용불가_노드는_연속_세번_성공하면_원장에서_복귀하고_카운터가_비워진다")
    void 이용불가_노드는_연속_세번_성공하면_원장에서_복귀하고_카운터가_비워진다() {
        // given — 실패로 내려간 노드
        repository.saveAndFlush(node("gpu01"));
        repository.saveAndFlush(node("gpu02"));
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        // when
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", true, NOW);
        }

        // then — ★복귀 직후 카운터가 남아 있으면 다음 실패 한 번에 임계를 넘겨 곧바로 다시 내려간다
        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
        assertThat(failNocsOf("gpu01")).isZero();
        assertThat(scsNocsOf("gpu01")).isZero();
    }

    @Test
    @DisplayName("부하는_같은_행에_덮어써지고_용도마다_따로_쌓인다")
    void 부하는_같은_행에_덮어써지고_용도마다_따로_쌓인다() {
        // given
        repository.saveAndFlush(node("gpu01"));

        // when — 두 번 관측한다(신규 삽입 -> 갱신)
        txService.applyLoad("gpu01", Map.of(
                AiSrvrUsageType.BATCH, new AiSrvrSlotLoad(1, 12),
                AiSrvrUsageType.INTERACTIVE, new AiSrvrSlotLoad(0, 0)), NOW);
        txService.applyLoad("gpu01", Map.of(
                AiSrvrUsageType.BATCH, new AiSrvrSlotLoad(1, 3),
                AiSrvrUsageType.INTERACTIVE, new AiSrvrSlotLoad(1, 0)), NOW.plusSeconds(5));

        // then — 행이 늘지 않고 값만 갱신된다(복합키 upsert)
        assertThat(usgRepository.findBySrvrId("gpu01")).hasSize(2);
        assertThat(usgRepository.findById(
                        new kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg.Key(
                                "gpu01", AiSrvrUsageType.BATCH))
                .orElseThrow().effectiveLoad()).isEqualTo(4);
        assertThat(usgRepository.findById(
                        new kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg.Key(
                                "gpu01", AiSrvrUsageType.INTERACTIVE))
                .orElseThrow().effectiveLoad()).isEqualTo(1);
    }

    private String statusOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT srvr_stts_cd FROM ls_ai_srvr WHERE srvr_id = ?", String.class, srvrId);
    }

    private Integer failNocsOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT chck_fail_nocs FROM ls_ai_srvr WHERE srvr_id = ?", Integer.class, srvrId);
    }

    private Integer scsNocsOf(String srvrId) {
        return jdbcTemplate.queryForObject(
                "SELECT chck_scs_nocs FROM ls_ai_srvr WHERE srvr_id = ?", Integer.class, srvrId);
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now());
    }
}
