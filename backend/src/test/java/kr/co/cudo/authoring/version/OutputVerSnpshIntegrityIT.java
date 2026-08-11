package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.service.OutputVersionStamper;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회차↔스냅샷 매핑({@code LS_OUTPUT_VER_SNPSH}, V183)의 <b>참조 무결성</b>과 <b>재마감 정합성</b>을
 * 실제 DB 로 고정한다.
 *
 * <h3>① 영상 삭제 시 매핑이 남으면 조용한 고아다 (DEV_FIX 2라운드 이슈 1)</h3>
 * {@code LS_DATA_RAW} 자식 테이블은 <b>전역 정책상 {@code ON DELETE CASCADE}</b> 다(V146 — 선존
 * {@code LS_LABEL_VERSION}·{@code LS_DATASET_EXPORT}·{@code LS_DATA_SRC} 포함 27개). 실삭제 경로
 * ({@code AugmentDiscardPurgeTxService} Phase 7 · {@code ResolutionPersistService.deleteFailedDerivativeRaw}
 * · {@code TusUploadService} 롤백)는 자식 정리를 이 CASCADE 에 위임한다. 신규 테이블만 FK 를 빠뜨리면
 * 영상이 사라져도 매핑만 남아 <b>존재하지 않는 영상·스냅샷을 가리키는 행</b>이 영구 잔존한다 —
 * 예외도 실패 신호도 없다("FK 가 없으면 <b>조용히</b> 고아가 남는다", CLAUDE.md).
 *
 * <h3>② 같은 회차를 재마감하면 매핑도 그 회차의 실제 내용으로 갱신돼야 한다 (이슈 2)</h3>
 * 산출은 실패 후 <b>같은 {@code OUTPUT_VER_NO} 로 재시도</b>된다({@code claimForRetry} →
 * {@code finalizeUnlessUnderDeidentReport} 재진입). 그 사이 ACTIVE 스냅샷이 바뀌면 산출 폴더는 새
 * 내용으로 재생성되는데 매핑만 첫 시도 값에 고정된다 — <b>매핑과 실제 산출 내용의 불일치</b>이며,
 * 이 테이블이 없애려던 바로 그 결함(조용한 오복원)이 좁은 형태로 남는 것이다.
 * 단 <b>바뀐 게 없으면 아무 행도 건드리지 않는다</b>(재실행 멱등 — 아래 세 번째 테스트).
 *
 * @design D5
 * @req R6
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class OutputVerSnpshIntegrityIT {

    @Autowired private VersionService versionService;
    @Autowired private OutputVersionStamper outputVersionStamper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsLabelVersionRepository labelVersionRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

    @Autowired
    @Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    private JdbcTemplate jdbc;
    private Long rawSn;
    private Long frameF;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-SNPSH-" + System.nanoTime(), "CCTV-SNPSH", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/snpsh.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        frameF = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "/frames/raw/snpsh/0.jpg", LocalDateTime.now())).getSrcSn();
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("영상을_삭제하면_회차_매핑도_함께_사라진다 — FK_CASCADE_없이는_조용한_고아다")
    void 영상을_삭제하면_회차_매핑도_함께_사라진다() {
        // given — v1 마감으로 매핑이 1건 생긴다
        replaceLabel(frameF, "person");
        approveAndFinalize(1);
        assertThat(mappingCount()).isEqualTo(1);

        // when — 실삭제 경로가 하는 것과 같은 영상 행 삭제(자식 정리는 V146 CASCADE 에 위임한다)
        jdbc.update("DELETE FROM ls_data_raw WHERE raw_sn = ?", rawSn);

        // then — 매핑도 함께 사라져야 한다. 남으면 존재하지 않는 영상·스냅샷을 가리키는 고아다.
        assertThat(mappingCount())
                .as("영상이 사라졌는데 회차 매핑만 남으면 예외도 실패 신호도 없는 조용한 고아다")
                .isZero();
        // 선존 자식(V146 CASCADE 대상)도 같은 삭제로 정리된다 — 신규 테이블만 방침이 다를 이유가 없다.
        assertThat(labelVersionRowCount())
                .as("선존 LS_LABEL_VERSION 은 V146 이 CASCADE 를 걸어 두었다(같은 정책의 대조군)")
                .isZero();
    }

    @Test
    @DisplayName("같은_회차를_다른_ACTIVE_스냅샷으로_다시_마감하면_매핑이_그_스냅샷으로_갱신된다")
    void 같은_회차를_다른_ACTIVE_스냅샷으로_다시_마감하면_매핑이_갱신된다() {
        // given — v1 마감(내용 A)
        replaceLabel(frameF, "person");
        approveAndFinalize(1);
        Long firstMapped = mappedSnapshotSn(1, frameF);
        assertThat(firstMapped).isEqualTo(activeSnapshotSn(frameF));

        // when — 산출이 실패해 <같은 회차>로 재시도되는 사이 내용이 B 로 바뀌어 ACTIVE 가 교체된다
        replaceLabel(frameF, "car");
        approveAndFinalize(1);

        // then — 산출 폴더는 새 내용으로 재생성되므로 매핑도 그 회차의 실제 내용을 가리켜야 한다.
        Long activeNow = activeSnapshotSn(frameF);
        assertThat(activeNow).isNotEqualTo(firstMapped);
        assertThat(mappedSnapshotSn(1, frameF))
                .as("첫 시도 값에 고정되면 매핑과 실제 산출 내용이 어긋난다(조용한 오복원의 좁은 재현)")
                .isEqualTo(activeNow);
        // 갱신이지 적층이 아니다 — UK(영상, 프레임, 회차)는 그대로 1건이다.
        assertThat(mappingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은_회차를_같은_스냅샷으로_다시_마감하면_행을_건드리지_않는다 — 재실행_멱등")
    void 같은_회차를_같은_스냅샷으로_다시_마감하면_행을_건드리지_않는다() {
        // given
        replaceLabel(frameF, "person");
        approveAndFinalize(1);
        Timestamp before = mappedRegDt(1, frameF);

        // when — 내용 무변경 재마감(산출 재시도)
        approveAndFinalize(1);

        // then — 값이 같으면 쓰기 자체가 없다(불필요한 dead tuple·잠금을 만들지 않는다).
        assertThat(mappedRegDt(1, frameF)).isEqualTo(before);
        assertThat(mappingCount()).isEqualTo(1);
    }

    // ---------- 헬퍼 ----------

    private int mappingCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ls_output_ver_snpsh WHERE data_raw_sn = ?", Integer.class, rawSn);
    }

    private int labelVersionRowCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ls_label_version WHERE data_raw_sn = ?", Integer.class, rawSn);
    }

    private Long mappedSnapshotSn(int outputVerNo, Long srcSn) {
        return jdbc.queryForObject(
                "SELECT lbl_ver_sn FROM ls_output_ver_snpsh "
                        + "WHERE data_raw_sn = ? AND data_src_sn = ? AND output_ver_no = ?",
                Long.class, rawSn, srcSn, outputVerNo);
    }

    private Timestamp mappedRegDt(int outputVerNo, Long srcSn) {
        return jdbc.queryForObject(
                "SELECT reg_dt FROM ls_output_ver_snpsh "
                        + "WHERE data_raw_sn = ? AND data_src_sn = ? AND output_ver_no = ?",
                Timestamp.class, rawSn, srcSn, outputVerNo);
    }

    private Long activeSnapshotSn(Long srcSn) {
        return labelVersionRepository.findByDataRawSnAndDataSrcSnAndActiveYn(
                        rawSn, srcSn, LsLabelVersion.ACTIVE_YES)
                .stream().findFirst().orElseThrow().getLabelVersionSn();
    }

    /** 프레임의 작업본 라벨을 지정 라벨 1건으로 교체한다(라벨 저장은 버전을 만들지 않는다). */
    private void replaceLabel(Long srcSn, String label) {
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        labelRepository.flush();
        labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, label, "[[10.0,10.0],[50.0,50.0]]", 1L));
        labelRepository.flush();
    }

    /** 검수 승인(스냅샷 생성) + 산출 마감(회차 확정)을 프로덕션과 같은 순서로 재현한다. */
    private void approveAndFinalize(int outputVerNo) {
        versionService.commitApproved(rawSn, reviewer);
        new TransactionTemplate(controlTxManager).executeWithoutResult(
                status -> outputVersionStamper.stamp(rawSn, outputVerNo));
    }
}
