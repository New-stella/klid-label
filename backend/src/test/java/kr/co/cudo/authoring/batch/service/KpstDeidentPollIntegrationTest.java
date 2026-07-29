package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 4 / UC018 — KPST 비식별 폴링 end-to-end 통합 테스트 (Testcontainers PostgreSQL).
 *
 * <p>{@link KpstDeidentService}·{@link KpstDeidentTxService}·{@link WorkLockService}·신고 해소를
 * 실제 빈으로 구동하고 {@link KpstDeidentifyClient} 만 {@code @MockBean} 으로 스텁한다. DB 영속으로
 * submit→poll→download→완료(Y/MARKING_READY) 와 타임아웃→F 전이를 검증한다.
 *
 * <p>{@code kpst.deid.enabled=true} 활성 시 {@code KpstWebClientConfig} 가 WebClient 빈을 ca.crt 로
 * 구성한다 — 본 테스트는 {@code classpath:kpst/test-ca.crt}(자체 서명) 를 주입해 기동을 보장한다.
 * {@code KpstDeidentifyClient} 는 @MockBean 이라 실제 WebClient 호출은 발생하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "kpst.deid.enabled=true",
        "kpst.deid.base-url=https://localhost:9201",
        "kpst.deid.ca-cert-path=src/test/resources/kpst/test-ca.crt",
        "kpst.deid.poll-max-attempts=2",
        "kpst.deid.poll-timeout-minutes=180",
        // 폴링 잡 트리거가 통합 검증 중 자동 발화하지 않도록 충분히 늦춘다(테스트는 pollOne 직접 호출).
        "kpst.deid.poll-interval-sec=3600"
})
class KpstDeidentPollIntegrationTest {

    /** 비식별 저장 base(구 위치) — 롤백 전략 및 회수 폴백 대상. 정적 임시 디렉토리(빈 생성 시점 해석). */
    private static final Path DEID_BASE;
    /**
     * Phase 5A — 원본 영상이 놓이는 <b>허용 마운트 루트</b>(관제 NAS 모사). co-locate 기본 전략에서
     * KPST export_path 는 이 하위 {@code {rawSn}/deid/} 로 도출된다.
     */
    private static final Path RAW_MOUNT_ROOT;

    static {
        try {
            DEID_BASE = Files.createTempDirectory("kpst-poll-deid-base");
            RAW_MOUNT_ROOT = Files.createTempDirectory("kpst-poll-raw-mount");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void deidPath(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.deidentified-path", DEID_BASE::toString);
        // 고정 allowlist — 원본 경로(RAW_FILE_PATH_NM)의 상위가 이 하위여야 산출 base 가 도출된다.
        registry.add("authoring.storage.raw-mount-roots", RAW_MOUNT_ROOT::toString);
    }

    @TempDir
    Path tmp;

    @MockBean
    private KpstDeidentifyClient kpstClient;

    @Autowired
    private KpstDeidentService kpstDeidentService;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDeidentProcLogRepository procLogRepository;
    @Autowired
    private LsAuthWorkLockRepository workLockRepository;
    @Autowired
    private LsDeidentReportRepository reportRepository;

    /** 본 통합 테스트가 생성한 rawSn — 공유 Testcontainer DB 정합 위해 @AfterEach 에서 정리. */
    private final java.util.List<Long> createdRawSns = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        // 공유 DB 의 다른 @SpringBootTest(예: DeidentReportControllerTest) 가 전역 OPEN 신고 목록을
        // 조회하므로, 본 테스트가 남긴 신고/락/원본을 정리해 데이터 누수를 차단한다.
        for (Long rawSn : createdRawSns) {
            reportRepository.deleteAll(reportRepository.findAllByDataRawSnOrderByReportDtDesc(rawSn));
            workLockRepository.deleteAll(
                    workLockRepository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                            LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED));
            procLogRepository.deleteAll(procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn));
            videoRepository.findById(rawSn).ifPresent(videoRepository::delete);
        }
        createdRawSns.clear();
    }

    private LsDataRaw persistRaw() {
        // Phase 5A — 원본은 허용 마운트 루트 하위에 둔다(co-locate 산출 base 원천).
        Path rawFile = RAW_MOUNT_ROOT.resolve("clip-" + System.nanoTime() + ".mp4");
        try {
            Files.writeString(rawFile, "raw-video-bytes");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60);
        LsDataRaw saved = videoRepository.save(raw);
        createdRawSns.add(saved.getRawSn());
        return saved;
    }

    private KpstProgressResponse progressWith(int procState, Long dsId) {
        KpstProgressResponse.DsStatus ds = new KpstProgressResponse.DsStatus(
                dsId, "clip.mp4", procState, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw", 100.0, 1, List.of(ds));
        return new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
    }

    /**
     * no-copy: KPST 가 export_path 에 직접 쓴 결과를 시뮬레이션한다(Phase 5A — co-locate 신 위치
     * {@code {RAW_MOUNT_ROOT}/{rawSn}/deid/}). 완료 폴링 시 서비스는 진행조회 응답 fileName 에서
     * {@code {stem}-mask{ext}} 를 재구성해 이 디렉터리에서 회수한다(복사 없음).
     */
    private Path prepareDeidResultFile(Long rawSn, String fileName) {
        // 무결성 강화(B-ISSUE-01) 이후 텍스트 스텁은 산출물로 인정되지 않는다 — 실제 최소 mp4 로 시뮬레이션.
        return TestVideoFixtures.writeTinyMp4(
                RAW_MOUNT_ROOT.resolve(String.valueOf(rawSn)).resolve("deid").resolve(fileName));
    }

    @Test
    @DisplayName("통합_KPST_submit부터_poll_download_Y전이까지_DB반영된다")
    void submitToPollDownloadCompletesAndPersists() {
        // given — 원본 영상 적재 + 마킹 단계 작업락(재처리락) 보유 + OPEN 신고 1건
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        workLockRepository.save(LsAuthWorkLock.lockRawForRedeident(rawSn, "worker-1"));
        reportRepository.save(LsDeidentReport.createReport(rawSn, 100L, "개인정보 노출"));

        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));

        // when 1 — 위탁: WAITING + prjId 영속, DE_IDENT_YN 미전이
        LsDeidentProcLog submitted = kpstDeidentService.submit(raw);
        Long procLogSn = submitted.getProcLogSn();

        LsDeidentProcLog afterSubmit = procLogRepository.findById(procLogSn).orElseThrow();
        assertThat(afterSubmit.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        assertThat(afterSubmit.getKpstPrjId()).isEqualTo(101L);
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isNotEqualTo("Y");

        // when 2 — 1차 폴링: 진행중(state=1) → POLLING + 시도 증가
        when(kpstClient.retrieveProgress(any(), eq(101L))).thenReturn(progressWith(1, 202L));
        kpstDeidentService.pollOne(procLogRepository.findById(procLogSn).orElseThrow());

        LsDeidentProcLog afterPoll1 = procLogRepository.findById(procLogSn).orElseThrow();
        assertThat(afterPoll1.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_POLLING);
        assertThat(afterPoll1.getPollAttemptCnt()).isGreaterThanOrEqualTo(1);
        assertThat(afterPoll1.getKpstDatasetId()).isEqualTo(202L);

        // when 3 — 2차 폴링: 완료(state=2) → 응답 fileName 회수(no-copy) → DOWNLOADED/SUCCEEDED + Y/MARKING_READY
        // KPST 가 export_path 에 직접 쓴 결과 시뮬레이션 — 산출물명은 KPST 소관({원본stem}-mask{ext}).
        Path masked = prepareDeidResultFile(rawSn, "clip-mask.mp4");
        when(kpstClient.retrieveProgress(any(), eq(101L))).thenReturn(progressWith(2, 202L));
        kpstDeidentService.pollOne(procLogRepository.findById(procLogSn).orElseThrow());

        // then — procLog 완료 전이 DB 반영
        LsDeidentProcLog done = procLogRepository.findById(procLogSn).orElseThrow();
        assertThat(done.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_DOWNLOADED);
        assertThat(done.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        // 적재 경로 = co-locate 산출 위치의 KPST 산출물 절대경로(조합·추측이 아니라 실제 회수 결과).
        assertThat(done.getDeIdntfFilePathNm()).isEqualTo(masked.toString());

        // then — raw Y + MARKING_READY 전이
        LsDataRaw completedRaw = videoRepository.findById(rawSn).orElseThrow();
        assertThat(completedRaw.getDeIdntfYn()).isEqualTo("Y");
        assertThat(completedRaw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);

        // then — 신고 RESOLVED + 작업락 해제
        LsDeidentReport report = reportRepository.findAllByDataRawSnAndReportSttsCd(
                rawSn, LsDeidentReport.REPORT_OPEN).stream().findFirst().orElse(null);
        assertThat(report).as("OPEN 신고가 남아있으면 안 됨").isNull();
        boolean stillLocked = workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED);
        assertThat(stillLocked).isFalse();
    }

    @Test
    @DisplayName("통합_원본_부재시_위탁거부되고_F전이_MARKING_READY_미전이_DB반영된다")
    void submitRejectedWhenSourceMissingAndNeverMarkingReady() throws IOException {
        // given — DB 에는 원본 경로가 있으나 실제 파일이 없는 영상(B-ISSUE-01 라이브 재현).
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        Files.delete(Path.of(raw.getRawFilePathNm()));

        // when / then — 위탁 자체가 거부된다(외부 호출 없음).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> kpstDeidentService.submit(raw))
                .isInstanceOf(kr.co.cudo.authoring.common.exception.CustomException.class);
        org.mockito.Mockito.verify(kpstClient, org.mockito.Mockito.never())
                .createProject(any(KpstProjectRequest.class));

        // then — 'F' 는 별도 트랜잭션으로 커밋되고(거부 예외로 롤백되지 않음), 'Y'/MARKING_READY 는 없다.
        LsDataRaw reloaded = videoRepository.findById(rawSn).orElseThrow();
        assertThat(reloaded.getDeIdntfYn()).isEqualTo("F");
        assertThat(reloaded.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        // 폴링 대상(WAITING) procLog 가 남지 않는다 — 실패 기록만 존재.
        List<LsDeidentProcLog> logs = procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn);
        assertThat(logs).isNotEmpty();
        assertThat(logs).noneMatch(l -> LsDeidentProcLog.POLL_WAITING.equals(l.getPollSttsCd()));
        assertThat(logs).anyMatch(l -> LsDeidentProcLog.FAILED.equals(l.getProcSttsCd()));
    }

    @Test
    @DisplayName("통합_폴링_타임아웃시_F전이_DB반영된다")
    void pollTimeoutMarksFailedAndPersists() {
        // given — 위탁 완료(WAITING)된 영상. pollMaxAttempts=2 로 2회 폴링 시 타임아웃.
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));
        LsDeidentProcLog submitted = kpstDeidentService.submit(raw);
        Long procLogSn = submitted.getProcLogSn();

        // when — 계속 진행중(state=1) 응답으로 pollMaxAttempts(2) 만큼 폴링 → 시도 초과 타임아웃
        when(kpstClient.retrieveProgress(any(), eq(101L))).thenReturn(progressWith(1, 202L));
        kpstDeidentService.pollOne(procLogRepository.findById(procLogSn).orElseThrow());
        kpstDeidentService.pollOne(procLogRepository.findById(procLogSn).orElseThrow());

        // then — POLL_FAILED 종료값 + raw DE_IDENT_YN='F' DB 반영
        LsDeidentProcLog timedOut = procLogRepository.findById(procLogSn).orElseThrow();
        assertThat(timedOut.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(timedOut.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("통합_재기동_WAITING건이_재폴링_조회된다")
    void restartReloadsWaitingTargets() {
        // given — 위탁된 WAITING 건 (재기동 시 인메모리 상태 없이 DB 에서 복원)
        LsDataRaw raw = persistRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(new KpstProjectResponse("success", 101L));
        LsDeidentProcLog submitted = kpstDeidentService.submit(raw);

        // when — 폴링 잡이 사용하는 조회(WAITING/POLLING). 무제한 조회는 금지되어 상한이 필수다(B-ISSUE-82).
        List<LsDeidentProcLog> targets = procLogRepository.findByPollSttsCdIn(
                List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING),
                org.springframework.data.domain.PageRequest.of(0, 100));

        // then — 방금 위탁한 건이 재폴링 대상에 포함된다
        assertThat(targets).extracting(LsDeidentProcLog::getProcLogSn)
                .contains(submitted.getProcLogSn());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
