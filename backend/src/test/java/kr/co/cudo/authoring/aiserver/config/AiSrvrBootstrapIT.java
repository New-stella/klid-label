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
import static org.assertj.core.api.Assertions.assertThatCode;

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
    @Autowired private kr.co.cudo.authoring.aiserver.service.AiSrvrRegistry registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager txManager;

    @Value("${authoring.integration.ai-server.base-url}")
    private String configuredSrvrAddr;

    /**
     * 시험에서 쓰는 외부 시계열 분석 씨앗 주소.
     *
     * <p>★<b>프로파일 설정값을 쓰지 않는다.</b> 시험 리소스의 프로파일 파일이 운영 파일을 대체하므로
     * 이 값은 환경에 따라 채워지기도 비기도 한다 — 그것에 기대면 시험이 <b>설정 파일 편집 한 줄에
     * 조용히 무의미해진다</b>. 씨앗 주소는 시험이 직접 주입해 두 갈래(있음/없음)를 모두 고정한다.
     */
    private static final String TIMESERIES_ADDR = "https://vlm.internal:8443";

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
    }

    @Test
    @DisplayName("원장이_비어있으면_기동시_기존_주소로_추론노드_한건이_등록된다")
    void 원장이_비어있으면_기동시_기존_주소로_추론노드_한건이_등록된다() {
        // given — 배포 직후(빈 원장)
        assertThat(repository.count()).isZero();

        // when
        guard.bootstrapIfEmpty();

        // then — 설정값 그대로 추론 노드 하나. 현재 동작이 그대로 이어진다.
        List<LsAiSrvr> inference = repository.findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType.INFERENCE);
        assertThat(inference).hasSize(1);
        LsAiSrvr node = inference.get(0);
        assertThat(node.getSrvrId()).isEqualTo(AiSrvrBootstrapGuard.DEFAULT_SRVR_ID);
        assertThat(node.getSrvrAddr()).isEqualTo(configuredSrvrAddr);
        assertThat(node.getSrvrSttsCd()).isEqualTo(AiSrvrStatus.AVAILABLE);
        assertThat(node.getSrvrTypeCd()).isEqualTo(LsAiSrvr.SrvrType.INFERENCE);
        assertThat(node.getRegDt()).isNotNull();
    }

    @Test
    @DisplayName("★시계열_주소가_설정된_환경이면_시계열_노드도_한건_심긴다")
    void 시계열_주소가_설정된_환경이면_시계열_노드도_한건_심긴다() {
        // when
        wiredGuard().bootstrapIfEmpty();

        // then — 유형별로 하나씩. 시계열 축이 고를 장비가 생긴다.
        List<LsAiSrvr> timeseries = repository.findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType.TIMESERIES);
        assertThat(timeseries).hasSize(1);
        assertThat(timeseries.get(0).getSrvrId())
                .isEqualTo(AiSrvrBootstrapGuard.DEFAULT_TIMESERIES_SRVR_ID);
        assertThat(timeseries.get(0).getSrvrAddr()).isEqualTo(TIMESERIES_ADDR);
        assertThat(timeseries.get(0).getSrvrSttsCd()).isEqualTo(AiSrvrStatus.AVAILABLE);
    }

    @Test
    @DisplayName("★시계열_주소가_비어있으면_시계열_노드가_심기지_않고_기동은_정상이다")
    void 시계열_주소가_비어있으면_시계열_노드가_심기지_않고_기동은_정상이다() {
        // given — 미연동이 정상인 배포. 값 없이 행을 세우면 존재하지 않는 장비로 위탁이 나간다.
        AiSrvrBootstrapGuard unwired =
                new AiSrvrBootstrapGuard(repository, registry, configuredSrvrAddr, "");

        // when
        unwired.bootstrapIfEmpty();

        // then
        assertThat(repository.findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType.TIMESERIES)).isEmpty();
        assertThat(repository.findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType.INFERENCE)).hasSize(1);
    }

    @Test
    @DisplayName("★추론노드만_있는_기존_환경에도_시계열_씨앗은_심긴다")
    void 추론노드만_있는_기존_환경에도_시계열_씨앗은_심긴다() {
        // given — 먼저 배포된 환경. 원장 <전체>가 비었는지로 판정하면 여기서 시계열이 영영 안 심긴다.
        repository.saveAndFlush(LsAiSrvr.register("gpu01", "klid-ai-gpu-01",
                "http://10.0.0.11:9300", LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now()));

        // when — 재기동
        wiredGuard().bootstrapIfEmpty();

        // then — 추론은 그대로 두고 시계열만 새로 심는다
        assertThat(repository.findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType.INFERENCE))
                .extracting(LsAiSrvr::getSrvrId).containsExactly("gpu01");
        assertThat(repository.findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType.TIMESERIES))
                .extracting(LsAiSrvr::getSrvrId)
                .containsExactly(AiSrvrBootstrapGuard.DEFAULT_TIMESERIES_SRVR_ID);
    }

    @Test
    @DisplayName("★이미_시계열_노드가_있으면_설정값이_달라도_덮어쓰지_않는다")
    void 이미_시계열_노드가_있으면_설정값이_달라도_덮어쓰지_않는다() {
        // given — 운영자가 관리 화면에서 바꿔 둔 주소
        repository.saveAndFlush(LsAiSrvr.register("vlm01", "vendor-vlm-01",
                "https://10.0.0.31:8443", LsAiSrvr.SrvrType.TIMESERIES, LocalDateTime.now()));

        // when — 재기동. 설정에는 <다른> 주소가 들어 있다.
        wiredGuard().bootstrapIfEmpty();

        // then — 씨앗은 최초 1회뿐이다. 덮어쓰면 화면에서 바꾼 주소가 배포 시점 값으로 되돌아간다.
        assertThat(repository.findBySrvrTypeCdOrderBySrvrIdAsc(LsAiSrvr.SrvrType.TIMESERIES))
                .extracting(LsAiSrvr::getSrvrId, LsAiSrvr::getSrvrAddr)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("vlm01", "https://10.0.0.31:8443"));
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
    @DisplayName("두_유형_모두_노드가_있으면_부트스트랩이_아무것도_하지_않는다")
    void 두_유형_모두_노드가_있으면_부트스트랩이_아무것도_하지_않는다() {
        // given — 관리자가 이미 실제 장비를 유형별로 등록해 둔 상태
        repository.saveAndFlush(LsAiSrvr.register("gpu01", "klid-ai-gpu-01",
                "http://10.0.0.11:9300", LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now()));
        repository.saveAndFlush(LsAiSrvr.register("vlm01", "vendor-vlm-01",
                "https://10.0.0.31:8443", LsAiSrvr.SrvrType.TIMESERIES, LocalDateTime.now()));

        // when — 재기동
        wiredGuard().bootstrapIfEmpty();

        // then — 씨앗은 최초 1회뿐이다. 설정값이 원장을 덮어쓰면 진실원이 둘이 된다.
        assertThat(repository.findAll()).extracting(LsAiSrvr::getSrvrId)
                .containsExactlyInAnyOrder("gpu01", "vlm01");
    }

    @Test
    @DisplayName("부트스트랩을_두_번_돌려도_노드가_늘지_않는다")
    void 부트스트랩을_두_번_돌려도_노드가_늘지_않는다() {
        // 2노드 Active-Active 라 두 노드가 동시에 기동한다.
        guard.bootstrapIfEmpty();
        long afterFirst = repository.count();

        guard.bootstrapIfEmpty();

        assertThat(afterFirst).isPositive();
        assertThat(repository.count()).isEqualTo(afterFirst);
    }

    /**
     * ★ <b>원장 데이터 한 행이 앱 전체를 못 뜨게 하지 않는다</b>. [@design ADR-062] [@design AC-1074]
     *
     * <p>구 동작은 여기서 {@code IllegalStateException} 을 던져 <b>기동을 중단</b>시켰다. 판정은
     * 그대로 두고 걸리는 자리만 장비 선택 시점으로 옮겼으므로, 이제 이 자리는 <b>알리기만</b> 한다 —
     * 그리고 그 기록이 없으면 「막지 않는다」가 「알리지 않는다」가 된다.
     */
    @Test
    @DisplayName("★원장의_서버ID가_형식을_위반해도_기동은_통과하고_위반이_전부_ERROR로_남는다")
    void 원장의_서버ID가_형식을_위반해도_기동은_통과한다() {
        // given — 체크 제약을 우회해 들어온 행(운영 SQL·제약 이전 데이터)을 재현한다.
        //         DDL 까지 트랜잭션에 넣고 롤백하므로 공유 컨테이너 상태가 남지 않는다.
        Logger guardLogger = (Logger) LoggerFactory.getLogger(AiSrvrBootstrapGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        guardLogger.addAppender(appender);

        try {
            new TransactionTemplate(txManager).execute(status -> {
                jdbcTemplate.execute(
                        "ALTER TABLE ls_ai_srvr DROP CONSTRAINT ck_ls_ai_srvr_srvr_id_format");
                jdbcTemplate.update("""
                        INSERT INTO ls_ai_srvr (srvr_id, srvr_addr, srvr_type_cd, srvr_stts_cd,
                                                chck_fail_nocs, reg_dt)
                        VALUES ('klid-ai-gpu-01', 'http://10.0.0.11:9300', 'INFERENCE', 'AVAILABLE',
                                0, now()),
                               ('GPU02', 'http://10.0.0.12:9300', 'INFERENCE', 'AVAILABLE',
                                0, now())
                        """);

                // when — 던지지 않는다. 던지면 그 자체로 기동 차단이 되살아난 것이다.
                assertThatCode(() -> guard.verifyLedger()).doesNotThrowAnyException();

                status.setRollbackOnly();
                return null;
            });

            // then — 위반은 <전부> ERROR 로 남는다(하나씩 알려주면 고치고 다시 보기를 반복한다).
            assertThat(appender.list)
                    .as("기동을 막지 않는 대신 오류 수준 기록이 유일한 단서가 된다")
                    .anyMatch(event -> event.getLevel() == Level.ERROR
                            && event.getFormattedMessage().contains("klid-ai-gpu-01")
                            && event.getFormattedMessage().contains("GPU02"));
        } finally {
            guardLogger.detachAppender(appender);
        }

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

    /** 시계열 주소가 <b>채워진</b> 배포를 재현한 가드. */
    private AiSrvrBootstrapGuard wiredGuard() {
        return new AiSrvrBootstrapGuard(repository, registry, configuredSrvrAddr, TIMESERIES_ADDR);
    }

    private boolean constraintExists(String name) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint
                 WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = ?
                """, Integer.class, name);
        return count != null && count > 0;
    }
}
