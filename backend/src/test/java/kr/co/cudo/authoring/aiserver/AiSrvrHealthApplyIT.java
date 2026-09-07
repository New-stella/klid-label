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
 * <b>SQL 자체가 규칙</b>이다 — 강등의 출발 상태 조건(조건부 UPDATE)과 복합키 upsert 는 실제 DB 없이는
 * 증명되지 않는다. 특히 강등 쿼리는 목이 돌려주는 숫자만 보면 <b>항상 성공</b>한다.
 *
 * <p>⚠ 이 경로는 <b>마지막 가용 노드 보호를 타지 않는다</b>(2026-09-07 정정) — 그 보호는 사람의 조작
 * 축이고 상태점검은 관측 축이다. [@design AC-1093]
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

    /**
     * ★★ <b>마지막 가용 노드도 내려간다</b> — 실제 DB 로 고정한다. [@design AC-1093] [@design AC-1091]
     *
     * <p>⚠ <b>구 동작 폐기(2026-09-07)</b>: 「마지막 가용노드는 연속 실패해도 내려가지 않는다 — 내리면
     * AI 기능 전체가 멈춘다」. 그 보호가 <b>실제 장애를 감췄다</b> — 현장에서 두 장비가 같은 시각에 둘 다
     * 응답하지 않는데 나중 쪽이 「가용」으로 남아, 화면을 본 운영자가 「한 대는 살아 있다」로 읽었다.
     *
     * <p>보호는 <b>사람의 조작</b> 축이고 상태점검은 <b>관측</b> 축이다. 관측을 막으면 죽은 장비로 계속
     * 보내게 되며, 가용이 0이 되는 것도 두렵지 않다 — 후보가 0이면 위탁은 <b>폴백 없이 거부</b>된다.
     * <b>되살리지 말 것.</b>
     */
    @Test
    @DisplayName("★★마지막_가용노드도_연속_실패하면_내려간다 — 구 보호 동작 폐기")
    void 마지막_가용노드도_연속_실패하면_내려간다() {
        // given — 노드가 하나뿐이다. 구 동작은 이 조합에서 강등을 거부했다.
        repository.saveAndFlush(node("gpu01"));

        // when
        for (int i = 0; i < 3; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        // then — 실제 장애를 소프트웨어로 부정하지 않는다.
        assertThat(statusOf("gpu01"))
                .as("살아 있지 않은 장비를 「가용」으로 남기면 화면이 거짓을 말한다")
                .isEqualTo(AiSrvrStatus.UNAVAILABLE.name());
        assertThat(failNocsOf("gpu01")).isEqualTo(3);
    }

    /**
     * 임계 <b>미만</b>에서는 내려가지 않는다 — 한 번의 네트워크 흔들림으로 장비를 잃지 않기 위한 축이며
     * 이번 변경이 건드리지 않는다.
     */
    @Test
    @DisplayName("임계_미만의_연속_실패로는_마지막_노드도_내려가지_않는다")
    void 임계_미만의_연속_실패로는_내려가지_않는다() {
        repository.saveAndFlush(node("gpu01"));

        for (int i = 0; i < 2; i++) {
            txService.applyHealth("gpu01", false, NOW);
        }

        assertThat(statusOf("gpu01")).isEqualTo(AiSrvrStatus.AVAILABLE.name());
        assertThat(failNocsOf("gpu01")).isEqualTo(2);
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
