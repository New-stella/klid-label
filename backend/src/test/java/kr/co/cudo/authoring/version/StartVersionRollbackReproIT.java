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
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
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
 * R6 — 「시작 버전 선택」이 <b>그 회차에 실제로 산출된 내용</b>으로 되돌리는지 고정하는 재현 IT.
 *
 * <h3>이 IT 가 막는 결함 (DEV_FIX 이슈 1)</h3>
 * 회차↔스냅샷 대응을 {@code LS_LABEL_VERSION.VER_NO} <b>단일 컬럼</b>으로 표현하면
 * "한 스냅샷이 여러 회차의 내용일 수 있다"(1:N)를 담지 못한다. 아래 4단계에서 그 결함이 드러난다:
 * <ol>
 *   <li>{@code v1} 승인 — 프레임 F 의 내용 A 가 스냅샷 {@code rA}({@code VER_NO=1})로 확정</li>
 *   <li>{@code v2} 승인 — 내용 B 가 스냅샷 {@code rB}({@code VER_NO=2})로 확정</li>
 *   <li><b>프레임 단위 롤백</b>으로 {@code rA} 재활성 — {@code rB} 는 비활성이지만 {@code VER_NO=2} 를
 *       단 채 남는다</li>
 *   <li>{@code v3} 승인·산출 — F 의 내용은 A 그대로라 새 스냅샷이 생기지 않고({@code (DATA_SRC_SN,
 *       VERSION_HASH)} UNIQUE 멱등) {@code rA} 에 새 번호도 찍히지 않는다(채번은 미채번 행만 대상)</li>
 * </ol>
 * 이 상태에서 <b>시작 버전 3</b> 을 고르면 번호 기반 규칙({@code VER_NO <= 3} 중 최대)이
 * <b>비활성 {@code rB}(2)</b> 를 골라 <b>{@code v3} 에 존재한 적 없는 내용 B</b> 로 되돌린다.
 * 예외도 없고 {@code unresolvedFrames} 에도 잡히지 않는 <b>조용한 오복원</b>이다.
 *
 * <p>따라서 회차↔스냅샷 대응은 별도 매핑({@code LS_OUTPUT_VER_SNPSH})이 소유하며, 이 IT 는 그
 * 매핑이 실제 DB·실제 서비스 경로에서 동작하는지를 <b>산출 회차를 실제로 마감시켜</b> 검증한다.
 *
 * <p>승인·산출 마감을 {@code ReviewService}/{@code DatasetExportService} 전체가 아니라
 * {@code commitApproved} + {@code OutputVersionStamper.stamp} 조합으로 재현한다 — 그 둘이
 * 프로덕션에서 회차를 확정하는 유일한 지점이고(승인 스냅샷 생성 / 산출 마감 트랜잭션),
 * 파일 산출·통지는 이 결함의 판정과 무관하기 때문이다.
 *
 * @design D5
 * @req R6
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class StartVersionRollbackReproIT {

    @Autowired private VersionService versionService;
    @Autowired private StartVersionService startVersionService;
    @Autowired private VideoLabelSaveService videoLabelSaveService;
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

    private Long rawSn;
    /** 롤백 대상 프레임 — 내용이 A → B → (롤백) A 로 오간다. */
    private Long frameF;
    /** 대조 프레임 — 매 회차 내용이 바뀌어 그 회차 번호가 실제로 채번되게 한다. */
    private Long frameG;

    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-STARTVER-" + System.nanoTime(), "CCTV-STARTVER", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/startver.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        frameF = srcRepository.save(
                LsDataSrc.create(rawSn, 0, "/frames/raw/startver/0.jpg", LocalDateTime.now())).getSrcSn();
        frameG = srcRepository.save(
                LsDataSrc.create(rawSn, 1, "/frames/raw/startver/1.jpg", LocalDateTime.now())).getSrcSn();
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    @Test
    @DisplayName("롤백된_회차를_불러온_뒤_저장하면_그_회차의_실제_내용으로_확정된다")
    void 롤백된_회차를_불러온_뒤_저장하면_그_회차의_실제_내용으로_확정된다() {
        // given ① v1 — F=person / G=person
        replaceLabel(frameF, "person");
        replaceLabel(frameG, "person");
        approveAndFinalize(1);
        String hashOfContentA = activeHash(frameF);

        // given ② v2 — F=car (내용 B), G 무변경
        replaceLabel(frameF, "car");
        approveAndFinalize(2);

        // given ③ 프레임 단위 롤백 — F 를 내용 A 로 되돌린다(rA 재활성 / rB 는 VER_NO=2 를 단 채 비활성)
        versionService.rollback(hashOfContentA, frameF, reviewer);
        assertThat(labelNamesOf(frameF)).containsExactly("person");

        // given ④ v3 — G 만 바뀌어 회차 3 이 채번된다. F 는 내용이 A 그대로라 새 스냅샷도 새 번호도 없다.
        replaceLabel(frameG, "truck");
        approveAndFinalize(3);

        // given — 그 뒤 F 를 다른 내용으로 편집(되돌릴 대상이 실제로 존재하도록)
        replaceLabel(frameF, "bicycle");

        // when ① 불러오기(API-195) — 서버에는 아무것도 쓰지 않는다
        VersionLabelsResponse loaded = startVersionService.loadVersionLabels(rawSn, 3, reviewer);

        // then ① 응답의 F 내용은 v3 의 실제 내용 A(person) 다. 비활성 rB 의 내용(car)이면 오복원이다.
        assertThat(labelsOf(loaded, frameF))
                .as("v3 에 존재한 적 없는 내용(car)이 실려 오면 조용한 오복원이다")
                .containsExactly("person");
        assertThat(labelsOf(loaded, frameG)).containsExactly("truck");
        // ★ 불러오기만으로는 서버 작업본이 바뀌지 않는다 — 화면에는 person 이 올라오지만 DB 는 그대로다.
        assertThat(labelNamesOf(frameF))
                .as("불러오기가 서버를 바꾸면 되돌릴 창이 사라진다(이 재설계의 핵심)")
                .containsExactly("bicycle");

        // when ② 확정 저장(API-196) — 이때 비로소 서버에 반영된다
        videoLabelSaveService.save(rawSn, toSaveRequest(loaded), reviewer);

        // then ② 저장 뒤에는 v3 의 내용으로 확정된다
        assertThat(labelNamesOf(frameF)).containsExactly("person");
        assertThat(labelNamesOf(frameG)).containsExactly("truck");
    }

    /**
     * 불러온 세트를 확정 저장 요청으로 옮긴다 — <b>화면이 하는 일</b>.
     *
     * <p>회차 번호 + 전 프레임 판번호만 보낸다({@code edits} 없음 = 고친 것이 없다). 본문은 서버가
     * {@code loadedVersion} 스냅샷에서 직접 읽으므로 클라이언트가 되보내지 않는다 — 그래서 생산이력과
     * 추적 식별자가 회차에 적힌 대로 살아남는다.
     */
    private VideoLabelSaveRequest toSaveRequest(VersionLabelsResponse loaded) {
        List<VideoLabelSaveRequest.FrameVersion> versions = loaded.frames().stream()
                .map(f -> new VideoLabelSaveRequest.FrameVersion(f.srcSn(), f.lblVer()))
                .toList();
        return new VideoLabelSaveRequest(loaded.version(), versions, List.of());
    }

    private List<String> labelsOf(VersionLabelsResponse loaded, Long srcSn) {
        return loaded.frames().stream()
                .filter(f -> srcSn.equals(f.srcSn()))
                .findFirst().orElseThrow()
                .items().stream().map(kr.co.cudo.authoring.label.dto.LabelResponse.Item::label)
                .sorted().toList();
    }

    @Test
    @DisplayName("같은_회차를_다시_마감해도_회차_매핑이_중복되지_않는다 — 산출_재시도_멱등")
    void 같은_회차를_다시_마감해도_회차_매핑이_중복되지_않는다() {
        // given — 프레임 2장이 모두 스냅샷을 가진 v1
        replaceLabel(frameF, "person");
        replaceLabel(frameG, "person");
        approveAndFinalize(1);

        // when — 산출 마감이 재시도돼 같은 회차가 다시 확정된다
        approveAndFinalize(1);

        // then — UK(영상, 프레임, 회차) 로 흡수되어 프레임당 1건이다(예외로 마감을 깨뜨리지도 않는다)
        assertThat(mappingCount(1)).isEqualTo(2);
    }

    @Test
    @DisplayName("회차_매핑은_이미_번호가_찍힌_스냅샷도_포함해_ACTIVE_전량을_기록한다")
    void 회차_매핑은_ACTIVE_전량을_기록한다() {
        // given — v1 에서 두 프레임 모두 스냅샷 생성
        replaceLabel(frameF, "person");
        replaceLabel(frameG, "person");
        approveAndFinalize(1);

        // when — v2 에서는 F 만 바뀐다(G 는 멱등 skip 이라 새 스냅샷도 새 번호도 없다)
        replaceLabel(frameF, "car");
        approveAndFinalize(2);

        // then — v2 의 내용은 F·G 둘 다이므로 매핑도 2건이다. 번호(VER_NO)만으로는 G 가 v2 의
        //   내용이었다는 사실이 남지 않는다 — 그 정보 손실이 조용한 오복원의 원인이었다.
        assertThat(mappingCount(2)).isEqualTo(2);
    }

    // ---------- 헬퍼 ----------

    private int mappingCount(int outputVerNo) {
        return new JdbcTemplate(controlDataSource).queryForObject(
                "SELECT count(*) FROM ls_output_ver_snpsh WHERE data_raw_sn = ? AND output_ver_no = ?",
                Integer.class, rawSn, outputVerNo);
    }

    /** 프레임의 작업본 라벨을 지정 라벨 1건으로 교체한다(라벨 저장은 버전을 만들지 않는다). */
    private void replaceLabel(Long srcSn, String label) {
        labelRepository.deleteAll(labelRepository.findBySrcSn(srcSn));
        labelRepository.flush();
        labelRepository.save(LsDataLbl.createManual(
                srcSn, "BBOX", null, label, "[[10.0,10.0],[50.0,50.0]]", 1L));
        labelRepository.flush();
    }

    /**
     * 검수 승인(스냅샷 생성) + 산출 마감(회차 번호 확정)을 프로덕션과 같은 순서로 재현한다.
     *
     * <p>{@code stamp} 는 프로덕션에서 산출 마감 트랜잭션 안에서만 호출되므로
     * ({@code DatasetExportTxService.finalizeUnlessUnderDeidentReport}) 여기서도 트랜잭션으로 감싼다.
     */
    private void approveAndFinalize(int outputVerNo) {
        versionService.commitApproved(rawSn, reviewer);
        new TransactionTemplate(controlTxManager).executeWithoutResult(
                status -> outputVersionStamper.stamp(rawSn, outputVerNo));
    }

    private String activeHash(Long srcSn) {
        return labelVersionRepository.findByDataRawSnAndDataSrcSnAndActiveYn(
                        rawSn, srcSn, LsLabelVersion.ACTIVE_YES)
                .stream().findFirst().orElseThrow().getVersionHash();
    }

    private List<String> labelNamesOf(Long srcSn) {
        return labelRepository.findBySrcSn(srcSn).stream().map(LsDataLbl::getLabelNm).sorted().toList();
    }
}
