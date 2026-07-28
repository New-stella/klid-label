package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.ImageResizer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / R1 — 검수완료(APPROVED) 영상 재비식별(REDEIDENT) 완료 경로 DB 레벨 통합 테스트
 * (Testcontainers PostgreSQL, {@link PostgresContainerContextCustomizerFactory} 공유 컨테이너 자동 주입).
 *
 * <p>{@link KpstDeidentTxService#finishDownloadAndComplete}(REDEIDENT 완료 진입점)와 그 하위
 * {@link kr.co.cudo.authoring.batch.step.DeidentFrameAttacher}·{@link kr.co.cudo.authoring.auth.service.WorkLockService}
 * 를 실 빈으로 구동하고, ffmpeg/이미지 의존인 {@link FfmpegFrameExtractor.FrameWriter}·{@link ImageResizer} 만
 * {@code @MockBean} 으로 격리한다(기존 {@link KpstDeidentPollIntegrationTest} 의 의존 격리 전략과 동일).
 *
 * <p>실DB 조회로 AC1(라벨/프레임 행 불변)·AC2(APPROVED 강등 없음)·AC5(비식별 프레임 경로 채움 →
 * V_COMPLETED_FRAME.DEIDENTIFIED_PATH 노출)를 단언한다. 프로덕션 코드는 변경하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // KpstDeidentTxService 는 @ConditionalOnProperty(kpst.deid.enabled=true) — 활성화해야 빈이 등록된다.
        "kpst.deid.enabled=true",
        "kpst.deid.base-url=https://localhost:9201",
        "kpst.deid.ca-cert-path=src/test/resources/kpst/test-ca.crt",
        // 폴링 잡이 통합 검증 중 자동 발화하지 않도록 충분히 늦춘다(본 테스트는 TxService 를 직접 호출).
        "kpst.deid.poll-interval-sec=3600"
})
class KpstRedeidentCompletionIntegrationTest {

    /** DeidentFrameAttacher.baseDeidPath 가 가리킬 비식별 저장 base — 정적 임시 디렉토리(빈 생성 시점 해석). */
    private static final Path DEID_BASE;

    static {
        try {
            DEID_BASE = Files.createTempDirectory("redeident-deid-base");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void deidPath(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.deidentified-path", DEID_BASE::toString);
    }

    @MockBean
    private FfmpegFrameExtractor.FrameWriter frameWriter;
    @MockBean
    private ImageResizer imageResizer;

    @Autowired
    private KpstDeidentTxService txService;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDataSrcRepository srcRepository;
    @Autowired
    private LsDataLblRepository lblRepository;
    @Autowired
    private LsRawDataStatusRepository rawStatusRepository;
    @Autowired
    private LsDeidentProcLogRepository procLogRepository;
    @Autowired
    private LsAuthWorkLockRepository workLockRepository;

    private final TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager em;

    private final List<Long> createdRawSns = new ArrayList<>();

    KpstRedeidentCompletionIntegrationTest(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        // 공유 컨테이너 DB 정합 — 본 테스트가 생성한 행만 역참조 순서로 정리.
        for (Long rawSn : createdRawSns) {
            lblRepository.deleteAllByRawSn(rawSn);
            srcRepository.deleteAll(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
            procLogRepository.deleteAll(procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn));
            workLockRepository.deleteAll(
                    workLockRepository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                            LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED));
            workLockRepository.deleteAll(
                    workLockRepository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                            LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_RELEASED));
            rawStatusRepository.findById(rawSn).ifPresent(rawStatusRepository::delete);
            videoRepository.findById(rawSn).ifPresent(videoRepository::delete);
        }
        createdRawSns.clear();
    }

    // ---------------------------------------------------------------------
    // 시드 헬퍼
    // ---------------------------------------------------------------------

    /** APPROVED + de_ident_yn='N' + prvc_type='UNKNOWN' 영상 + 프레임 N개 + 라벨 M개 + REDEIDENT procLog + 작업락. */
    private record Seed(Long rawSn, Long procLogSn, String deidFile, int frameCount, int labelCount) {
    }

    private Seed seedApprovedRedeidentTarget(int frameCount, int labelsPerFrame) {
        return txTemplate.execute(status -> {
            // 1) ls_data_raw — de_ident_yn='N', prvc_type_cd='UNKNOWN' 로 직접 강제.
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "redeident-clip-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                    LsDataRaw.PRVC_TYPE_UNKNOWN, "/orig/redeident-" + System.nanoTime() + ".mp4", null, 60);
            LsDataRaw savedRaw = videoRepository.saveAndFlush(raw);
            Long rawSn = savedRaw.getRawSn();
            createdRawSns.add(rawSn);
            // de_ident_yn 기본값이 'N' 이 아닐 수 있으므로 명시적으로 'N' 강제(시드 정합).
            em.createNativeQuery("UPDATE LS_DATA_RAW SET DE_IDENT_YN = 'N' WHERE RAW_SN = :sn")
                    .setParameter("sn", rawSn).executeUpdate();

            // 2) ls_raw_data_status — APPROVED.
            LsRawDataStatus rawStatus = LsRawDataStatus.initial(rawSn);
            rawStatus.transitionTo(LsRawDataStatus.STTS_APPROVED);
            rawStatusRepository.saveAndFlush(rawStatus);

            // 3) ls_data_src 프레임 N개 — 원본 경로 보유, 비식별 경로(NULL).
            int labelCount = 0;
            for (int i = 0; i < frameCount; i++) {
                Path origFrame = origFrameFile(rawSn, i);
                LsDataSrc src = LsDataSrc.create(rawSn, i, origFrame.toString(), null);
                LsDataSrc savedSrc = srcRepository.saveAndFlush(src);
                // 4) ls_data_lbl 라벨 — 각 프레임에 labelsPerFrame 개.
                for (int j = 0; j < labelsPerFrame; j++) {
                    LsDataLbl lbl = LsDataLbl.createManual(
                            savedSrc.getSrcSn(), LsDataLbl.TYPE_BBOX, null,
                            "person", "[[1,2],[3,4]]", 100L);
                    lblRepository.saveAndFlush(lbl);
                    labelCount++;
                }
            }

            // 5) ls_deident_proc_log — REQ_KIND=REDEIDENT, 다운로드 완료(폴링 진행) 상태.
            String deidFile = deidVideoFile(rawSn).toString();
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    rawSn, "req-" + rawSn, savedRaw.getRawFilePathNm(), "tester");
            procLog.markRedeident();
            procLog.markKpstSubmitted(101L, 202L);
            LsDeidentProcLog savedLog = procLogRepository.saveAndFlush(procLog);

            // 6) 작업락 LOCKED 1건.
            workLockRepository.saveAndFlush(LsAuthWorkLock.lockRawForRedeident(rawSn, "worker-1"));

            return new Seed(rawSn, savedLog.getProcLogSn(), deidFile, frameCount, labelCount);
        });
    }

    /** 비식별 영상 파일 — verifyDeidFile(무결성: 정규파일+크기하한+컨테이너 시그니처) / attach 통과용. */
    private Path deidVideoFile(Long rawSn) {
        return kr.co.cudo.authoring.support.TestVideoFixtures.writeTinyMp4(
                DEID_BASE.resolve("video-" + rawSn + ".mp4"));
    }

    /** 원본 프레임 파일 — verifyResolution 의 원본 측정(readDimensions) 입력 경로용. */
    private Path origFrameFile(Long rawSn, int frameNo) {
        try {
            Path dir = DEID_BASE.resolve("orig").resolve(String.valueOf(rawSn));
            Files.createDirectories(dir);
            Path f = dir.resolve("orig-" + frameNo + ".jpg");
            Files.writeString(f, "ORIG-FRAME");
            return f;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** FrameWriter mock — writeFrameByNumber 가 실제 더미 파일을 생성하도록(존재/해상도 측정 대상). */
    private void stubFrameWriterWritesFile() {
        when(frameWriter.sourceExists(any(Path.class))).thenReturn(true);
        try {
            org.mockito.Mockito.doAnswer(inv -> {
                Path output = inv.getArgument(1);
                Files.createDirectories(output.getParent());
                Files.writeString(output, "DEID-FRAME");
                return null;
            }).when(frameWriter).writeFrameByNumber(any(Path.class), any(Path.class), org.mockito.ArgumentMatchers.anyInt());
        } catch (IOException ignored) {
            // doAnswer 스텁 등록은 체크예외를 던지지 않음.
        }
    }

    // ---------------------------------------------------------------------
    // 성공 경로 — AC1/AC2/AC5
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("통합_REDEIDENT완료시_APPROVED유지_라벨프레임불변_비식별경로채움_DB반영(AC1·AC2·AC5)")
    void redeidentCompletionKeepsApprovedAndLabelsAndFillsDeidPath() {
        // given — APPROVED + N=3 프레임 + M=6 라벨(프레임당 2) + de_ident_yn='N' + prvc='UNKNOWN' + 락 LOCKED.
        int frameCount = 3;
        Seed seed = seedApprovedRedeidentTarget(frameCount, 2);
        Long rawSn = seed.rawSn();
        // 동일 해상도 반환 → 해상도 가드 통과.
        when(imageResizer.readDimensions(any(Path.class))).thenReturn(new int[]{1920, 1080});
        stubFrameWriterWritesFile();

        // when — REDEIDENT 완료 진입점(다운로드 완료 + 완료 후처리 원자 트랜잭션).
        txService.finishDownloadAndComplete(rawSn, seed.procLogSn(), 202L, seed.deidFile());
        em.clear();

        // then AC1 — 라벨 행 수/내용 불변(M 유지), 새 프레임 행 없음(N 유지).
        assertThat(lblRepository.countByRawSn(rawSn))
                .as("AC1: 라벨 행 수 불변(M)").isEqualTo(seed.labelCount());
        assertThat(srcRepository.countByRawSn(rawSn))
                .as("AC1: 프레임 행 수 불변(N) — 새 LS_DATA_SRC 행 0").isEqualTo(frameCount);

        // then AC2 — 검수 워크플로우 상태 APPROVED 강등 없음.
        assertThat(rawStatusRepository.findById(rawSn).orElseThrow().getDataSttsCd())
                .as("AC2: APPROVED 강등 없음").isEqualTo(LsRawDataStatus.STTS_APPROVED);

        // then AC5 — 각 프레임 비식별 경로(de_idntf_src_file_path_nm) 채움(NOT NULL).
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        assertThat(frames).hasSize(frameCount);
        assertThat(frames).allSatisfy(s ->
                assertThat(s.getDeIdntfSrcFilePathNm())
                        .as("AC5: 비식별 프레임 경로 채움").isNotBlank());

        // then AC5(view) — V_COMPLETED_FRAME.DEIDENTIFIED_PATH 노출(APPROVED + 비식별 경로 NOT NULL).
        @SuppressWarnings("unchecked")
        List<Object[]> viewRows = em.createNativeQuery(
                        "SELECT FRAME_NO, DEIDENTIFIED_PATH FROM V_COMPLETED_FRAME WHERE RAW_SN = :sn ORDER BY FRAME_NO")
                .setParameter("sn", rawSn).getResultList();
        assertThat(viewRows).as("AC5: APPROVED 영상 프레임이 데이터마트 View 에 N건 노출").hasSize(frameCount);
        assertThat(viewRows).allSatisfy(row ->
                assertThat(row[1]).as("AC5: View DEIDENTIFIED_PATH NOT NULL").isNotNull());

        // 보너스 — de_ident_yn='Y', prvc_type_cd='PRVC'(UNKNOWN 정정), 작업락 해제.
        LsDataRaw completed = videoRepository.findById(rawSn).orElseThrow();
        assertThat(completed.getDeIdntfYn()).as("보너스: de_ident_yn='Y'").isEqualTo("Y");
        assertThat(completed.getPrvcTypeCd()).as("보너스: PRVC 정정").isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        boolean stillLocked = workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED);
        assertThat(stillLocked).as("보너스: 작업락 해제").isFalse();

        // 보너스 — procLog 다운로드/성공 전이.
        LsDeidentProcLog doneLog = procLogRepository.findById(seed.procLogSn()).orElseThrow();
        assertThat(doneLog.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(doneLog.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
    }

    // ---------------------------------------------------------------------
    // 실패 경로 — 해상도 불일치 → 전체 롤백(라벨/상태/de_ident_yn 불변)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("통합_REDEIDENT_해상도불일치시_전체롤백_APPROVED유지_라벨불변_deIdentYn미변경")
    void redeidentResolutionMismatchRollsBackAndKeepsApproved() {
        // given — 정상 시드. 단, 비식별 출력 해상도가 원본과 다르게 측정되도록 mock 구성.
        int frameCount = 2;
        Seed seed = seedApprovedRedeidentTarget(frameCount, 2);
        Long rawSn = seed.rawSn();
        stubFrameWriterWritesFile();
        // 원본=1920x1080, 비식별=1280x720 → verifyResolution 불일치 예외(전체 롤백).
        // readDimensions 는 원본/비식별 순으로 호출됨(attacher.verifyResolution).
        when(imageResizer.readDimensions(any(Path.class)))
                .thenReturn(new int[]{1920, 1080})
                .thenReturn(new int[]{1280, 720});

        // when/then — 완료 트랜잭션이 롤백되며 예외 전파(폴링 오케스트레이터가 별도로 terminal 종결).
        assertThatThrownBy(() ->
                txService.finishDownloadAndComplete(rawSn, seed.procLogSn(), 202L, seed.deidFile()))
                .isInstanceOf(CustomException.class);
        em.clear();

        // then — 라벨 불변(M), 프레임 행 불변(N), 비식별 경로 미채움(롤백).
        assertThat(lblRepository.countByRawSn(rawSn)).isEqualTo(seed.labelCount());
        assertThat(srcRepository.countByRawSn(rawSn)).isEqualTo(frameCount);
        assertThat(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn))
                .allSatisfy(s -> assertThat(s.getDeIdntfSrcFilePathNm()).isNull());

        // then — APPROVED 유지(강등 없음), de_ident_yn 미변경('N').
        assertThat(rawStatusRepository.findById(rawSn).orElseThrow().getDataSttsCd())
                .isEqualTo(LsRawDataStatus.STTS_APPROVED);
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn())
                .as("롤백: de_ident_yn 'Y' 로 가지 않음").isNotEqualTo("Y");
    }
}
