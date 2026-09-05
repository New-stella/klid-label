package kr.co.cudo.authoring.aiserver;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 용도별 부하 표의 <b>DB 강제</b> 검증 (Testcontainers PostgreSQL). [@design ADR-057]
 *
 * <h3>왜 부모에 값 하나를 두지 않는가</h3>
 * <p>노드를 고를 때 보는 값은 <b>그 요청 자신의 용도에 해당하는 대기 건수뿐</b>이다. 두 용도는
 * 장비 안에서 실행이 격리돼 있어 한쪽이 밀려 있다는 사실이 다른 쪽의 응답을 늦추지 않는다.
 * 두 값을 하나로 합치면 장비 안에서 갈라 놓은 것을 부르는 쪽에서 다시 붙이는 셈이 되어,
 * 일괄 처리가 밀린 장비를 화면 요청이 <b>피할 이유가 없는데도 피하게</b> 된다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrUsgLedgerIT {

    @Autowired private LsAiSrvrRepository repository;
    @Autowired private LsAiSrvrUsgRepository usgRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_usg");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("한_노드는_용도마다_한_행을_갖는다")
    void 한_노드는_용도마다_한_행을_갖는다() {
        // given
        repository.saveAndFlush(node("gpu01"));

        // when
        usgRepository.saveAndFlush(observed("gpu01", AiSrvrUsageType.BATCH, 1, 12));
        usgRepository.saveAndFlush(observed("gpu01", AiSrvrUsageType.INTERACTIVE, 0, 0));

        // then
        assertThat(usgRepository.findBySrvrId("gpu01")).hasSize(2);
    }

    @Test
    @DisplayName("허용목록_밖의_용도유형코드는_저장이_거부된다")
    void 허용목록_밖의_용도유형코드는_저장이_거부된다() {
        repository.saveAndFlush(node("gpu01"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ls_ai_srvr_usg
                    (srvr_id, usg_type_cd, wtng_nocs, prcs_nocs, reg_dt)
                VALUES ('gpu01', 'TRAINING', 0, 0, now())
                """))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("건수가_음수면_저장이_거부된다")
    void 건수가_음수면_저장이_거부된다() {
        repository.saveAndFlush(node("gpu01"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ls_ai_srvr_usg
                    (srvr_id, usg_type_cd, wtng_nocs, prcs_nocs, reg_dt)
                VALUES ('gpu01', 'BATCH', -1, 0, now())
                """))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("원장에_없는_노드의_부하행은_저장이_거부된다")
    void 원장에_없는_노드의_부하행은_저장이_거부된다() {
        assertThatThrownBy(() ->
                usgRepository.saveAndFlush(observed("ghost", AiSrvrUsageType.BATCH, 0, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("노드를_지우면_그_노드의_부하행도_함께_사라진다")
    void 노드를_지우면_그_노드의_부하행도_함께_사라진다() {
        // given — 부하행은 관측값일 뿐이라 노드보다 오래 살아남을 이유가 없다(배정 기록과 다른 축)
        repository.saveAndFlush(node("gpu01"));
        usgRepository.saveAndFlush(observed("gpu01", AiSrvrUsageType.BATCH, 1, 12));

        // when
        jdbcTemplate.update("DELETE FROM ls_ai_srvr WHERE srvr_id = 'gpu01'");

        // then
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ls_ai_srvr_usg WHERE srvr_id = 'gpu01'", Integer.class);
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("원장에는_용도를_가리지_않는_대기건수_컬럼이_남아있지_않다")
    void 원장에는_용도를_가리지_않는_대기건수_컬럼이_남아있지_않다() {
        // ★자식으로 옮긴 값을 부모에 남겨 두면 아무도 쓰지 않는 죽은 컬럼이 되고,
        //   다음 사람이 그 값을 「전체 부하」로 읽어 두 용도를 다시 합치게 된다.
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = 'ls_ai_srvr'
                   AND column_name = 'wtng_nocs'
                """, Integer.class);

        assertThat(columns).isZero();
    }

    @Test
    @DisplayName("원장에는_연속_성공을_세는_컬럼이_있다")
    void 원장에는_연속_성공을_세는_컬럼이_있다() {
        // 복귀 판정(연속 N회 성공)은 폴링 노드의 메모리에 둘 수 없다 — 2노드가 틱을 나눠 갖고,
        // 재기동으로도 사라진다.
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND table_name = 'ls_ai_srvr'
                   AND column_name = 'chck_scs_nocs'
                """, Integer.class);

        assertThat(columns).isEqualTo(1);
    }

    private static LsAiSrvr node(String srvrId) {
        return LsAiSrvr.register(srvrId, null, "http://ai.internal:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now());
    }

    private static LsAiSrvrUsg observed(String srvrId, AiSrvrUsageType usage, int running, int queued) {
        LsAiSrvrUsg usg = LsAiSrvrUsg.of(srvrId, usage, LocalDateTime.now());
        usg.observe(running, queued, LocalDateTime.now());
        return usg;
    }
}
