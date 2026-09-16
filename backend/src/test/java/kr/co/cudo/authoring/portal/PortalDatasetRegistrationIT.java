package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalDatasetRegistrationState;
import kr.co.cudo.authoring.portal.dto.PortalDatasetVideoPageResponse;
import kr.co.cudo.authoring.portal.dto.PortalDatasetVideoResponse;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.dto.PortalMetaScope;
import kr.co.cudo.authoring.portal.dto.PortalMetaUpdateRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserWorkResponse;
import kr.co.cudo.authoring.portal.service.PortalDatamartDownloadTxService;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationFailureReason;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationService;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationStatus;
import kr.co.cudo.authoring.portal.service.PortalDatasetVideoService;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import kr.co.cudo.authoring.portal.service.PortalMaterialsSummary;
import kr.co.cudo.authoring.portal.service.PortalMaterialsUnpacker;
import kr.co.cudo.authoring.portal.service.PortalMaterialsWorkspace;
import kr.co.cudo.authoring.portal.service.PortalUserWorkService;
import kr.co.cudo.authoring.portal.service.PortalWorkEventAnnotationService;
import kr.co.cudo.authoring.portal.service.PortalWorkMetaService;
import kr.co.cudo.authoring.portal.service.PortalWorkableVideoPolicy;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.DEID_JPEG;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.ORGNL_JPEG;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.dir;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.frame;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.niaDoc;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.originalPair;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.realDoc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 데이터셋 영상 원장 등록 · 데이터셋 영상 목록 · 작업 창구 통과를 <b>실 DB</b>로 고정한다(ADR-068).
 *
 * <p>배포 압축본은 <b>개발망 실물</b>(2026-09-16)과 같은 구성을 시험 안에서 만든 것이다 — 실제 압축본
 * 왕복이 아니다. 소재 조달(포털 조회·압축 해제)은 이 시험의 대상이 아니므로, 공개된 해제본 자리
 * ({@code current/content})를 직접 만들고 등록부터 시작한다.
 *
 * <h3>이 시험이 지키지 못하는 것</h3>
 * <ul>
 *   <li>여러 노드가 같은 데이터셋을 동시에 등록하는 경합 — 한 노드 안의 멱등만 본다.</li>
 *   <li>AI 보조 창구(탐지·분할·추적) — 추론 서버 왕복이라 여기서 태우지 않는다. 진입 인가가 같은
 *       작업 가능 판정을 거친다는 사실은 {@code PortalWorkTargetResolverTest} 가 고정한다.</li>
 * </ul>
 *
 * @design ADR-068
 * @design API-253
 * @design API-203
 * @design AC-1118
 * @design AC-1119
 * @design AC-1120
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class PortalDatasetRegistrationIT {

    private static final AtomicLong DATASET_SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private PortalDatasetRegistrationService registrationService;
    @Autowired private PortalMaterialsWorkspace workspace;
    @Autowired private PortalDatasetVideoService videoService;
    @Autowired private PortalLabelService labelService;
    @Autowired private PortalWorkMetaService metaService;
    @Autowired private PortalWorkEventAnnotationService annotationService;
    @Autowired private PortalUserWorkService userWorkService;
    @Autowired private PortalDatamartDownloadTxService downloadTxService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private kr.co.cudo.authoring.batch.repository.LsDataSrcRepository srcRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 비식별 저장소 base — <b>설정값을 그대로</b> 쓴다(서빙 판정기와 같은 base 여야 이미지 서빙까지 왕복된다).
     *
     * <p>임시 디렉터리로 바꾸는 속성 주입을 두지 않는 것은 의도다 — 그러면 이 클래스 전용 스프링 컨텍스트가
     * 하나 더 생긴다(컨텍스트 종류 래칫). 그래서 {@code PortalUserWorkControllerTest} 와 같은 구성으로
     * 컨텍스트를 재사용하고, 이 시험이 만든 자리는 {@link #cleanup()} 이 지운다.
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String deidentifiedPath;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    PortalDatasetRegistrationIT(@Qualifier("controlDataSource") DataSource dataSource,
                                @Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ==================================================== 픽스처

    private final List<Long> createdDatasets = new java.util.ArrayList<>();

    private long newDatasetId() {
        long id = DATASET_SEQ.incrementAndGet();
        createdDatasets.add(id);
        return id;
    }

    private Path deidRoot() {
        return Path.of(deidentifiedPath).toAbsolutePath().normalize();
    }

    /** 이 시험이 만든 해제본 자리와 복사한 이미지 묶음 자리를 지운다(원장 행은 식별자가 유일해 남겨도 무해하다). */
    @org.junit.jupiter.api.AfterEach
    void cleanup() throws IOException {
        for (long datasetId : createdDatasets) {
            for (Long rawSn : rawSnsOf(datasetId)) {
                for (String p : jdbc.queryForList(
                        "select de_idntf_src_file_path_nm from ls_data_src where raw_sn = ?", String.class, rawSn)) {
                    deleteTree(Path.of(p).getParent());
                }
                try {
                    Files.deleteIfExists(deidRoot().resolve("frames/deid/" + rawSn));
                } catch (IOException ignored) {
                    // 다른 시험의 것이 들어 있으면 남긴다.
                }
            }
            deleteTree(workspace.datasetDir(datasetId));
        }
        createdDatasets.clear();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static TokenClaims portalUser(String sub) {
        return new TokenClaims(sub, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));
    }

    private Path readyContent(long datasetId) throws IOException {
        return Files.createDirectories(workspace.readyDir(datasetId).resolve(PortalMaterialsUnpacker.CONTENT_DIR));
    }

    /**
     * 해제본 — 영상 2건이며 <b>평평한 자리와 하위 폴더가 섞여</b> 있다(AC-1118).
     *
     * <ul>
     *   <li>{@code a.mp4} — 평평한 자리에 2장. 우리 산출물 모양의 문서(사각 박스 1 · 폴리곤 1 · 키포인트 1).</li>
     *   <li>{@code b.mp4} — 하위 폴더에 1장. 개발망 실물 모양의 문서(사각 박스 1).</li>
     *   <li>원본으로 보이는 자리에 온전한 짝 1벌 — 읽히면 영상이 셋이 되어 드러난다.</li>
     * </ul>
     */
    private void validLayout(long datasetId) throws IOException {
        Path content = readyContent(datasetId);
        frame(content, 0, niaDoc("a.mp4", 100, "첫 장", "999999999"));
        frame(content, 1, niaDoc("a.mp4", 130, "둘째 장", "999999999"));
        frame(dir(content, "sub"), 5, realDoc("b.mp4", 5, "fire"));
        originalPair(content, 0, realDoc("z.mp4", 0, "fire"));
    }

    private List<Long> rawSnsOf(long datasetId) {
        return jdbc.queryForList("select raw_sn from ls_data_raw where vms_clip_id like ? order by raw_sn",
                Long.class, LsDataRaw.PORTAL_DATASET_CLIP_ID_PREFIX + datasetId + "\\_%");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private long rawSnByKey(long datasetId, String videoKey) {
        return jdbc.queryForObject("select raw_sn from ls_data_raw where vms_clip_id = ?", Long.class,
                LsDataRaw.portalDatasetClipId(datasetId, videoKey));
    }

    /**
     * 해제본 옆 요약을 공개 자리에 직접 쓴다 — 조달(포털 조회·압축 해제)은 이 시험의 대상이 아니므로
     * 조달이 남겼을 파일만 같은 창구로 만들어 둔다.
     */
    private void writeSummary(long datasetId, String code, String version) throws IOException {
        workspace.writeSummary(workspace.readyDir(datasetId),
                new PortalMaterialsSummary(code, version, null, 0, 0L, 0, Instant.now()));
    }

    /** 그 영상 메타의 값 — 키가 없으면 {@code null}. */
    private String meta(long rawSn, String key) {
        List<String> values = jdbc.queryForList(
                "select meta_vl from ls_data_meta where raw_sn = ? and meta_key = ?", String.class, rawSn, key);
        return values.isEmpty() ? null : values.get(0);
    }

    private List<Long> srcSnsOf(long rawSn) {
        return jdbc.queryForList("select src_sn from ls_data_src where raw_sn = ? order by frm_no", Long.class, rawSn);
    }

    private PortalDatasetRegistrationStatus registerNow(long datasetId) {
        assertThat(registrationService.tryClaim(datasetId)).isTrue();
        try {
            return registrationService.registerClaimed(datasetId);
        } finally {
            registrationService.release(datasetId);
        }
    }

    // ==================================================== AC-1118 — 등록

    @Test
    @DisplayName("★실물_구성이면_영상_프레임_원본라벨_메타가_출처_PORTAL_DATASET_으로_적재되고_다시_등록해도_늘지_않는다")
    void registersLedgerAndIsIdempotent() throws IOException {
        long datasetId = newDatasetId();
        validLayout(datasetId);

        registrationService.registerAfterProvision(datasetId);

        PortalDatasetRegistrationStatus marker = workspace.readRegistration(datasetId);
        assertThat(marker.state()).isEqualTo(PortalDatasetRegistrationState.DONE);
        assertThat(marker.registeredVideos()).isEqualTo(2);

        List<Long> rawSns = rawSnsOf(datasetId);
        assertThat(rawSns).hasSize(2);
        long camA = rawSnByKey(datasetId, "a.mp4");
        long camB = rawSnByKey(datasetId, "b.mp4");

        assertThat(jdbc.queryForList("select src_type from ls_data_raw where raw_sn in (?, ?)", String.class,
                camA, camB)).containsOnly(LsDataRaw.SRC_TYPE_PORTAL_DATASET);
        assertThat(jdbc.queryForList("select de_ident_yn from ls_data_raw where raw_sn in (?, ?)", String.class,
                camA, camB)).containsOnly("Y");
        assertThat(count("select count(*) from ls_data_raw where raw_sn in (?, ?) and portal_user_no is not null",
                camA, camB)).as("소유자는 비어 있다").isZero();

        assertThat(srcSnsOf(camA)).as("평평한 자리의 두 장").hasSize(2);
        assertThat(srcSnsOf(camB)).as("★하위 폴더에 놓인 짝도 같은 규칙으로 읽힌다").hasSize(1);
        assertThat(jdbc.queryForList("select vdo_frm_no from ls_data_src where raw_sn = ? order by frm_no",
                Long.class, camA)).containsExactly(100L, 130L);
        assertThat(count("select count(*) from ls_data_src where raw_sn = ? and src_file_path_nm is not null", camA))
                .as("원본 경로는 비운다").isZero();
        String deidPath = jdbc.queryForObject(
                "select de_idntf_src_file_path_nm from ls_data_src where raw_sn = ? and frm_no = 0", String.class, camA);
        assertThat(Path.of(deidPath).getParent().getParent()).as("비식별 프레임 영역의 그 영상 자리 아래")
                .isEqualTo(deidRoot().resolve("frames/deid/" + camA));
        assertThat(Path.of(deidPath).getFileName().toString()).isEqualTo("0000.jpg");
        assertThat(Files.readAllBytes(Path.of(deidPath))).as("★비식별 이미지가 복사됐다").isEqualTo(DEID_JPEG);

        assertThat(count("select count(*) from ls_data_lbl l join ls_data_src s on s.src_sn = l.src_sn where s.raw_sn = ?",
                camA)).as("★키포인트는 옮기지 않는다 — 프레임당 박스·폴리곤 2").isEqualTo(4);
        assertThat(count("select count(*) from ls_data_lbl l join ls_data_src s on s.src_sn = l.src_sn "
                + "where s.raw_sn = ? and l.lbl_id is not null", camA))
                .as("활성 마스터가 아닌 분류 식별자는 연결하지 않는다").isZero();

        assertThat(jdbc.queryForObject("select meta_vl from ls_data_meta where raw_sn = ? and meta_key = 'portal.dataset_id'",
                String.class, camA)).isEqualTo(Long.toString(datasetId));
        assertThat(jdbc.queryForObject("select meta_vl from ls_data_meta where raw_sn = ? and meta_key = 'video.original_filename'",
                String.class, camA)).isEqualTo("a.mp4");
        assertThat(jdbc.queryForObject("select meta_vl from ls_data_meta where raw_sn = ? and meta_key = 'portal.dataset_video_key'",
                String.class, camA)).as("★영상 키는 문서의 영상 파일명이다").isEqualTo("a.mp4");
        assertThat(jdbc.queryForObject("select meta_vl from ls_data_meta where raw_sn = ? and meta_key = 'video.length_sec'",
                String.class, camB)).isEqualTo("12");
        assertThat(jdbc.queryForObject("select meta_vl from ls_data_meta where raw_sn = ? and meta_key = 'video.event_type_cd'",
                String.class, camB)).isEqualTo("EV02000102");
        assertThat(count("select count(*) from ls_data_lbl l join ls_data_src s on s.src_sn = l.src_sn "
                + "where s.raw_sn = ? and l.lbl_nm = '사람'", camA))
                .as("분류 목록의 이름").isEqualTo(4);
        assertThat(count("select count(*) from ls_data_lbl l join ls_data_src s on s.src_sn = l.src_sn "
                + "where s.raw_sn = ? and l.lbl_nm = 'fire'", camB))
                .as("★분류 식별자 없이 이름 문자열만 온 라벨도 이름으로 앉는다").isEqualTo(1);

        assertThat(count("select count(*) from ls_raw_data_status where raw_data_id in (?, ?)", camA, camB))
                .as("★검수 워크플로 상태 행을 만들지 않는다").isZero();
        assertThat(count("select count(*) from v_completed_video where raw_sn in (?, ?)", camA, camB))
                .as("★관제 조회 뷰에 나타나지 않는다").isZero();

        assertOnlyDeidImagesCopied(Path.of(deidPath).getParent(), 2);
        assertNoStagingLeft();

        // 멱등 — 같은 데이터셋을 다시 등록해도 행이 늘지 않는다.
        registerNow(datasetId);
        assertThat(rawSnsOf(datasetId)).containsExactlyElementsOf(rawSns);
        assertThat(srcSnsOf(camA)).hasSize(2);
        assertThat(count("select count(*) from ls_data_lbl l join ls_data_src s on s.src_sn = l.src_sn where s.raw_sn = ?",
                camA)).isEqualTo(4);
        assertThat(workspace.readRegistration(datasetId).state()).isEqualTo(PortalDatasetRegistrationState.DONE);
    }

    @Test
    @DisplayName("★해제본_요약의_데이터셋_코드와_버전이_영상_메타로_남는다_정리_삭제_트리거가_그_두_값으로_온다")
    void datasetCodeAndVersionAreRecordedInMeta() throws IOException {
        long datasetId = newDatasetId();
        validLayout(datasetId);
        writeSummary(datasetId, "DS-FLOOD-2025-01", "16.0.0");

        registrationService.registerAfterProvision(datasetId);

        for (long rawSn : List.of(rawSnByKey(datasetId, "a.mp4"), rawSnByKey(datasetId, "b.mp4"))) {
            assertThat(meta(rawSn, "portal.dataset_code")).isEqualTo("DS-FLOOD-2025-01");
            assertThat(meta(rawSn, "portal.dataset_version")).isEqualTo("16.0.0");
            assertThat(meta(rawSn, "portal.dataset_id")).as("번호 축은 그대로다")
                    .isEqualTo(Long.toString(datasetId));
        }
    }

    @Test
    @DisplayName("★요약에_코드와_버전이_없으면_그_키를_쓰지_않는다_지어내지_않는다")
    void blankCodeAndVersionLeaveKeysAbsent() throws IOException {
        long datasetId = newDatasetId();
        validLayout(datasetId);
        writeSummary(datasetId, null, "   "); // 옛 데이터 — 코드가 없고 버전이 공백이다

        registrationService.registerAfterProvision(datasetId);

        long camA = rawSnByKey(datasetId, "a.mp4");
        assertThat(meta(camA, "portal.dataset_code")).isNull();
        assertThat(meta(camA, "portal.dataset_version")).isNull();
        assertThat(meta(camA, "portal.dataset_id")).as("★두 값이 비어도 등록 자체는 그대로 끝난다")
                .isEqualTo(Long.toString(datasetId));
    }

    @Test
    @DisplayName("요약_파일이_아예_없어도_등록은_끝나고_코드_버전_키만_없다")
    void missingSummaryStillRegisters() throws IOException {
        long datasetId = newDatasetId();
        validLayout(datasetId); // 요약을 쓰지 않는다

        PortalDatasetRegistrationStatus marker = registerNow(datasetId);

        assertThat(marker.state()).isEqualTo(PortalDatasetRegistrationState.DONE);
        long camA = rawSnByKey(datasetId, "a.mp4");
        assertThat(meta(camA, "portal.dataset_code")).isNull();
        assertThat(meta(camA, "portal.dataset_version")).isNull();
    }

    /** 원본 이미지 바이트가 비식별 프레임 영역 어디에도 없다 — 원본 폴더를 읽지 않았다. */
    private void assertOnlyDeidImagesCopied(Path dir, int expectedFrames) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> list = files.toList();
            assertThat(list).hasSize(expectedFrames);
            for (Path f : list) {
                assertThat(Files.readAllBytes(f)).isNotEqualTo(ORGNL_JPEG);
            }
        }
    }

    private void assertNoStagingLeft() throws IOException {
        try (Stream<Path> files = Files.list(deidRoot().resolve("frames/deid"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("작업 중 자리가 남지 않는다").noneMatch(n -> n.startsWith(".portal-dataset-staging-"));
        }
    }

    @Test
    @DisplayName("★구성이_가정과_다르면_실패로_끝나고_원장에_그_데이터셋의_행이_하나도_없다")
    void mismatchFailsWithoutLedgerRows() throws IOException {
        long datasetId = newDatasetId();
        Path content = readyContent(datasetId);
        frame(content, 0, realDoc("a.mp4", 0, "fire"));
        Path sub = dir(content, "sub");
        frame(sub, 1, realDoc("b.mp4", 1, "fire"));
        Files.write(sub.resolve("0002.jpg"), DEID_JPEG); // 짝 없음 — 앞 영상은 정상이어도 한 행도 쓰지 않는다

        PortalDatasetRegistrationStatus result = registerNow(datasetId);

        assertThat(result.state()).isEqualTo(PortalDatasetRegistrationState.FAILED);
        assertThat(result.failureReason()).isEqualTo(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH);
        assertThat(workspace.readRegistration(datasetId).state()).isEqualTo(PortalDatasetRegistrationState.FAILED);
        assertThat(rawSnsOf(datasetId)).isEmpty();

        PortalDatasetVideoPageResponse page = videoService.list(datasetId, 0, 20, "ds-user-" + datasetId);
        assertThat(page.registrationState()).isEqualTo(PortalDatasetRegistrationState.FAILED);
        assertThat(page.content()).isEmpty();
    }

    // ==================================================== AC-1119 — 목록 창구

    @Test
    @DisplayName("소재가_준비되지_않은_데이터셋은_409")
    void notReadyIsConflict() {
        long datasetId = newDatasetId();

        assertThatThrownBy(() -> videoService.list(datasetId, 0, 20, "u"))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("★진입_프레임과_마지막_저장_시각은_요청_사용자_기준이고_내_작업_목록과_같은_판정이다")
    void entryAndLastSavedArePerUser() throws IOException {
        long datasetId = newDatasetId();
        validLayout(datasetId);
        registrationService.registerAfterProvision(datasetId);
        long camA = rawSnByKey(datasetId, "a.mp4");
        List<Long> frames = srcSnsOf(camA);
        String gap = "ds-gap-" + datasetId;
        String eul = "ds-eul-" + datasetId;

        labelService.saveUserLabel(new PortalUserLabelRequest(camA, frames.get(1), "BBOX", "사람",
                "[[1,1],[5,5]]", null, null), portalUser(eul));
        metaService.save(frames.get(1), eul, new PortalMetaUpdateRequest(List.of(
                new PortalMetaUpdateRequest.Item("note", "메모", PortalMetaScope.VIDEO))));

        PortalDatasetVideoPageResponse gapPage = videoService.list(datasetId, 0, 20, gap);
        assertThat(gapPage.registrationState()).isEqualTo(PortalDatasetRegistrationState.DONE);
        assertThat(gapPage.content()).extracting(PortalDatasetVideoResponse::videoName)
                .containsExactly("a.mp4", "b.mp4");
        PortalDatasetVideoResponse gapA = gapPage.content().get(0);
        assertThat(gapA.frameCount()).isEqualTo(2);
        assertThat(gapA.labelCount()).as("원본 라벨 수 — 사용자 저장 라벨이 아니다").isEqualTo(4);
        assertThat(gapA.entrySrcSn()).as("저장이 없으면 첫 프레임").isEqualTo(frames.get(0));
        assertThat(gapA.lastSavedAt()).as("★을의 저장이 섞이지 않는다").isNull();

        PortalDatasetVideoResponse eulA = videoService.list(datasetId, 0, 20, eul).content().get(0);
        assertThat(eulA.entrySrcSn()).as("저장한 라벨의 프레임").isEqualTo(frames.get(1));
        assertThat(eulA.lastSavedAt()).isNotNull();

        PortalUserWorkResponse work = userWorkService.listUserWorks(eul, PageRequest.of(0, 100)).getContent().stream()
                .filter(w -> w.rawSn() == camA).findFirst().orElseThrow();
        assertThat(work.lastSavedAt()).as("★내 작업 목록과 같은 저작 시각").isEqualTo(eulA.lastSavedAt());
        assertThat(work.entrySrcSn()).as("★내 작업 진입이 열린다").isEqualTo(frames.get(1));
    }

    @Test
    @DisplayName("★표식이_오래된_진행_중이고_진행_중인_작업이_없으면_조회가_등록을_다시_시작한다")
    void staleMarkerRestartsRegistration() throws Exception {
        long datasetId = newDatasetId();
        validLayout(datasetId);
        workspace.writeRegistration(datasetId, PortalDatasetRegistrationStatus.inProgress(0, 0,
                Instant.now().minus(PortalDatasetRegistrationService.STALE_IN_PROGRESS).minusSeconds(60)));

        PortalDatasetVideoPageResponse first = videoService.list(datasetId, 0, 20, "u");
        assertThat(first.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);

        PortalDatasetVideoPageResponse later = first;
        for (int i = 0; i < 300 && later.registrationState() != PortalDatasetRegistrationState.DONE; i++) {
            Thread.sleep(100); // 백그라운드 등록 완료 대기(폴링)
            later = videoService.list(datasetId, 0, 20, "u");
        }
        assertThat(later.registrationState()).isEqualTo(PortalDatasetRegistrationState.DONE);
        assertThat(later.content()).hasSize(2);
    }

    @Test
    @DisplayName("★표식이_신선한_진행_중이면_새로_시작하지_않는다_다른_노드가_진행_중일_수_있다")
    void freshMarkerDoesNotRestart() throws Exception {
        long datasetId = newDatasetId();
        validLayout(datasetId);
        Instant written = Instant.now();
        workspace.writeRegistration(datasetId, PortalDatasetRegistrationStatus.inProgress(0, 0, written));

        PortalDatasetVideoPageResponse page = videoService.list(datasetId, 0, 20, "u");

        assertThat(page.registrationState()).isEqualTo(PortalDatasetRegistrationState.IN_PROGRESS);
        assertThat(registrationService.inProgress(datasetId)).isFalse();
        assertThat(workspace.readRegistration(datasetId).updatedAt()).isEqualTo(written);
        assertThat(rawSnsOf(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("창구_인가_계약_토큰없음_401_내부채널_403_미준비_409_준비_200")
    void endpointContract() throws Exception {
        long datasetId = newDatasetId();
        String path = "/v1/portal/datasets/" + datasetId + "/videos";
        String portal = JwtTestSupport.token(secret, "ds-http-" + datasetId, "PORTAL_USER", "PORTAL", issuer, 60);
        String reviewer = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);

        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header("Authorization", "Bearer " + reviewer)).andExpect(status().isForbidden());
        mockMvc.perform(get(path).header("Authorization", "Bearer " + portal)).andExpect(status().isConflict());

        validLayout(datasetId);
        registrationService.registerAfterProvision(datasetId);
        mockMvc.perform(get(path).param("size", "500").header("Authorization", "Bearer " + portal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationState").value("DONE"))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].videoName").value("a.mp4"))
                .andExpect(jsonPath("$.data.content[0].frameCount").value(2))
                .andExpect(jsonPath("$.data.content[0].labelCount").value(4))
                .andExpect(jsonPath("$.data.content[0].entrySrcSn").isNumber())
                .andExpect(jsonPath("$.data.content[0].lastSavedAt").doesNotExist());
    }

    // ==================================================== AC-1120 — 작업 창구 통과

    @Test
    @DisplayName("★검수_상태_없이_라벨조회_이미지_저장_메타_어노테이션_내려받기가_열리고_원본_라벨은_불변이다")
    void workEndpointsOpenForDatasetVideo() throws IOException {
        long datasetId = newDatasetId();
        validLayout(datasetId);
        registrationService.registerAfterProvision(datasetId);
        long camA = rawSnByKey(datasetId, "a.mp4");
        long frameF = srcSnsOf(camA).get(0);
        String gap = "ds-work-" + datasetId;
        TokenClaims actor = portalUser(gap);

        PortalFrameLabelsResponse loaded = labelService.loadFrameLabels(frameF, actor);
        assertThat(loaded.labels()).as("등록한 원본 라벨이 처음 불러오는 값이다").hasSize(2);

        ResponseEntity<Resource> image = labelService.serveFrameImage(frameF, actor);
        try (InputStream in = image.getBody().getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(DEID_JPEG);
        }

        List<String> originalBefore = jdbc.queryForList(
                "select lbl_type_cd || ':' || point_cn from ls_data_lbl where src_sn = ? order by lbl_sn", String.class, frameF);

        labelService.saveUserLabel(new PortalUserLabelRequest(camA, frameF, "BBOX", "사람",
                "[[2,2],[9,9]]", null, null), actor);
        assertThat(labelService.loadFrameLabels(frameF, actor).labels())
                .as("다시 조회한 라벨은 본인 저장본이다").singleElement()
                .satisfies(i -> assertThat(i.points()).containsExactly(List.of(2.0, 2.0), List.of(9.0, 9.0)));
        assertThat(jdbc.queryForList("select lbl_type_cd || ':' || point_cn from ls_data_lbl where src_sn = ? order by lbl_sn",
                String.class, frameF)).as("★원장의 원본 라벨은 저장 전과 같다").isEqualTo(originalBefore);

        assertThat(metaService.load(frameF, gap).rawSn()).isEqualTo(camA);
        assertThat(annotationService.load(camA, gap).rawSn()).isEqualTo(camA);

        PortalDatamartDownloadTxService.DownloadPlan plan = downloadTxService.plan(camA, actor);
        assertThat(plan.deidVideoPath()).as("영상 파일은 배포본에 없다").isNull();
        assertThat(plan.frames()).hasSize(2);
        JsonNode docNode = objectMapper.readTree(plan.frames().get(0).annotationJson());
        assertThat(docNode.path("video").path("filename").asText()).as("등록 메타의 원본 파일명").isEqualTo("a.mp4");
        assertThat(docNode.path("video").path("fps").asText()).isEqualTo("30");
        assertThat(docNode.path("video").path("width").asInt()).isEqualTo(1920);
        assertThat(docNode.path("dataset").path("src_path").isNull()).as("비식별 영상 경로가 없으면 비운다").isTrue();
        assertThat(docNode.path("image").path("frame_num").asInt()).isEqualTo(100);
        assertThat(docNode.path("image").path("description").asText()).isEqualTo("첫 장");
    }

    @Test
    @DisplayName("★승인도_데이터셋_등록도_업로드도_아닌_영상은_한_문구의_403이다")
    void otherVideoIsForbiddenWithSingleMessage() {
        long rawSn = txTemplate.execute(s -> videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-DS-" + System.nanoTime(), "cctv-1", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30)).getRawSn());
        long srcSn = txTemplate.execute(s -> srcRepository.save(
                kr.co.cudo.authoring.batch.entity.LsDataSrc.create(rawSn, 0L, "/frames/0.png", null)).getSrcSn());

        assertThatThrownBy(() -> labelService.loadFrameLabels(srcSn, portalUser("ds-other")))
                .isInstanceOf(CustomException.class)
                .hasMessage(PortalWorkableVideoPolicy.NOT_WORKABLE_MESSAGE);
        assertThatThrownBy(() -> downloadTxService.plan(rawSn, portalUser("ds-other")))
                .isInstanceOf(CustomException.class)
                .hasMessage(PortalWorkableVideoPolicy.NOT_WORKABLE_MESSAGE);
    }
}
