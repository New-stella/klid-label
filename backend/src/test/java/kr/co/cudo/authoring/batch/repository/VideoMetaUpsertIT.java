package kr.co.cudo.authoring.batch.repository;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LsDataMetaRepository#upsertMeta} 의 <b>실 PostgreSQL(Testcontainer) 원자 upsert 검증</b> —
 * Phase 2 DEV_FIX 2(race 근본 해결).
 *
 * <p>기존 단위 테스트(Mockito)는 PostgreSQL 이 UK 위반 시 트랜잭션을 abort 시키는 실제 동작을
 * 재현하지 못한다. 본 IT 는 read-then-write + 인라인 재시도(PG 에서 cosmetic)를 대체한
 * {@code ON CONFLICT} 원자 upsert 가 실 DB 에서 다음을 만족함을 런타임 고정한다.
 *
 * <ol>
 *   <li>최초 삽입 → 1행, MDFCN_DT 는 NULL(신규 삽입 의미 보존).</li>
 *   <li>동일 (rawSn, metaKey) 재upsert → <b>1행 유지</b> + META_VL 갱신 + MDFCN_DT 세팅(멱등, UK 위반 없음).</li>
 *   <li>서로 다른 키 6건 → 6행.</li>
 * </ol>
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 모든 Spring 컨텍스트에
 * 자동 주입한다. {@code LsDataMetaRepository} 는 {@code @ControlRepo} 이므로
 * {@code controlTransactionManager} 트랜잭션 안에서 동작한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class VideoMetaUpsertIT {

    @Autowired
    private LsDataMetaRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("upsertMeta_최초삽입시_1행_생성되고_MDFCN_DT는_null이다")
    void upsertMeta_최초삽입시_1행_MDFCN_DT_null() {
        // given — 아직 존재하지 않는 (rawSn, key)
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);

        // when
        repository.upsertMeta(rawSn, "video.fps", "29.97");

        // then — 1행 삽입, META_VL 저장, MDFCN_DT 는 NULL(신규)
        List<LsDataMeta> rows = repository.findByRawSn(rawSn);
        assertThat(rows).hasSize(1);
        LsDataMeta row = rows.get(0);
        assertThat(row.getMetaKey()).isEqualTo("video.fps");
        assertThat(row.getMetaVl()).isEqualTo("29.97");
        assertThat(row.getRegDt()).isNotNull();
        assertThat(row.getMdfcnDt()).isNull();
    }

    @Test
    @DisplayName("동일키_재upsert시_UK위반없이_1행유지_값과_MDFCN_DT_갱신된다")
    void 동일키_재upsert_멱등_1행유지_값갱신() {
        // given — 최초 삽입
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        repository.upsertMeta(rawSn, "video.fps", "29.97");

        // when — 동일 (rawSn, metaKey) 로 다른 값 재upsert (UK 위반 상황을 원자적으로 해소)
        repository.upsertMeta(rawSn, "video.fps", "30.0");

        // then — 중복 행 없이 1행 유지 + 값 갱신 + MDFCN_DT 세팅(update 경로)
        List<LsDataMeta> rows = repository.findByRawSn(rawSn);
        assertThat(rows).hasSize(1);
        LsDataMeta row = rows.get(0);
        assertThat(row.getMetaVl()).isEqualTo("30.0");
        assertThat(row.getMdfcnDt()).isNotNull();
    }

    @Test
    @DisplayName("서로다른_6개키_upsert시_6행_생성된다")
    void 서로다른_6개키_6행() {
        // given
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);

        // when — video.* 6키를 각각 원자 upsert
        repository.upsertMeta(rawSn, "video.fps", "29.97");
        repository.upsertMeta(rawSn, "video.codec", "h264");
        repository.upsertMeta(rawSn, "video.bit_rate", "4500000");
        repository.upsertMeta(rawSn, "video.duration_ms", "12500");
        repository.upsertMeta(rawSn, "video.filesize", "6789012");
        repository.upsertMeta(rawSn, "video.resolution", "1920x1080");

        // then — 6행
        List<LsDataMeta> rows = repository.findByRawSn(rawSn);
        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(LsDataMeta::getMetaKey)
                .containsExactlyInAnyOrder("video.fps", "video.codec", "video.bit_rate",
                        "video.duration_ms", "video.filesize", "video.resolution");
    }
}
