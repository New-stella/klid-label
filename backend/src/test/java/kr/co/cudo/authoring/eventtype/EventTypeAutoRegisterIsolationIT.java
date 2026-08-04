package kr.co.cudo.authoring.eventtype;

import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import kr.co.cudo.authoring.eventtype.service.EventTypeAutoRegistrar;
import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이벤트유형 자동등록의 <b>트랜잭션 격리</b>와 <b>캐시 무효화 시점</b> 회귀 가드.
 *
 * <h3>왜 이 테스트가 따로 필요한가</h3>
 * <p>{@code LsEvntTypeAutoRegisterIT} 는 {@code register()} 를 <b>독립 호출</b>로만 검증해서, 이
 * 메서드가 <b>영상 적재 트랜잭션에 참여</b>했을 때의 동작이 커버리지 밖이었다. 참여(기본
 * {@code REQUIRED})하면 등록 중 발생한 DB 오류가 <b>호출자의 물리 트랜잭션까지 abort</b> 시켜
 * "실패해도 적재를 막지 않는다"는 보장이 깨진다 — Java 의 {@code catch} 로는 되살릴 수 없다.
 * ({@code ON CONFLICT DO NOTHING} 이 막는 것은 PK 유니크 위반 하나뿐이고, 잠금 대기·데드락·
 * statement timeout 은 그대로 트랜잭션을 죽인다.)
 *
 * <p>따라서 여기서 고정하는 것은 <b>"별도 물리 트랜잭션에서 돈다"</b>는 사실 자체다 —
 * 어노테이션 문자열이 아니라 <b>실동작</b>(호출자 롤백에도 등록이 살아남는다)으로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class EventTypeAutoRegisterIsolationIT {

    /** 이 테스트가 만드는 유형코드 접두(컬럼 길이 20 이내). */
    private static final String PREFIX = "ITISO";

    @Autowired private EventTypeAutoRegistrar autoRegistrar;
    @Autowired private EventTypeCacheEvictor cacheEvictor;
    @Autowired private LsEvntTypeRepository repository;
    @Autowired private CacheManager cacheManager;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        txTemplate = new TransactionTemplate(controlTxManager);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        dropPoisonConstraint();
        jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd LIKE ?", PREFIX + "%");
    }

    /**
     * 등록 문을 <b>서버 측에서</b> 실패시키기 위한 스크래치 CHECK 제약(테스트 종료 시 제거).
     *
     * <p>클라이언트 측에서 걸러지는 값(예: NUL 바이트)으로는 이 시나리오를 재현할 수 없다 —
     * 그러면 PostgreSQL 트랜잭션이 애초에 abort 되지 않아 <b>참여 트랜잭션이어도 통과</b>하는
     * 가짜 통과가 된다. 실제 사고 계열(잠금 대기·데드락·타임아웃·제약 위반)과 같이 <b>서버가
     * 트랜잭션을 죽이는</b> 오류여야 가드가 성립한다.
     */
    private void addPoisonConstraint() {
        jdbc.execute("ALTER TABLE ls_evnt_type ADD CONSTRAINT ck_itiso_poison"
                + " CHECK (evnt_nm IS NULL OR evnt_nm <> 'ITISO-POISON')");
    }

    private void dropPoisonConstraint() {
        jdbc.execute("ALTER TABLE ls_evnt_type DROP CONSTRAINT IF EXISTS ck_itiso_poison");
    }

    @Test
    @DisplayName("자동등록은_호출자_트랜잭션이_롤백돼도_살아남는다")
    void 자동등록은_호출자_트랜잭션이_롤백돼도_살아남는다() {
        // given — 영상 적재처럼 <이미 열린 트랜잭션> 안에서 자동등록이 호출되는 상황
        String code = PREFIX + "RN";

        // when — 호출자 트랜잭션을 롤백시킨다
        txTemplate.execute(status -> {
            autoRegistrar.register(code, "격리검증", "01", null);
            status.setRollbackOnly();
            return null;
        });

        // then — ★등록은 자체 트랜잭션(REQUIRES_NEW)에서 이미 커밋됐으므로 남아 있다.
        //   propagation 이 REQUIRED 로 되돌아가면 이 행이 함께 롤백돼 이 단언이 깨진다.
        assertThat(repository.findById(code)).isPresent();
    }

    @Test
    @DisplayName("자동등록_내부_DB오류가_호출자_트랜잭션을_오염시키지_않는다")
    void 자동등록_내부_DB오류가_호출자_트랜잭션을_오염시키지_않는다() {
        // given — 등록 문을 <서버 측에서> 실패시키는 스크래치 제약. ON CONFLICT 로는 막을 수 없는
        //   오류 계열(잠금 대기·데드락·타임아웃·제약 위반)을 대표한다.
        addPoisonConstraint();
        String poisonCode = PREFIX + "PO";

        // when — 호출자 트랜잭션 안에서 ①등록 실패 → ②호출자 자신의 INSERT → ③커밋
        boolean registered = Boolean.TRUE.equals(txTemplate.execute(status -> {
            boolean result = autoRegistrar.register(poisonCode, "ITISO-POISON", "01", null);
            // 호출자가 자기 일을 계속한다 — 참여 트랜잭션이었다면 여기서
            //   "current transaction is aborted" 로 실패하고 커밋 자체가 롤백된다.
            jdbc.update("INSERT INTO ls_evnt_type (evnt_type_cd, evnt_nm, evnt_clsf_cd, clct_yn)"
                    + " VALUES (?, '생존', '01', 'Y')", PREFIX + "OK");
            return result;
        }));

        // then — 등록은 실패(false)로 <조용히> 흡수되고, 호출자의 작업은 정상 커밋된다.
        assertThat(registered).isFalse();
        assertThat(repository.findById(PREFIX + "OK")).isPresent();
        assertThat(repository.findById(poisonCode)).isEmpty();
    }

    @Test
    @DisplayName("캐시_무효화는_커밋_이후에만_일어난다")
    void 캐시_무효화는_커밋_이후에만_일어난다() {
        // given — 캐시에 값을 심어 두고, 트랜잭션 안에서 무효화를 요청한다
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        assertThat(cache).isNotNull();
        cache.put("itiso-probe", "before");

        // when / then — 트랜잭션 <안>에서는 아직 비워지지 않아야 한다.
        //   커밋 전에 비우면 다른 요청이 미커밋 상태를 읽어 옛 값으로 캐시를 다시 채운다(TTL 고착).
        txTemplate.executeWithoutResult(status -> {
            cacheEvictor.evictAfterCommit();
            assertThat(cache.get("itiso-probe"))
                    .as("커밋 전에는 캐시가 유지돼야 한다(경합 창 차단)")
                    .isNotNull();
        });

        // then — 커밋 직후 비워진다
        assertThat(cache.get("itiso-probe")).as("커밋 이후 캐시가 비워져야 한다").isNull();
    }

    @Test
    @DisplayName("롤백되면_캐시는_비우지_않는다")
    void 롤백되면_캐시는_비우지_않는다() {
        // given
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        assertThat(cache).isNotNull();
        cache.put("itiso-probe2", "keep");

        // when — 트랜잭션이 롤백되면 바뀐 것이 없으므로 캐시를 건드릴 이유도 없다
        txTemplate.executeWithoutResult(status -> {
            cacheEvictor.evictAfterCommit();
            status.setRollbackOnly();
        });

        // then — 장수명 캐시를 근거 없이 날리지 않는다
        assertThat(cache.get("itiso-probe2")).isNotNull();
    }

    @Test
    @DisplayName("트랜잭션_밖에서는_즉시_무효화한다")
    void 트랜잭션_밖에서는_즉시_무효화한다() {
        // given — 커밋을 기다릴 대상이 없으면 지연시켰을 때 영영 실행되지 않는다
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        assertThat(cache).isNotNull();
        cache.put("itiso-probe3", "before");

        // when
        cacheEvictor.evictAfterCommit();

        // then
        assertThat(cache.get("itiso-probe3")).isNull();
    }
}
