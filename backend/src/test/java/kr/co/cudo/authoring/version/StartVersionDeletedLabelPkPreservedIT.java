package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.service.VideoLabelSaveService;
import kr.co.cudo.authoring.version.dto.VersionLabelsResponse;
import kr.co.cudo.authoring.version.service.OutputVersionStamper;
import kr.co.cudo.authoring.version.service.StartVersionService;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6 — 과거 산출 회차를 불러와 확정 저장할 때 <b>그 사이 삭제됐던 라벨</b>이 옛 {@code LBL_SN} 으로
 * 되살아나는지 고정하는 재현 IT.
 *
 * <h3>이 IT 가 막는 결함</h3>
 * 저장 코어의 신규/기존 분기({@code LabelService.isNewLabel})는 "그 프레임에 <b>실재하는</b> 라벨인가"로
 * 판정한다. 회차 스냅샷에 있던 라벨이 그 사이 삭제되면 실재하지 않으므로 <b>새 PK 가 발급</b>되고,
 * 시각적으로 같은 내용인데도 payload 의 {@code id} 가 달라진다. 그 결과 셋이 함께 어긋난다:
 * <ol>
 *   <li><b>diff 오분류</b> — 비교축에 {@code id} 가 있어 내용이 같은데 {@code REMOVED + ADDED} 쌍이 된다.</li>
 *   <li><b>산출물 불필요 재생성</b> — 콘텐츠 해시 입력에 {@code lblSn} 이 들어가므로 해시가 달라져
 *       새 버전 폴더 + 이미지 2벌이 적층된다(CWE-770 — 이미 같은 부류를 닫은 전례가 있다).</li>
 *   <li><b>스냅샷 행 중복</b> — 재승인 시 "같은 내용의 비활성 스냅샷 재사용" 경로가 발동하지 못해
 *       새 행이 쌓인다(프레임당 payload 최대 10MB).</li>
 * </ol>
 *
 * <p><b>롤백 경로는 정확히 이 이유로 {@code LBL_SN} 을 의도적으로 보존한다</b>(D-ISSUE-22 —
 * "PK 재발급 시 diff 가 전량 교체로 오분류되므로 점유된 PK 만 신규 발급 폴백"). 확정 저장 경로에만
 * 그 방어가 빠져 있던 <b>비대칭</b>을 고정한다.
 *
 * <p>승인·산출 마감은 {@code StartVersionRollbackReproIT} 와 같은 조합
 * ({@code commitApproved} + {@code OutputVersionStamper.stamp})으로 재현한다 — 그 둘이 프로덕션에서
 * 회차를 확정하는 유일한 지점이고 파일 산출·통지는 이 결함의 판정과 무관하다.
 *
 * @design API-196
 * @req R6
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StartVersionDeletedLabelPkPreservedIT {

    @Autowired private VersionService versionService;
    @Autowired private StartVersionService startVersionService;
    @Autowired private VideoLabelSaveService videoLabelSaveService;
    @Autowired private OutputVersionStamper outputVersionStamper;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager controlTxManager;

    @Autowired
    @Qualifier("controlDataSource")
    private javax.sql.DataSource controlDataSource;

    private Long rawSn;
    /** 라벨 1건이 삭제됐다가 회차 불러오기로 되살아나는 프레임. */
    private Long frameF;
    /** 대조 프레임 — 라벨이 삭제되지 않아 복원 대상이 없다(무관 프레임이 함께 깨지지 않는지). */
    private Long frameG;

    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-PKRESTORE-" + System.nanoTime(), "CCTV-PKRESTORE", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/pkrestore.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        frameF = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "/frames/raw/pkrestore/0.jpg", LocalDateTime.now())).getSrcSn();
        frameG = srcRepository.save(
                LsDataSrc.create(rawSn, 1, "/frames/raw/pkrestore/1.jpg", LocalDateTime.now())).getSrcSn();
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("삭제됐던_라벨이_회차_불러오기_확정저장에서_옛_LBL_SN_으로_되살아난다")
    void 삭제됐던_라벨이_옛_LBL_SN_으로_되살아난다() {
        Long removed = givenApprovedV1ThenDeletedInV2();

        // when — 회차 1 을 불러와(API-195) 확정 저장(API-196)
        confirmLoadedVersion(1);

        // then — 되살아난 라벨의 PK 가 v1 의 그것과 같다. 새 PK 가 발급되면 diff 가 내용이 같은데도
        //   REMOVED+ADDED 쌍으로 오분류되고 export 가 v{n+1} 로 불필요 재생성된다.
        assertThat(lblSnsOf(frameF))
                .as("스냅샷에 있던 라벨은 옛 LBL_SN 그대로 되살아나야 한다(롤백 경로와 같은 규약)")
                .contains(removed);
    }

    @Test
    @DisplayName("옛_LBL_SN_이_보존되면_재승인이_스냅샷_행을_늘리지_않는다")
    void 재승인이_스냅샷_행을_늘리지_않는다() {
        givenApprovedV1ThenDeletedInV2();
        int rowsBefore = versionRowCount(frameF);
        assertThat(rowsBefore).as("v1·v2 두 회차의 스냅샷 2건이 전제다").isEqualTo(2);

        // given — 회차 1 을 불러와 확정 저장
        confirmLoadedVersion(1);

        // when — 재승인. 작업본이 v1 스냅샷과 <b>바이트까지 같아야</b> 재사용 경로가 발동한다.
        versionService.commitApproved(rawSn, reviewer);

        // then ① 같은 내용의 비활성 스냅샷을 다시 정본으로 삼아 행이 늘지 않는다
        //   (이 수정의 최종 관측 지표 — LBL_SN 이 달라지면 해시가 달라져 새 행이 적층된다)
        assertThat(versionRowCount(frameF))
                .as("LBL_SN 이 재발급되면 해시가 달라져 스냅샷 행이 적층된다(프레임당 payload 최대 10MB)")
                .isEqualTo(rowsBefore);
        // then ② 활성은 정확히 1건이다
        assertThat(activeRowCount(frameF)).isEqualTo(1);
    }

    // ---------- given ----------

    /**
     * v1 승인·마감(프레임 F 라벨 2건) → 그중 1건 삭제 → v2 승인·마감.
     *
     * @return 삭제된 라벨의 옛 {@code LBL_SN} (확정 저장이 되살려야 하는 PK)
     */
    private Long givenApprovedV1ThenDeletedInV2() {
        addLabel(frameF, "person");
        Long removed = addLabel(frameF, "car");
        addLabel(frameG, "person");
        approveAndFinalize(1);

        // 그 사이 라벨 1건이 삭제된다 — 「시작 버전 선택」이 정의상 다루는 정상 상황이다.
        labelRepository.deleteAllByIdInBatch(List.of(removed));
        approveAndFinalize(2);
        return removed;
    }

    /** 회차 하나를 불러와(API-195) 그대로 확정 저장한다(API-196) — 화면이 하는 일. */
    private void confirmLoadedVersion(int versionNo) {
        VersionLabelsResponse loaded = startVersionService.loadVersionLabels(rawSn, versionNo, reviewer);
        List<VideoLabelSaveRequest.FrameVersion> versions = loaded.frames().stream()
                .map(f -> new VideoLabelSaveRequest.FrameVersion(f.srcSn(), f.lblVer()))
                .toList();
        videoLabelSaveService.save(rawSn,
                new VideoLabelSaveRequest(loaded.version(), versions, List.of()), reviewer);
    }

    // ---------- 헬퍼 ----------

    private Long addLabel(Long srcSn, String label) {
        LsDataLbl saved = labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, label, "[[10.0,10.0],[50.0,50.0]]", 1L));
        labelRepository.flush();
        return saved.getLblSn();
    }

    private List<Long> lblSnsOf(Long srcSn) {
        return labelRepository.findBySrcSn(srcSn).stream().map(LsDataLbl::getLblSn).sorted().toList();
    }

    /** 검수 승인(스냅샷 생성) + 산출 마감(회차 번호 확정)을 프로덕션과 같은 순서로 재현한다. */
    private void approveAndFinalize(int outputVerNo) {
        versionService.commitApproved(rawSn, reviewer);
        new TransactionTemplate(controlTxManager).executeWithoutResult(
                status -> outputVersionStamper.stamp(rawSn, outputVerNo));
    }

    private int versionRowCount(Long srcSn) {
        return new JdbcTemplate(controlDataSource).queryForObject(
                "SELECT count(*) FROM ls_label_version WHERE data_raw_sn = ? AND data_src_sn = ?",
                Integer.class, rawSn, srcSn);
    }

    private int activeRowCount(Long srcSn) {
        return new JdbcTemplate(controlDataSource).queryForObject(
                "SELECT count(*) FROM ls_label_version "
                        + "WHERE data_raw_sn = ? AND data_src_sn = ? AND actvtn_yn = 'Y'",
                Integer.class, rawSn, srcSn);
    }
}
