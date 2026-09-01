package kr.co.cudo.authoring.aiserver.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기동 시 원장 부트스트랩·검증의 실동작 검증 (Testcontainers PostgreSQL). [@design ADR-057]
 *
 * <h3>부트스트랩이 하위호환의 전부다</h3>
 * <p>노드 목록의 진실원은 <b>원장 하나</b>이고 기존 설정값은 <b>최초 1회 씨앗</b>으로만 쓴다
 * (설정과 원장을 둘 다 진실원으로 두면 어느 쪽이 이기는지가 코드에 흩어진다). 그래서 배포 직후
 * 빈 원장이면 설정값으로 노드 하나를 세워 <b>현재 동작을 그대로</b> 유지한다.
 *
 * <p>⚠ 그런데 그 설정 기본값은 <b>틀려 있을 수 있다</b> — 반입 설정 템플릿이 추론 서버 주소를
 * loopback 으로 가리켜, 장비를 나눈 구성에서는 반드시 틀린다. 그리고 틀려도 기동과 상태점검은
 * 정상이고 오토라벨링만 조용히 실패한다. 그래서 부트스트랩은 <b>경고를 남긴다</b>.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrBootstrapIT {

    @Autowired private AiSrvrBootstrapGuard guard;
    @Autowired private LsAiSrvrRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager txManager;

    @Value("${authoring.integration.ai-server.base-url}")
    private String configuredSrvrAddr;

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("원장이_비어있으면_기동시_기존_주소로_노드_한건이_등록된다")
    void 원장이_비어있으면_기동시_기존_주소로_노드_한건이_등록된다() {
        // given — 배포 직후(빈 원장)
        assertThat(repository.count()).isZero();

        // when
        guard.bootstrapIfEmpty();

        // then — 설정값 그대로 노드 하나. 현재 동작이 그대로 이어진다.
        List<LsAiSrvr> nodes = repository.findAll();
        assertThat(nodes).hasSize(1);
        LsAiSrvr node = nodes.get(0);
        assertThat(node.getSrvrId()).isEqualTo(AiSrvrBootstrapGuard.DEFAULT_SRVR_ID);
        assertThat(node.getSrvrAddr()).isEqualTo(configuredSrvrAddr);
        assertThat(node.getSrvrSttsCd()).isEqualTo(AiSrvrStatus.AVAILABLE);
        assertThat(node.getSrvrTypeCd()).isEqualTo(LsAiSrvr.SrvrType.INFERENCE);
        assertThat(node.getRegDt()).isNotNull();
    }

    @Test
    @DisplayName("부트스트랩_아이디는_스스로_형식_규약을_지킨다")
    void 부트스트랩_아이디는_스스로_형식_규약을_지킨다() {
        // 우리가 만든 값이 DB 체크 제약에 걸리면 기동이 통째로 막힌다.
        guard.bootstrapIfEmpty();
        assertThat(repository.findById(AiSrvrBootstrapGuard.DEFAULT_SRVR_ID)).isPresent();
    }

    @Test
    @DisplayName("부트스트랩은_주소_확인_경고를_남긴다")
    void 부트스트랩은_주소_확인_경고를_남긴다() {
        // given
        Logger guardLogger = (Logger) LoggerFactory.getLogger(AiSrvrBootstrapGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);

        try {
            // when
            guard.bootstrapIfEmpty();

            // then — 기본 주소가 틀려도 기동·상태점검은 정상이라 이 경고가 유일한 단서다
            assertThat(appender.list)
                    .as("최초 등록 시 주소 확인 경고(WARN)가 남아야 한다")
                    .anyMatch(event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains(AiSrvrBootstrapGuard.DEFAULT_SRVR_ID));
        } finally {
            guardLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("부트스트랩_경고에_노드_주소_전문이_실리지_않는다")
    void 부트스트랩_경고에_노드_주소_전문이_실리지_않는다() {
        // given — 노드 주소는 내부 토폴로지다(CWE-497). 로그로 새어 나가지 않아야 한다.
        Logger guardLogger = (Logger) LoggerFactory.getLogger(AiSrvrBootstrapGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);

        try {
            // when
            guard.bootstrapIfEmpty();

            // then
            assertThat(appender.list)
                    .noneMatch(event -> event.getFormattedMessage().contains(configuredSrvrAddr));
        } finally {
            guardLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("원장에_노드가_있으면_부트스트랩이_아무것도_하지_않는다")
    void 원장에_노드가_있으면_부트스트랩이_아무것도_하지_않는다() {
        // given — 관리자가 이미 실제 장비를 등록해 둔 상태
        repository.saveAndFlush(LsAiSrvr.register("gpu01", "klid-ai-gpu-01",
                "http://10.0.0.11:9300", LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now()));

        // when — 재기동
        guard.bootstrapIfEmpty();

        // then — 씨앗은 최초 1회뿐이다. 설정값이 원장을 덮어쓰면 진실원이 둘이 된다.
        assertThat(repository.findAll()).extracting(LsAiSrvr::getSrvrId).containsExactly("gpu01");
    }

    @Test
    @DisplayName("부트스트랩을_두_번_돌려도_노드가_늘지_않는다")
    void 부트스트랩을_두_번_돌려도_노드가_늘지_않는다() {
        // 2노드 Active-Active 라 두 노드가 동시에 기동한다.
        guard.bootstrapIfEmpty();
        guard.bootstrapIfEmpty();

        assertThat(repository.count()).isOne();
    }

    @Test
    @DisplayName("원장의_서버ID가_형식을_위반하면_기동이_실패한다")
    void 원장의_서버ID가_형식을_위반하면_기동이_실패한다() {
        // given — 체크 제약을 우회해 들어온 행(운영 SQL·제약 이전 데이터)을 재현한다.
        //         DDL 까지 트랜잭션에 넣고 롤백하므로 공유 컨테이너 상태가 남지 않는다.
        new TransactionTemplate(txManager).execute(status -> {
            jdbcTemplate.execute(
                    "ALTER TABLE ls_ai_srvr DROP CONSTRAINT ck_ls_ai_srvr_srvr_id_format");
            jdbcTemplate.update("""
                    INSERT INTO ls_ai_srvr (srvr_id, srvr_addr, srvr_type_cd, srvr_stts_cd,
                                            wtng_nocs, chck_fail_nocs, reg_dt)
                    VALUES ('klid-ai-gpu-01', 'http://10.0.0.11:9300', 'INFERENCE', 'AVAILABLE',
                            0, 0, now())
                    """);

            // when · then — 경고가 아니라 기동 차단이다. 지금 막지 않으면 어긋남이 한참 뒤
            //               메트릭 라벨에서 드러난다.
            assertThatThrownBy(() -> guard.verifyLedger())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("klid-ai-gpu-01");

            status.setRollbackOnly();
            return null;
        });

        // 제약이 되살아났는지 확인 — 여기서 새면 뒤따르는 시험이 조용히 통과한다
        assertThat(constraintExists("ck_ls_ai_srvr_srvr_id_format")).isTrue();
    }

    @Test
    @DisplayName("원장이_형식을_지키면_검증이_통과한다")
    void 원장이_형식을_지키면_검증이_통과한다() {
        repository.saveAndFlush(LsAiSrvr.register("gpu01", null, "http://10.0.0.11:9300",
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now()));

        guard.verifyLedger();
    }

    private boolean constraintExists(String name) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = ?
                """, Integer.class, name);
        return count != null && count > 0;
    }
}
