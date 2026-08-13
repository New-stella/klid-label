package kr.co.cudo.authoring.version;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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
 * <h3>★② 한 번 쓰인 회차 매핑은 <b>불변</b>이다 (2026-08-12 사용자 확정, 구속)</h3>
 * 각 회차는 이전 회차들과 간섭해선 안 된다 — 그래야 검수 완료 시점마다 만들어진 데이터마트가 각각
 * 유지된다. 이 매핑이 "회차 N 의 내용은 어느 스냅샷이었는가"의 단일 원천이므로, 나중 쓰기가 과거
 * 회차의 행을 덮으면 그 회차의 산출물 기준이 소급해 바뀐다. 그래서 {@code ON CONFLICT DO NOTHING} 으로
 * DB 문장 자체가 불변을 강제하고, 건너뛴 사실은 <b>WARN 으로 가시화</b>한다(조용한 stale 금지).
 *
 * <p><b>구 정책({@code DO UPDATE} — 재마감 시 최신 ACTIVE 스냅샷으로 갱신)은 폐기됐다.</b> 그 근거는
 * *"산출은 실패 후 같은 {@code OUTPUT_VER_NO} 로 재시도된다"* 였는데 채번을 따라가면 성립하지 않는다:
 * 실패 회수({@code DatasetExportFailureRecoverer})는 {@code runApprovalAsync} 로 산출을 처음부터 재진입
 * 하고 {@code insertNextVersion} 이 {@code countByDataRawSn() + 1} 로 <b>새 번호</b>를 받는다. 이 IT 의
 * 두 번째 테스트는 그 구 정책을 검증하던 것이라 <b>불변 검증으로 뒤집혔다</b>(같은 시나리오·반대 기대).
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

    /**
     * ★구 정책({@code DO UPDATE} 갱신)을 검증하던 테스트를 <b>불변 검증으로 뒤집은 것</b>이다 —
     * 시나리오는 그대로이고 기대만 반대다. 폐기 사유는 클래스 주석 §② 참조.
     */
    @Test
    @DisplayName("이미_기록된_회차_매핑은_다시_쓰이지_않는다 — 회차_불변")
    void 이미_기록된_회차_매핑은_다시_쓰이지_않는다() {
        // given — v1 마감(내용 A)
        replaceLabel(frameF, "person");
        approveAndFinalize(1);
        Long firstMapped = mappedSnapshotSn(1, frameF);
        assertThat(firstMapped).isEqualTo(activeSnapshotSn(frameF));

        // when — 내용이 B 로 바뀐 뒤 <같은 회차 번호>로 다시 마감이 시도된다
        replaceLabel(frameF, "car");
        approveAndFinalize(1);

        // then — 회차 1 의 매핑은 처음 기록된 스냅샷 그대로다. 덮으면 이미 산출·통지된 회차의 기준이
        //   소급해 바뀌어, 그 시점 데이터마트를 각각 유지할 수 없다.
        Long activeNow = activeSnapshotSn(frameF);
        assertThat(activeNow)
                .as("전제 확인 — 현재 정본은 내용 B 의 스냅샷으로 교체돼 있다")
                .isNotEqualTo(firstMapped);
        assertThat(mappedSnapshotSn(1, frameF))
                .as("한 번 쓰인 회차 매핑은 불변이다(각 회차는 이전 회차와 간섭하지 않는다)")
                .isEqualTo(firstMapped);
        // 적층도 아니다 — UK(영상, 프레임, 회차)는 그대로 1건이다.
        assertThat(mappingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("회차_매핑_충돌로_건너뛰면_경고를_남긴다 — 조용한_stale_금지")
    void 회차_매핑_충돌로_건너뛰면_경고를_남긴다() {
        // given — v1 매핑이 이미 기록돼 있다
        replaceLabel(frameF, "person");
        approveAndFinalize(1);

        Logger stamperLogger = (Logger) LoggerFactory.getLogger(OutputVersionStamper.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        stamperLogger.addAppender(appender);
        try {
            // when — 내용이 바뀐 뒤 같은 회차로 다시 마감된다(매핑은 불변이라 삽입이 건너뛰어진다)
            replaceLabel(frameF, "car");
            approveAndFinalize(1);

            // then — 건너뛴 사실이 WARN 으로 드러난다. 조용히 넘기면 매핑과 실제 산출 내용이 어긋난
            //   상태를 아무도 알 수 없다(그 경로가 실재하는지 자체가 미확인이라 관측이 유일한 근거다).
            assertThat(appender.list)
                    .as("기대 건수보다 적게 기록되면 WARN 이어야 한다")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .contains("output version mapping skipped")
                                .contains("rawSn=" + rawSn)
                                .contains("expected=1")
                                .contains("mapped=0");
                    });
        } finally {
            stamperLogger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * ★판정은 <b>보수적</b>이다 — 같은 스냅샷으로 다시 마감해도 경고한다. 잡음이 아니라 <b>의도</b>이니
     * "무해한 멱등에 경고가 뜬다"고 조건을 좁히지 말 것.
     *
     * <p>근거: {@code stamp} 는 프로덕션에서 <b>(영상, 회차)당 정확히 1회</b> 호출된다 — 산출 1회당
     * export 행 1건이고 그 행마다 번호가 새로 채번되며({@code insertNextVersion}), 마감 분기
     * (성공/부분)는 상호배타라 한 산출에서 두 번 부르지 않는다. 즉 <b>같은 회차 재마감 자체가</b>
     * 우리가 찾지 못한 경로이므로, 내용이 같든 다르든 알려야 한다. 건수 비교만으로 두 경우를 구분할 수
     * 없는데(둘 다 삽입 0건) 구분하려면 조회를 더 붙여야 하고, 그 정밀도로 얻을 것이 없다.
     */
    @Test
    @DisplayName("같은_스냅샷으로_다시_마감해도_경고한다 — 보수적_판정")
    void 같은_스냅샷으로_다시_마감해도_경고한다() {
        // given — v1 마감
        replaceLabel(frameF, "person");
        approveAndFinalize(1);

        Logger stamperLogger = (Logger) LoggerFactory.getLogger(OutputVersionStamper.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        stamperLogger.addAppender(appender);
        try {
            // when — 내용 무변경 재마감(같은 회차)
            approveAndFinalize(1);

            // then — 행은 그대로지만(위 멱등 테스트) 같은 회차가 두 번 마감된 사실은 알린다
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == Level.WARN)
                    .as("같은 회차 재마감 자체가 미확인 경로다 — 내용 동일 여부와 무관하게 알린다")
                    .isNotEmpty();
        } finally {
            stamperLogger.detachAppender(appender);
            appender.stop();
        }
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
