package kr.co.cudo.authoring.eventtype;

import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import kr.co.cudo.authoring.eventtype.service.EventTypeAutoRegistrar;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이벤트유형 <b>자동등록</b> 실동작 통합 테스트 (Testcontainers PostgreSQL, V168).
 *
 * <p>구 {@code MngExEvntTypeRepositoryIT}(관제 공유 마스터 READ 정합)를 대체한다 — 그 2종은 V168 로
 * 제거됐고, 이제 마스터는 <b>우리가 쓰는</b> 테이블이다. 따라서 고정해야 할 계약도 "읽히는가"가
 * 아니라 <b>쓰기 규약</b>이다:
 * <ol>
 *   <li>미등록 유형은 등록된다</li>
 *   <li>이미 등록된 유형은 인입값으로 <b>덮어쓰지 않는다</b>(운영자 정정 보호)</li>
 *   <li>동시 등록에도 중복·예외가 없다(CWE-362 — 2노드 Active-Active)</li>
 * </ol>
 *
 * <p>시드는 {@code @ActiveProfiles("local")} 의 {@code DevSeedRunner} 가 Flyway 이후 멱등 적재한다.
 * 이 테스트가 만드는 행은 고유 접두({@link #PREFIX})를 써 시드·다른 테스트와 겹치지 않게 하고
 * 종료 시 스스로 지운다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.dev.seed.enabled=true")
class LsEvntTypeAutoRegisterIT {

    /** 이 테스트가 만드는 유형코드 접두 — 컬럼 길이(20) 안에서 고유해야 한다. */
    private static final String PREFIX = "ITEVT";

    @Autowired private EventTypeAutoRegistrar autoRegistrar;
    @Autowired private LsEvntTypeRepository repository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ls_evnt_type WHERE evnt_type_cd LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("신규_유형코드가_인입되면_자동등록된다")
    void 신규_유형코드가_인입되면_자동등록된다() {
        // given — 마스터에 없는 유형코드(관제가 새로 쓰기 시작한 유형)
        String code = PREFIX + "NEW";
        assertThat(repository.findById(code)).isEmpty();

        // when — 인입 소비 시점의 자동등록
        boolean registered = autoRegistrar.register(code, "신규이벤트", "09", null);

        // then — 등록되고, 수집대상 기본값 Y 로 들어간다(인입으로 실제 들어온 유형이므로)
        assertThat(registered).isTrue();
        LsEvntType saved = repository.findById(code).orElseThrow();
        assertThat(saved.getEvntNm()).isEqualTo("신규이벤트");
        assertThat(saved.getEvntClsfCd()).isEqualTo("09");
        assertThat(saved.isCollected()).isTrue();
        assertThat(saved.getRegDt()).isNotNull();
    }

    @Test
    @DisplayName("관제가_보낸_유형명이_기존행에_반영된다")
    void 관제가_보낸_유형명이_기존행에_반영된다() {
        // given — 이관 시점에는 관제 수신명이 비어 있다(관제 마스터에 유형별 이름이 없었다)
        String code = PREFIX + "UPD";
        autoRegistrar.register(code, null, "01", "0001");

        // when — 관제가 유형별 이름을 보내기 시작한다
        boolean changed = autoRegistrar.register(code, "수위상승", "01", "0001");

        // then — ★갱신된다. DO NOTHING 이면 중복 이름이 영구 고착한다(이 규칙의 존재 이유).
        assertThat(changed).isTrue();
        assertThat(repository.findById(code).orElseThrow().getEvntNm()).isEqualTo("수위상승");
    }

    @Test
    @DisplayName("운영자_표시명이_있으면_관제_갱신이_표시를_바꾸지_않는다")
    void 운영자_표시명이_있으면_관제_갱신이_표시를_바꾸지_않는다() {
        // given — 운영자가 관리 화면에서 표시명을 정한 행(관제 칸과 <다른 칸>이다)
        String code = PREFIX + "KEEP";
        autoRegistrar.register(code, "관제원본명", "01", "0001");
        jdbc.update("UPDATE ls_evnt_type SET optr_indct_nm = ? WHERE evnt_type_cd = ?",
                "운영자표시명", code);

        // when — 관제가 새 유형명을 보낸다
        boolean changed = autoRegistrar.register(code, "관제신규명", "01", "0001");

        // then — ★관제 칸은 갱신되지만(원본 보존) <표시명은 운영자 값 그대로>다.
        //   두 칸이 분리돼 있어 별도 표식 컬럼 없이 보호가 성립한다.
        assertThat(changed).isTrue();
        LsEvntType saved = repository.findById(code).orElseThrow();
        assertThat(saved.getEvntNm()).isEqualTo("관제신규명");
        assertThat(saved.getOptrIndctNm()).isEqualTo("운영자표시명");
        assertThat(EventTypeDisplayNamePolicy.resolve(saved.getOptrIndctNm(), saved.getEvntNm(),
                null, saved.getEvntTypeCd())).isEqualTo("운영자표시명");
    }

    @Test
    @DisplayName("운영자_표시명을_지우면_관제값으로_복귀한다")
    void 운영자_표시명을_지우면_관제값으로_복귀한다() {
        // given — 표시명이 지정된 행
        String code = PREFIX + "RSTR";
        autoRegistrar.register(code, "관제원본명", "01", "0001");
        jdbc.update("UPDATE ls_evnt_type SET optr_indct_nm = ? WHERE evnt_type_cd = ?", "임시명", code);

        // when — 운영자가 표시명을 해제한다
        jdbc.update("UPDATE ls_evnt_type SET optr_indct_nm = NULL WHERE evnt_type_cd = ?", code);

        // then — ★관제 원본이 유실되지 않아 자연 복귀한다(되돌릴 수 없는 차단 없음)
        LsEvntType saved = repository.findById(code).orElseThrow();
        assertThat(EventTypeDisplayNamePolicy.resolve(saved.getOptrIndctNm(), saved.getEvntNm(),
                null, saved.getEvntTypeCd())).isEqualTo("관제원본명");
    }

    @Test
    @DisplayName("이름이_같으면_불필요한_UPDATE가_발생하지_않는다")
    void 이름이_같으면_불필요한_UPDATE가_발생하지_않는다() {
        // given — 매 인입마다 같은 값이 온다(정상 운영의 대다수 경로)
        String code = PREFIX + "NOOP";
        autoRegistrar.register(code, "동일이름", "01", null);

        // when
        boolean changed = autoRegistrar.register(code, "동일이름", "01", null);

        // then — ★변화 없음(false). no-op UPDATE 는 행 잠금·WAL·캐시 evict 를 매번 유발한다.
        assertThat(changed).isFalse();
    }

    @Test
    @DisplayName("관제_미송신_값은_기존_값을_지우지_않는다")
    void 관제_미송신_값은_기존_값을_지우지_않는다() {
        // given — 이름·분류가 채워진 행
        String code = PREFIX + "NULL";
        autoRegistrar.register(code, "보존대상", "01", null);

        // when — 관제가 이름·분류 없이 유형코드만 보낸다
        boolean changed = autoRegistrar.register(code, null, null, null);

        // then — 미송신이 기존 값을 <지우면> 안 된다(null 이 사실을 덮어쓰지 않는다)
        assertThat(changed).isFalse();
        LsEvntType saved = repository.findById(code).orElseThrow();
        assertThat(saved.getEvntNm()).isEqualTo("보존대상");
        assertThat(saved.getEvntClsfCd()).isEqualTo("01");
    }

    @Test
    @DisplayName("수집여부는_인입이_되돌리지_않는다")
    void 수집여부는_인입이_되돌리지_않는다() {
        // given — 운영자가 숨긴(CLCT_YN='N') 유형. 관제는 이 값을 보내지 않는다(인입에 컬럼이 없다).
        String code = PREFIX + "HIDE";
        autoRegistrar.register(code, "숨긴유형", "01", null);
        jdbc.update("UPDATE ls_evnt_type SET clct_yn = 'N' WHERE evnt_type_cd = ?", code);

        // when — 이름이 바뀌는 인입이 다시 온다(갱신 경로를 실제로 태운다)
        autoRegistrar.register(code, "이름변경", "01", null);

        // then — ★수집여부는 인입 기본값 'Y' 로 되살아나면 안 된다(운영자가 숨긴 유형이 부활한다)
        LsEvntType saved = repository.findById(code).orElseThrow();
        assertThat(saved.isCollected()).isFalse();
        assertThat(saved.getEvntNm()).isEqualTo("이름변경");
    }

    @Test
    @DisplayName("이름_없이_들어온_유형도_등록되고_라벨은_원문_폴백한다")
    void 이름_없이_들어온_유형도_등록되고_라벨은_원문_폴백한다() {
        // given — 관제가 코드만 보내고 이름·분류를 안 보낸 경우(폴백으로 지어내지 않는다)
        String code = PREFIX + "NONM";

        // when
        boolean registered = autoRegistrar.register(code, "   ", null, null);

        // then — 등록은 되고 이름·분류는 null 이다(대용값 없음)
        assertThat(registered).isTrue();
        LsEvntType saved = repository.findById(code).orElseThrow();
        assertThat(saved.getEvntNm()).isNull();
        assertThat(saved.getEvntClsfCd()).isNull();
    }

    @Test
    @DisplayName("빈_유형코드는_등록하지_않는다")
    void 빈_유형코드는_등록하지_않는다() {
        // when / then — null/공백은 no-op (빈 PK 행을 만들지 않는다)
        assertThat(autoRegistrar.register(null, "x", "01", null)).isFalse();
        assertThat(autoRegistrar.register("   ", "x", "01", null)).isFalse();
    }

    @Test
    @DisplayName("동시_인입에도_유형이_중복등록되지_않는다")
    void 동시_인입에도_유형이_중복등록되지_않는다() throws Exception {
        // given — 2노드 Active-Active 에서 같은 신규 유형이 동시에 인입되는 상황.
        //   ★DO UPDATE 는 DO NOTHING 보다 잠금 경합이 크다(같은 행에 row lock 을 잡는다) —
        //     절반은 <값이 다른> 인입으로 보내 갱신 경로까지 동시에 태운다(교착 회귀 가드).
        String code = PREFIX + "RACE";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String name = (i % 2 == 0) ? "동시등록" : "동시갱신";
            tasks.add(() -> autoRegistrar.register(code, name, "03", null));
        }

        // when — 동시에 등록 시도
        List<Future<Boolean>> results;
        try {
            results = pool.invokeAll(tasks);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        // then — 예외 없이 전부 완주하고 <행은 정확히 1건>이다(중복 등록·교착 없음).
        //   조회 후 INSERT 였다면 PK 위반이 나고, PostgreSQL 은 그 트랜잭션을 통째로 abort 시켜
        //   함께 진행 중이던 영상 적재까지 롤백시킨다.
        long affected = 0;
        for (Future<Boolean> f : results) {
            if (Boolean.TRUE.equals(f.get())) {
                affected++;
            }
        }
        // 최소 1건(최초 등록)은 반드시 성공하고, 나머지는 등록/갱신/no-op 중 하나로 조용히 끝난다.
        assertThat(affected).isBetween(1L, (long) threads);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ls_evnt_type WHERE evnt_type_cd = ?", Integer.class, code);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("컬럼길이를_넘는_이름은_잘라서_등록하고_적재를_막지_않는다")
    void 컬럼길이를_넘는_이름은_잘라서_등록하고_적재를_막지_않는다() {
        // given — 관제가 200자를 넘는 이름을 보낸 경우(수신값은 신뢰 경계 밖)
        String code = PREFIX + "LONG";
        String longName = "가".repeat(300);

        // when
        boolean registered = autoRegistrar.register(code, longName, "01", null);

        // then — 예외 없이 등록되고 컬럼 길이(명V200)로 잘린다
        assertThat(registered).isTrue();
        assertThat(repository.findById(code).orElseThrow().getEvntNm()).hasSize(200);
    }
}
