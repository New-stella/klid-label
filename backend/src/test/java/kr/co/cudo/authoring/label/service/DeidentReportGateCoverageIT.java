package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.controller.TaskQueryController;
import kr.co.cudo.authoring.label.controller.FrameImageController;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.FrameImageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DEV_FIX-A (S7 확장) — 비식별 누락 신고 구간({@code DE_IDNTF_YN='F'}) PII 게이트를
 * <b>우회 경로 전체</b>에서 못박는다.
 *
 * <p>배경: 신고가 라벨을 삭제하지 않고 <b>보존</b>하도록 정책이 반전(2026-07-27)되면서, 구 정책에서
 * "빈 결과"로 안전했던 경로들이 PII 위치 특정 정보(라벨 좌표)·실제 PII 이미지를 그대로 내보내게 됐다.
 * 게이트({@code LabelAccessGuard.requireNotUnderDeidentReport})가 {@code LabelService.getByFrame}
 * 한 곳에만 배선돼 있던 것이 결함의 형태이므로, 본 IT 는 <b>mock 없이 실제 호출 경로</b>를 타서
 * 배선 자체를 검증한다(게이트 한 줄을 지우면 해당 테스트가 실패한다).
 *
 * <ul>
 *   <li><b>H1</b> 프레임 이미지 서빙 2경로 — {@code FrameImageService.serve}(rawSn+frameNo),
 *       {@code FrameImageController.getImage}(srcSn, 원본 프레임)</li>
 *   <li><b>H2</b> 포털(외부 채널) — {@code PortalLabelService.loadFrameLabels/serveFrameImage}</li>
 *   <li><b>H3</b> 관제 조회 — {@code TaskQueryController.getLabels}</li>
 *   <li><b>H4</b> 라벨 이력 / 버전 diff — {@code LabelService.getHistory}, {@code VersionService.diff}</li>
 *   <li><b>H5</b> 쓰기 비대칭 — {@code VersionService.rollback} (작업락 없이 {@code 'F'} 인 배치 실패 상태)</li>
 *   <li><b>수용 기준</b> resolve 후 전 경로 재개방 + 라벨 동일 / {@code 'Y'} 일반 영상 무영향</li>
 * </ul>
 *
 * <p>공유 Testcontainers PG 를 쓰므로 시드는 {@code DIDGATE-} 고유 clipId 로 만들고, 단언은 시드한
 * rawSn/srcSn 으로만 좁힌다. 파일은 설정된 스토리지 base 하위 고유 디렉터리에만 쓰고 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.control-notify.enabled=true")
class DeidentReportGateCoverageIT {

    @Autowired private DeidentReportService deidentReportService;
    @Autowired private LabelService labelService;
    @Autowired private VersionService versionService;
    @Autowired private PortalLabelService portalLabelService;
    @Autowired private FrameImageService frameImageService;
    @Autowired private FrameImageController frameImageController;
    @Autowired private TaskQueryController taskQueryController;

    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private LsRawDataStatusRepository rawDataStatusRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private kr.co.cudo.authoring.label.repository.LsDeidentReportRepository reportRepository;
    @Autowired private kr.co.cudo.authoring.auth.service.WorkLockService workLockService;

    @Value("${authoring.storage.raw-path:./storage/raw}") private String storageRawPath;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;

    @TempDir Path tempDir;

    private final TransactionTemplate txTemplate;

    DeidentReportGateCoverageIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private final TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
            Instant.now().plusSeconds(600));
    private final TokenClaims portalUser = new TokenClaims("500", Role.PORTAL_USER, Channel.PORTAL,
            Instant.now().plusSeconds(600));

    /** 이 IT 가 시드한 rawSn — 공유 PG 오염 방지용 정리 대상. */
    private final List<Long> seededRawSns = new ArrayList<>();
    /** 이 IT 가 시드한 srcSn — 버전 행 정리용. */
    private final List<Long> seededSrcSns = new ArrayList<>();
    /** 이 IT 가 만든 이미지 파일 디렉터리 — 정리 대상. */
    private final List<Path> seededDirs = new ArrayList<>();

    @BeforeEach
    void authenticate() {
        // 컨트롤러 빈은 @PreAuthorize 로 프록시되므로 직접 호출도 SecurityContext 가 필요하다
        // (JwtAuthenticationFilter 와 동일하게 principal=TokenClaims + ROLE_*/CHANNEL_* authority).
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                reviewer, null,
                List.of(new SimpleGrantedAuthority("ROLE_REVIEWER"),
                        new SimpleGrantedAuthority("CHANNEL_INTERNAL"))));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        for (Long srcSn : seededSrcSns) {
            txTemplate.execute(s -> {
                labelVersionRepository.deleteAll(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn));
                return null;
            });
        }
        seededSrcSns.clear();
        for (Long rawSn : seededRawSns) {
            txTemplate.execute(s -> {
                reportRepository.deleteAll(reportRepository.findAllByDataRawSnOrderByReportDtDesc(rawSn));
                rawDataStatusRepository.findById(rawSn).ifPresent(rawDataStatusRepository::delete);
                return null;
            });
            workLockService.releaseRaw(rawSn, "system", "TEST_CLEANUP");
        }
        seededRawSns.clear();
        for (Path dir : seededDirs) {
            deleteQuietly(dir);
        }
        seededDirs.clear();
    }

    private static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 정리 실패는 테스트 결과에 영향 없음
                }
            });
        } catch (IOException ignored) {
            // 디렉터리 부재 — 무시
        }
    }

    // ---------- 픽스처 ----------

    /** 시드 결과 — 영상/프레임/라벨/버전 식별자. */
    private record Seed(long rawSn, long srcSn, List<Long> lblSns, String fromHash, String toHash) { }

    /**
     * 비식별 완료('Y') 영상 + 프레임 1건(원본·비식별 이미지 파일 실재) + 라벨 2건 + 버전 2건 시드.
     *
     * @param approved true 면 검수 완료(APPROVED) 상태 행까지 생성 — 포털 노출 조건 충족용
     */
    private Seed seed(String suffix, boolean approved) throws IOException {
        String unique = suffix + "-" + System.nanoTime();
        // 경로 규약(StorageSubtreePolicy) 준수 — 비식별 프레임은 반드시 {base}/frames/deid/** 하위여야
        // 서빙 판정기(verifyDeidentifiedFile)를 통과한다. 원본 프레임은 frames/raw/** 규약을 따른다.
        Path rawDir = Paths.get(storageRawPath).toAbsolutePath().normalize()
                .resolve("frames").resolve("raw").resolve("it-didgate-" + unique);
        Path deidDir = Paths.get(storageDeidPath).toAbsolutePath().normalize()
                .resolve("frames").resolve("deid").resolve("it-didgate-" + unique);
        Files.createDirectories(rawDir);
        Files.createDirectories(deidDir);
        seededDirs.add(rawDir);
        seededDirs.add(deidDir);
        Path rawFile = rawDir.resolve("f0.jpg");
        Path deidFile = deidDir.resolve("f0.jpg");
        Files.write(rawFile, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        Files.write(deidFile, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

        String fromHash = hex("a", unique);
        String toHash = hex("b", unique);

        Seed seed = txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "DIDGATE-" + unique, "CCTV-DIDGATE", "EVT", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/DIDGATE.mp4", LocalDateTime.now(), 30);
            raw.markDeidentified("Y");
            raw = videoRepository.save(raw);
            Long rawSn = raw.getRawSn();

            LsDataSrc f0 = srcRepository.save(LsDataSrc.create(
                    rawSn, 0L, 0L, rawFile.toString(), deidFile.toString(), LocalDateTime.now(),
                    "Y", "N", "Y"));
            LsDataLbl l1 = lblRepository.save(LsDataLbl.createAutoBbox(
                    f0.getSrcSn(), null, "person", "[10,20,30,40]", BigDecimal.valueOf(0.9), null));
            LsDataLbl l2 = lblRepository.save(LsDataLbl.createAutoBbox(
                    f0.getSrcSn(), null, "car", "[50,60,70,80]", BigDecimal.valueOf(0.8), null));

            labelVersionRepository.save(LsLabelVersion.create(rawSn, f0.getSrcSn(), fromHash,
                    payload(f0.getSrcSn(), l1.getLblSn(), "person", "[[10,20],[30,40]]"),
                    1, LsLabelVersion.SAVE_REASON_APPROVED, "1"));
            labelVersionRepository.save(LsLabelVersion.create(rawSn, f0.getSrcSn(), toHash,
                    payload(f0.getSrcSn(), l1.getLblSn(), "person", "[[11,21],[31,41]]"),
                    2, LsLabelVersion.SAVE_REASON_APPROVED, "1"));

            if (approved) {
                LsRawDataStatus status = rawDataStatusRepository.findById(rawSn)
                        .orElseGet(() -> LsRawDataStatus.initial(rawSn));
                status.transitionTo(LsRawDataStatus.STTS_APPROVED);
                rawDataStatusRepository.save(status);
            }
            return new Seed(rawSn, f0.getSrcSn(), List.of(l1.getLblSn(), l2.getLblSn()), fromHash, toHash);
        });
        seededRawSns.add(seed.rawSn());
        seededSrcSns.add(seed.srcSn());
        return seed;
    }

    /** 고유 hex 해시(≤64자) — 공유 DB 의 VERSION_HASH 유니크 제약 충돌 방지. */
    private static String hex(String prefix, String unique) {
        return (prefix + Integer.toHexString(unique.hashCode()) + Long.toHexString(System.nanoTime()))
                .replaceAll("[^0-9a-f]", "0");
    }

    private static String payload(Long srcSn, Long lblSn, String label, String points) {
        return "{\"srcSn\":" + srcSn + ",\"frameNo\":0,\"items\":[{\"id\":" + lblSn
                + ",\"lblTypeCd\":\"BBOX\",\"label\":\"" + label + "\",\"labelId\":null,\"points\":"
                + points + ",\"autoLblYn\":null,\"confScore\":null,\"trackId\":null,\"lblSrcCd\":null}]}";
    }

    /** 신고 이후 외부 솔루션이 비식별본을 교체한 상태 — resolve 산출물 검증 게이트 통과용. */
    private void seedDeidentArtifact(long rawSn) throws IOException {
        // 무결성 판정(DeidentArtifactIntegrity)을 통과하는 실제 최소 mp4 픽스처.
        Path artifact = kr.co.cudo.authoring.support.TestVideoFixtures.writeTinyMp4(
                tempDir.resolve("deid-" + rawSn + ".mp4"));
        txTemplate.execute(s -> {
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    rawSn, "req-didgate-" + rawSn, "/var/raw/DIDGATE.mp4", "system");
            procLog.succeed(artifact.toString());
            return procLogRepository.save(procLog);
        });
    }

    /** 배치 실패 경로 재현 — 작업락 없이 {@code DE_IDNTF_YN} 만 전이시킨다. */
    private void markDeidentDirectly(long rawSn, String code) {
        txTemplate.execute(s -> {
            videoRepository.findById(rawSn).orElseThrow().markDeidentified(code);
            return null;
        });
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    // ---------- H1 — 프레임 이미지 서빙 ----------

    @Test
    @DisplayName("신고_상태에서_프레임_이미지_서빙이_차단된다")
    void frameImageServingBlockedDuringReport() throws IOException {
        // given — 신고 전에는 두 경로 모두 이미지가 나간다.
        Seed seed = seed("H1", false);
        assertThat(frameImageService.serve(seed.rawSn(), 0, false, reviewer).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(frameImageController.getImage(seed.srcSn(), reviewer).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // when — 비식별 누락 신고 (라벨·이미지는 보존되지만 접근은 막혀야 한다).
        deidentReportService.report(seed.srcSn(), "얼굴 미블러 노출", reviewer);

        // then — 비식별본 경로(rawSn+frameNo)와 원본 프레임 경로(srcSn) 모두 412.
        assertThatThrownBy(() -> frameImageService.serve(seed.rawSn(), 0, false, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThatThrownBy(() -> frameImageController.getImage(seed.srcSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    // ---------- H2 — 포털(외부 채널) ----------

    @Test
    @DisplayName("신고_상태에서_포털_라벨_조회와_이미지_서빙이_차단된다")
    void portalPathsBlockedDuringReport() throws IOException {
        // given — APPROVED 영상(포털 노출 조건). 신고 전에는 좌표·이미지가 포털로 나간다.
        Seed seed = seed("H2", true);
        assertThat(portalLabelService.loadFrameLabels(seed.srcSn(), portalUser).labels()).hasSize(2);
        assertThat(portalLabelService.serveFrameImage(seed.srcSn(), portalUser).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // when — 신고. report 는 LS_RAW_DATA_STATUS 를 건드리지 않으므로 APPROVED 는 유지된다
        //        (= 기존 데이터마트 게이트만으로는 절대 막히지 않는 노출 경로).
        deidentReportService.report(seed.srcSn(), "얼굴 미블러 노출", reviewer);
        assertThat(txTemplate.execute(s -> rawDataStatusRepository.findById(seed.rawSn()).orElseThrow())
                .getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_APPROVED);

        // then — 포털은 역할 예외 없이 412.
        assertThatThrownBy(() -> portalLabelService.loadFrameLabels(seed.srcSn(), portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThatThrownBy(() -> portalLabelService.serveFrameImage(seed.srcSn(), portalUser))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    // ---------- H3 — 관제 조회 API ----------

    @Test
    @DisplayName("신고_상태에서_관제_tasks_labels_조회가_차단된다")
    void controlTaskLabelsBlockedDuringReport() throws IOException {
        // given
        Seed seed = seed("H3", false);
        assertThat(taskQueryController.getLabels(seed.rawSn(), null, PageRequest.of(0, 20), reviewer)
                .data().getTotalElements()).isPositive();

        // when
        deidentReportService.report(seed.srcSn(), "얼굴 미블러 노출", reviewer);

        // then — 좌표 본문(points)을 담는 경로라 412.
        assertThatThrownBy(() -> taskQueryController.getLabels(
                seed.rawSn(), null, PageRequest.of(0, 20), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    // ---------- H4 — 라벨 이력 / 버전 diff ----------

    @Test
    @DisplayName("신고_상태에서_label_history_와_version_diff_가_차단된다")
    void historyAndDiffBlockedDuringReport() throws IOException {
        // given
        Seed seed = seed("H4", false);
        assertThatCode(() -> labelService.getHistory(seed.srcSn(), reviewer, PageRequest.of(0, 20)))
                .doesNotThrowAnyException();
        assertThat(versionService.diff(seed.fromHash(), seed.toHash(), reviewer).labels()).isNotEmpty();

        // when
        deidentReportService.report(seed.srcSn(), "얼굴 미블러 노출", reviewer);

        // then — before/after 좌표 전문을 담는 두 경로 모두 412.
        assertThatThrownBy(() -> labelService.getHistory(seed.srcSn(), reviewer, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThatThrownBy(() -> versionService.diff(seed.fromHash(), seed.toHash(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    // ---------- H5 — 롤백(쓰기) 비대칭 ----------

    @Test
    @DisplayName("신고_상태에서는_롤백이_거부된다")
    void rollbackRejectedWhileDeidentFailed() throws IOException {
        // given — 배치 실패 경로 재현: 작업락 없이 DE_IDNTF_YN='F' 만 세팅한다
        //         (작업락 검사만 있으면 이 경로가 그대로 통과해 라벨을 교체한다).
        Seed seed = seed("H5", false);
        markDeidentDirectly(seed.rawSn(), "F");
        assertThat(workLockService.isRawLocked(seed.rawSn())).isFalse();

        // when / then — 412 로 거부되고 라벨 본문은 손대지 않는다.
        assertThatThrownBy(() -> versionService.rollback(seed.fromHash(), seed.srcSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(DeidentReportGateCoverageIT::errorCodeOf)
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(txTemplate.execute(s -> lblRepository.findBySrcSn(seed.srcSn())).stream()
                .map(LsDataLbl::getLblSn).sorted().toList())
                .isEqualTo(seed.lblSns().stream().sorted().toList());

        // when — 재비식별 완료로 'Y' 가 복원되면 롤백이 다시 허용된다(영구 폐쇄 아님).
        markDeidentDirectly(seed.rawSn(), "Y");
        assertThatCode(() -> versionService.rollback(seed.fromHash(), seed.srcSn(), reviewer))
                .doesNotThrowAnyException();
    }

    // ---------- 수용 기준 — resolve 후 재개방 ----------

    @Test
    @DisplayName("resolve_후에는_위_경로_전부가_다시_열리고_라벨이_그대로다")
    void allPathsReopenAfterResolveWithSameLabels() throws IOException {
        // given — APPROVED + 이미지 파일 + 버전 2건.
        Seed seed = seed("RSV", true);
        Long rprtSn = deidentReportService.report(seed.srcSn(), "얼굴 미블러 노출", reviewer);

        // when — 외부 솔루션 수동 재비식별 완료 → resolve('F'→'Y').
        seedDeidentArtifact(seed.rawSn());
        deidentReportService.resolveManually(rprtSn, reviewer);

        // then ① 전 경로 재개방 (별도 복원 절차 없음).
        assertAllReadPathsOpen(seed);

        // then ② 보존된 라벨이 그대로 — LBL_SN·좌표 동일.
        List<LsDataLbl> labels = txTemplate.execute(s -> lblRepository.findBySrcSn(seed.srcSn()));
        assertThat(labels).extracting(LsDataLbl::getLblSn)
                .containsExactlyInAnyOrderElementsOf(seed.lblSns());
        assertThat(labels).extracting(LsDataLbl::getLabelNm)
                .containsExactlyInAnyOrder("person", "car");
    }

    // ---------- 회귀 방어 — 'Y' 일반 영상 ----------

    @Test
    @DisplayName("deIdntfYn_이_Y_인_일반영상은_위_경로_전부_정상_동작한다")
    void normalVideoUnaffected() throws IOException {
        // given — 신고가 없는 정상('Y') 영상.
        Seed seed = seed("NRM", true);

        // when / then — 게이트가 일반 흐름을 건드리지 않는다.
        assertAllReadPathsOpen(seed);
        assertThatCode(() -> versionService.rollback(seed.fromHash(), seed.srcSn(), reviewer))
                .doesNotThrowAnyException();
    }

    /**
     * 게이트가 닫혀 있지 않을 때 열려 있어야 하는 조회 경로 전부 (H1·H2·H3·H4).
     * 롤백(H5)은 APPROVED 영상에서 export 재생성 통지를 유발하므로 별도 테스트에서 검증한다.
     */
    private void assertAllReadPathsOpen(Seed seed) throws IOException {
        assertThat(frameImageService.serve(seed.rawSn(), 0, false, reviewer).getStatusCode())
                .isEqualTo(HttpStatus.OK);                                              // H1-a
        assertThat(frameImageController.getImage(seed.srcSn(), reviewer).getStatusCode())
                .isEqualTo(HttpStatus.OK);                                              // H1-b
        assertThat(portalLabelService.loadFrameLabels(seed.srcSn(), portalUser).labels())
                .hasSize(2);                                                            // H2-a
        assertThat(portalLabelService.serveFrameImage(seed.srcSn(), portalUser).getStatusCode())
                .isEqualTo(HttpStatus.OK);                                              // H2-b
        assertThat(taskQueryController.getLabels(seed.rawSn(), null, PageRequest.of(0, 20), reviewer)
                .data().getTotalElements()).isPositive();                            // H3
        assertThatCode(() -> labelService.getHistory(seed.srcSn(), reviewer, PageRequest.of(0, 20)))
                .doesNotThrowAnyException();                                            // H4-a
        assertThat(versionService.diff(seed.fromHash(), seed.toHash(), reviewer).labels())
                .isNotEmpty();                                                          // H4-b
    }
}
