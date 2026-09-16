package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 3 / UC018 — KpstDeidentService 오케스트레이션 단위 테스트.
 *
 * <p>위탁(submit) / 폴링(pollOne) 분기 검증. 상태 전이의 실제 영속은 {@link KpstDeidentTxService}
 * 로 위임되므로 본 테스트는 위탁 호출·회수 경로 산출·tx 위임 호출을 검증한다.
 *
 * <p>shared-mount no-copy 모델: KPST 가 결과를 우리 비식별 저장소({@code {base}/videos/{rawSn}/})
 * 에 직접 쓰며, 완료 시 진행조회 응답의 {@code dsStatus.fileName} 을 그대로 회수 경로로 쓴다.
 * 복사·GET /download 는 없다(클라이언트가 KPST 출력 파일을 옮기지 않는다).
 */
class KpstDeidentServiceTest {

    @TempDir
    Path tmp;

    private KpstDeidentifyClient kpstClient;
    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private KpstDeidentTxService txService;
    private BatchTransitionService batchTransitionService;
    /** Phase C-2 — 비동기 제출의 완료 핸들러(ACK/실패 기록). 단위 테스트에서는 호출 위임만 검증한다. */
    private KpstSubmitOutcomeRecorder outcomeRecorder;
    /** R9 — 마스킹 옵션 3종 조달원. 기본 스텁은 시드 기본값(0 / 1.0 / 0)을 돌려준다. */
    private SystemConfigService systemConfigService;
    private KpstDeidentService service;
    private Path baseDeid;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() throws Exception {
        kpstClient = mock(KpstDeidentifyClient.class);
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        txService = mock(KpstDeidentTxService.class);
        batchTransitionService = mock(BatchTransitionService.class);
        outcomeRecorder = mock(KpstSubmitOutcomeRecorder.class);
        systemConfigService = mock(SystemConfigService.class);
        // 기본 스텁 = V178 시드값. 개별 케이스가 필요할 때만 덮어쓴다.
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_MASKING_TYPE)).thenReturn(0);
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_DB_SAVE)).thenReturn(0);
        when(systemConfigService.getDouble(ConfigKeys.KPST_DEID_MASKING_RANGE)).thenReturn(1.0);
        // Phase C-2 — 원장 발급은 별도 REQUIRES_NEW 빈(선커밋)으로 위임됐다. 단위 테스트에서는
        // 실제 저장 대신 procLogSn 이 발급된 WAITING 원장을 돌려주는 스텁으로 대체한다.
        when(txService.issueSubmitLedger(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> {
                    LsDeidentProcLog p = LsDeidentProcLog.request(
                            inv.getArgument(0), null, inv.getArgument(1), "batch");
                    if (Boolean.TRUE.equals(inv.getArgument(2))) {
                        p.markRedeident();
                    }
                    p.markKpstSubmitPending();
                    setField(p, "procLogSn", 1L);
                    return p;
                });

        baseDeid = tmp.resolve("deid");
        // 기존 케이스는 구 위치({deid_base}/videos/{rawSn}/) 계약을 검증하므로 롤백 전략 리졸버를 주입한다.
        // co-locate 신 위치(export_path) 검증은 별도 케이스에서 수행한다.
        service = newService(kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport.labelingRoot(
                tmp.resolve("labeling"), baseDeid));

        when(procLogRepository.save(any(LsDeidentProcLog.class))).thenAnswer(inv -> {
            LsDeidentProcLog p = inv.getArgument(0);
            setField(p, "procLogSn", 1L);
            return p;
        });

        logCapture = new ListAppender<>();
        logCapture.start();
        serviceLogger().addAppender(logCapture);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        serviceLogger().detachAppender(logCapture);
    }

    private static ch.qos.logback.classic.Logger serviceLogger() {
        return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KpstDeidentService.class);
    }

    /** 캡처된 WARN 로그 중 주어진 조각을 포함하는 것이 있는지. */
    private boolean warnLogged(String fragment) {
        return logCapture.list.stream()
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains(fragment));
    }

    /** 공통 설정이 주입된 서비스 인스턴스 — 산출 base 전략(리졸버)만 케이스별로 바꾼다. */
    private KpstDeidentService newService(VideoArtifactRootResolver resolver) {
        // 완료 신호 스케줄러는 immediate — 전용 풀 대신 호출 스레드에서 즉시 전달해 단위 테스트를
        // 결정적으로 만든다(프로덕션은 kpstSubmitScheduler 전용 풀).
        KpstDeidentService s = new KpstDeidentService(kpstClient, videoRepository, procLogRepository,
                txService, resolver, batchTransitionService, outcomeRecorder,
                reactor.core.scheduler.Schedulers.immediate(), systemConfigService);
        setField(s, "deidPath", baseDeid.toString());
        setField(s, "creatorId", "authoring");
        setField(s, "reqUserId", "authoring");
        setField(s, "pollMaxAttempts", 3);
        setField(s, "pollTimeoutMinutes", 60L);
        setField(s, "verifySourceExists", true);
        // 유예 재확인은 케이스에서 명시적으로 켠다(기본 0 = 즉시 판정, 테스트 지연 없음).
        setField(s, "resultRecheckDelayMs", 0L);
        // Phase C-2 — 제출 ACK 대기 유예(초). 기본은 "유예 안"(회수 안 함) 상태로 둔다.
        setField(s, "submitAckGraceSec", 180L);
        invoke(s, "initBasePath");
        return s;
    }

    private LsDataRaw newRaw() {
        Path rawFile = tmp.resolve("clip.mp4");
        try {
            java.nio.file.Files.writeString(rawFile, "video");
        } catch (Exception ignored) { /* test fixture */ }
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60);
        setField(raw, "rawSn", 9001L);
        return raw;
    }

    private LsDeidentProcLog submittedProcLog() {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(procLog, "procLogSn", 1L);
        procLog.markKpstSubmitted(101L, null);
        return procLog;
    }

    private KpstProgressResponse progressWith(int procState, Long dsId) {
        return progressWithFileName(procState, dsId, "clip.mp4");
    }

    private KpstProgressResponse progressWithFileName(int procState, Long dsId, String fileName) {
        KpstProgressResponse.DsStatus ds = new KpstProgressResponse.DsStatus(
                dsId, fileName, procState, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 100.0, 1, List.of(ds));
        return new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
    }

    /** no-copy: KPST 가 export_path 에 직접 쓴 결과를 시뮬레이션하는 회수 경로. */
    private Path deidPathFor(String fileName) {
        return baseDeid.resolve("videos").resolve("9001").resolve(fileName);
    }

    /**
     * KPST 산출물 시뮬레이션 — <b>실제 유효 영상 바이트</b>(최소 mp4)로 쓴다.
     * 무결성 강화 후에는 텍스트 스텁이 산출물로 인정되지 않으므로(B-ISSUE-01) 픽스처도 실제 영상이어야 한다.
     */
    private Path writeDeidResult(String fileName) {
        return TestVideoFixtures.writeTinyMp4(deidPathFor(fileName));
    }

    /** 산출물 자리에 임의 바이트를 쓴다(불완전/위장 산출물 케이스 전용). */
    private Path writeDeidBytes(String fileName, byte[] bytes) throws Exception {
        Path p = deidPathFor(fileName);
        java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.write(p, bytes);
        return p;
    }

    // ────────────────────────── 위탁 ──────────────────────────

    @Test
    @DisplayName("submit이_upload_미호출하고_createProject만_호출한다")
    void submitCallsCreateProjectOnly() {
        // given — shared-mount 모델: 업로드 없이 공유 경로 참조로 createProject 만 호출
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        LsDeidentProcLog procLog = service.submit(raw);

        // then — createProject 1회만, 그 외 kpstClient 상호작용 없음(upload/download 미호출)
        verify(kpstClient).createProject(any(KpstProjectRequest.class));
        verifyNoMoreInteractions(kpstClient);
        // Phase C-2 — 반환 시점 원장은 "WAITING + prjId 미정(ACK 대기)". prjId 는 완료 핸들러가 기록한다.
        assertThat(procLog.getKpstPrjId()).isNull();
        assertThat(procLog.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        verify(outcomeRecorder).onAccepted(eq(9001L), eq(1L),
                org.mockito.ArgumentMatchers.argThat(r -> r != null && r.prjId() == 101L));
        // DE_IDNTF_YN 미전이(완료 대기)
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
    }

    @Test
    @DisplayName("input_path는_원본_부모디렉터리에_끝슬래시_포함이다")
    void submitInputPathIsParentWithTrailingSlash() {
        // given
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        service.submit(raw);

        // then — input_path = 원본 부모디렉터리 + 끝 슬래시(규격 §22.3.3)
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        assertThat(captor.getValue().inputPath()).isEqualTo(tmp.toString() + "/");
    }

    @Test
    @DisplayName("submit_export_path는_우리base_videos_rawSn_슬래시이고_디렉터리가_사전생성된다")
    void submitExportPathIsOurBaseAndDirCreated() {
        // given
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        service.submit(raw);

        // then — export_path = {STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/ (KPST 결과 WRITE 대상)
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        Path exportDir = baseDeid.resolve("videos").resolve("9001");
        assertThat(captor.getValue().exportPath()).isEqualTo(exportDir + "/");
        // KPST 쓰기 대상 디렉터리가 사전 생성되었는지
        assertThat(exportDir).exists();
    }

    @Test
    @DisplayName("files는_원본_파일명만_포함한다")
    void submitFilesContainsOriginalFileNameOnly() {
        // given
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        service.submit(raw);

        // then — files 는 디렉터리 없는 원본 파일명만
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        assertThat(captor.getValue().files()).containsExactly("clip.mp4");
        assertThat(captor.getValue().projectName()).isEqualTo("raw9001");
    }

    // ────────────────────── 재위탁 프로젝트 이름 (INT-004 · AC-1133) ──────────────────────

    /**
     * 원장 선발급을 실제처럼 흉내 낸다 — 호출마다 번호가 커지고, "앞선 이력 존재" 판정은 이미 발급된
     * 번호 중 이번 번호보다 작은 것이 있는지로 답한다(리포지토리 파생 쿼리와 같은 의미).
     */
    private void stubSequentialLedger(long firstSn) {
        java.util.List<Long> issued = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicLong next = new java.util.concurrent.atomic.AtomicLong(firstSn);
        // doAnswer 형식 — when(mock.call()) 로 덮으면 setUp 의 기존 스텁 응답이 null 인자로 실행된다.
        org.mockito.Mockito.doAnswer(inv -> {
                    LsDeidentProcLog p = LsDeidentProcLog.request(
                            inv.getArgument(0), null, inv.getArgument(1), "batch");
                    if (Boolean.TRUE.equals(inv.getArgument(2))) {
                        p.markRedeident();
                    }
                    p.markKpstSubmitPending();
                    long sn = next.getAndIncrement();
                    setField(p, "procLogSn", sn);
                    issued.add(sn);
                    return p;
                }).when(txService).issueSubmitLedger(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.doAnswer(inv -> {
                    long cur = inv.getArgument(1);
                    return issued.stream().anyMatch(sn -> sn < cur);
                }).when(procLogRepository).existsByDataRawSnAndProcLogSnLessThan(eq(9001L), any());
    }

    /** 주어진 순서대로 위탁하고 KPST 에 나간 프로젝트 이름을 호출 순으로 돌려준다. */
    private List<String> submitAndCaptureNames(boolean... redeidentFlags) {
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));
        for (boolean redeident : redeidentFlags) {
            service.submit(raw, redeident);
        }
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient, org.mockito.Mockito.times(redeidentFlags.length)).createProject(captor.capture());
        return captor.getAllValues().stream().map(KpstProjectRequest::projectName).toList();
    }

    @Test
    @DisplayName("그_영상의_첫_위탁은_종전대로_raw_영상번호_이름을_쓴다")
    void firstSubmitUsesPlainName() {
        stubSequentialLedger(57L);

        List<String> names = submitAndCaptureNames(false);

        assertThat(names).containsExactly("raw9001");
    }

    @Test
    @DisplayName("다시_위탁하면_이번_회차_원장_번호가_접미로_붙어_첫_이름과_다르다")
    void secondSubmitAppendsLedgerNumber() {
        stubSequentialLedger(57L);

        List<String> names = submitAndCaptureNames(false, false);

        assertThat(names).containsExactly("raw9001", "raw9001r58");
        assertThat(names.get(1)).isNotEqualTo(names.get(0));
    }

    @Test
    @DisplayName("세_번째_위탁_이름은_앞의_두_이름과_모두_다르다")
    void thirdSubmitDiffersFromPreviousTwo() {
        stubSequentialLedger(57L);

        List<String> names = submitAndCaptureNames(false, false, false);

        assertThat(names).containsExactly("raw9001", "raw9001r58", "raw9001r59");
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("검수완료_재비식별_위탁도_앞선_이력이_있으면_접미를_붙인다")
    void redeidentSubmitAlsoAppendsSuffix() {
        stubSequentialLedger(57L);

        List<String> names = submitAndCaptureNames(false, true);

        assertThat(names).containsExactly("raw9001", "raw9001r58");
    }

    @Test
    @DisplayName("위탁_프로젝트_이름은_영문과_숫자만으로_이루어진다")
    void projectNameIsAlphanumericOnly() {
        stubSequentialLedger(57L);

        List<String> names = submitAndCaptureNames(false, true, false);

        assertThat(names).allMatch(n -> n.matches("[A-Za-z0-9]+"));
    }

    @Test
    @DisplayName("접미_번호는_이번_회차에_선발급된_원장_번호_그대로다")
    void suffixIsTheIssuedLedgerNumber() {
        // 원장 번호가 크게 튀어도(다른 영상 행이 사이에 끼는 실제 상황) 그 번호가 그대로 실린다.
        stubSequentialLedger(1000L);
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        service.submit(raw);
        LsDeidentProcLog second = service.submit(raw);

        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient, org.mockito.Mockito.times(2)).createProject(captor.capture());
        assertThat(captor.getAllValues().get(1).projectName())
                .isEqualTo("raw9001r" + second.getProcLogSn());
        verify(procLogRepository).existsByDataRawSnAndProcLogSnLessThan(9001L, second.getProcLogSn());
    }

    // ────────────────────── R9 마스킹 옵션(설정 연동) ──────────────────────

    /** 위탁 요청 캡처 헬퍼 — createProject 스텁 + 제출 후 요청 DTO 반환. */
    private KpstProjectRequest captureSubmittedRequest() {
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));
        service.submit(raw);
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("R9_운영자가_설정한_마스킹_옵션_3종이_위탁요청에_실린다")
    void submitCarriesConfiguredMaskingOptions() {
        // given — 운영자가 모자이크(2) · 배율 1.5 · 프레임 저장(1) 로 바꿔 둔 상태
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_MASKING_TYPE)).thenReturn(2);
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_DB_SAVE)).thenReturn(1);
        when(systemConfigService.getDouble(ConfigKeys.KPST_DEID_MASKING_RANGE)).thenReturn(1.5);

        // when
        KpstProjectRequest req = captureSubmittedRequest();

        // then
        assertThat(req.maskingType()).isEqualTo(2);
        assertThat(req.dbSave()).isEqualTo(1);
        assertThat(req.maskingRange()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("R9_마스킹_범위는_실수_0_5를_잘라먹지_않는다")
    void submitCarriesFractionalMaskingRange() {
        // given — 구 결함: 필드가 int 라 0.5 가 0 으로 잘려 전송 자체가 불가능했다.
        when(systemConfigService.getDouble(ConfigKeys.KPST_DEID_MASKING_RANGE)).thenReturn(0.5);

        // when / then
        assertThat(captureSubmittedRequest().maskingRange()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("R9_설정조회가_예외를_던져도_위탁은_실패하지_않고_기본값으로_진행한다")
    void submitFallsBackWhenConfigLookupThrows() {
        // given — 선커밋된 원장 뒤·외부 호출 직전이라 여기서 예외가 나가면 위탁 자체가 'F' 로 종결된다.
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_MASKING_TYPE))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 키를 찾을 수 없습니다."));
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_DB_SAVE))
                .thenThrow(new CustomException(ErrorCode.INTERNAL_ERROR, "CONFIG_VALUE 가 숫자가 아닙니다"));
        when(systemConfigService.getDouble(ConfigKeys.KPST_DEID_MASKING_RANGE))
                .thenThrow(new CustomException(ErrorCode.INVALID_INPUT, "CONFIG_TYPE_CD 이 DECIMAL 이 아닙니다"));

        // when — 예외가 전파되지 않고 위탁이 그대로 나간다.
        KpstProjectRequest req = captureSubmittedRequest();

        // then — 규격 기본값 폴백(fail-safe)
        assertThat(req.maskingType()).isEqualTo(KpstProjectRequest.DEFAULT_MASKING_TYPE);
        assertThat(req.dbSave()).isEqualTo(KpstProjectRequest.DEFAULT_DB_SAVE);
        assertThat(req.maskingRange()).isEqualTo(KpstProjectRequest.DEFAULT_MASKING_RANGE);
    }

    @Test
    @DisplayName("R9_DB에_허용목록_밖_값이_있으면_기본값으로_폴백한다_fail_closed")
    void submitFallsBackWhenStoredValueOutOfAllowedSet() {
        // given — 입구 검증을 우회한 수기 수정(1 은 벤더 미할당 · 배율 3.0 은 범위 밖 · db_save 9)
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_MASKING_TYPE)).thenReturn(1);
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_DB_SAVE)).thenReturn(9);
        when(systemConfigService.getDouble(ConfigKeys.KPST_DEID_MASKING_RANGE)).thenReturn(3.0);

        // when
        KpstProjectRequest req = captureSubmittedRequest();

        // then — 잘못된 코드값을 외부로 그대로 보내지 않는다.
        assertThat(req.maskingType()).isEqualTo(KpstProjectRequest.DEFAULT_MASKING_TYPE);
        assertThat(req.dbSave()).isEqualTo(KpstProjectRequest.DEFAULT_DB_SAVE);
        assertThat(req.maskingRange()).isEqualTo(KpstProjectRequest.DEFAULT_MASKING_RANGE);
    }

    @Test
    @DisplayName("R9_expQuality_expFormat은_설정으로_열지_않고_규격_기본값_그대로다")
    void submitKeepsUnsupportedVendorFieldsAtSpecDefaults() {
        // given — 벤더 미지원 회신 필드. 화면에 노출하지 않지만 규격상 필수라 요청에는 계속 싣는다.
        when(systemConfigService.getInt(ConfigKeys.KPST_DEID_MASKING_TYPE)).thenReturn(3);

        // when
        KpstProjectRequest req = captureSubmittedRequest();

        // then
        assertThat(req.expQuality()).isEqualTo(KpstProjectRequest.DEFAULT_EXP_QUALITY);
        assertThat(req.expFormat()).isEqualTo(KpstProjectRequest.DEFAULT_EXP_FORMAT);
    }

    @Test
    @DisplayName("제출실패는_예외전파대신_완료핸들러가_기록한다_PhaseC2")
    void submitFailureRecordedByOutcomeHandler() {
        // given — createProject(외부 호출)가 에러 신호로 종료(논블로킹 제출의 정상적인 실패 표현).
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.error(
                        new CustomException(ErrorCode.EXTERNAL_API_ERROR, "boom")));

        // when — ACK 왕복을 기다리지 않으므로 제출 이후 실패는 호출 스레드로 전파되지 않는다.
        LsDeidentProcLog procLog = service.submit(raw);

        // then — 종단 상태 기록은 완료 핸들러가 별도 커밋으로 수행한다('F' + 원장 FAILED).
        assertThat(procLog.getProcLogSn()).isEqualTo(1L);
        verify(outcomeRecorder).onSubmitFailed(eq(9001L), eq(1L), any(Throwable.class));
        verify(outcomeRecorder, never()).onAccepted(any(), any(), any());
    }

    @Test
    @DisplayName("제출구독이_동기예외로_거부되면_완료핸들러가_실패로_기록한다_전용풀포화")
    void submitSubscriptionRejectionRecordedAsFailure() {
        // given — 전용 풀 포화(AbortPolicy) 등으로 구독 자체가 즉시 예외를 던지는 상황.
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("pool full"));

        // when — 예외를 밖으로 던지지 않는다(파이프라인을 실패로 만들지 않음).
        service.submit(raw);

        // then — 확정 실패와 동일하게 기록된다(기록 유실 != 위탁 유실).
        verify(outcomeRecorder).onSubmitFailed(eq(9001L), eq(1L), any(Throwable.class));
    }

    @Test
    @DisplayName("제출_사전조건_실패는_원장을_별도커밋_종결하고_동기예외를_전파한다")
    void submitPrepareFailureTerminatesLedgerAndThrows() {
        // given — 부모 디렉터리가 없는 손상 경로(외부에 아무것도 나가지 않는 사전 조건 실패).
        setField(service, "verifySourceExists", false);
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);

        // when / then — 기존 계약(동기 예외) 유지 + 실패 흔적은 별도 트랜잭션으로 커밋.
        assertThatThrownBy(() -> service.submit(raw)).isInstanceOf(CustomException.class);
        verify(txService).failSubmit(eq(1L), eq(9001L),
                eq(KpstDeidentService.SUBMIT_FAILED_CODE), any());
        verify(kpstClient, never()).createProject(any());
    }

    @Test
    @DisplayName("원본경로_부모디렉터리가_null이면_F마킹하고_예외전파_createProject미호출")
    void submitNullParentRejected() {
        // given — 부모 디렉터리가 없는 비정상 경로(파일명만) → 경계 방어(CWE-22/입력검증).
        //  원본 실재 가드(B-ISSUE-01)를 끈 상태에서 <부모 null> 분기 자체가 여전히 거부하는지 본다.
        setField(service, "verifySourceExists", false);
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "clip.mp4", null, 60);
        setField(raw, "rawSn", 9001L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        // when / then — createProject 호출 전에 거부, F 마킹(별도 커밋) + 예외 전파
        assertThatThrownBy(() -> service.submit(raw)).isInstanceOf(CustomException.class);
        verify(kpstClient, never()).createProject(any());
        verify(txService).failSubmit(eq(1L), eq(9001L), eq(KpstDeidentService.SUBMIT_FAILED_CODE), any());
    }

    // ─────────────── B-ISSUE-01: 원본 실재 가드(fail-closed) ───────────────

    @Test
    @DisplayName("원본_파일이_없으면_KPST_위탁이_거부되고_F_로_마킹된다")
    void submitRejectsWhenSourceMissing() {
        // given — 관제 NAS 경로가 DB 에는 있으나 실제 파일이 없는 영상(라이브 재현: clip-9101.mp4 부재).
        LsDataRaw raw = newRaw();
        setField(raw, "rawFilePathNm", tmp.resolve("gone.mp4").toString());

        // when / then — 위탁 자체를 거부(fail-closed). 외부 호출 없음.
        assertThatThrownBy(() -> service.submit(raw))
                .isInstanceOf(CustomException.class);
        verify(kpstClient, never()).createProject(any());
        // 'F' 는 별도 REQUIRES_NEW 로 커밋되어야 한다(submit 트랜잭션 롤백과 독립).
        verify(batchTransitionService).recordDeidentFailure(eq(9001L), any(), any());
        // 위탁 대기(WAITING) 원장을 남기지 않는다 — 폴링 대상이 되면 안 된다.
        verify(procLogRepository, never()).save(any(LsDeidentProcLog.class));
        verify(txService, never()).issueSubmitLedger(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("원본_부재_시_MARKING_READY_로_전이되지_않는다")
    void submitSourceMissingNeverTransitionsMarkingReady() {
        // given — 원본 부재. 거짓 'Y'/MARKING_READY 로 후속 단계를 오염시키면 안 된다(CWE-345).
        LsDataRaw raw = newRaw();
        setField(raw, "rawFilePathNm", tmp.resolve("gone.mp4").toString());
        String beforeStatus = raw.getDataSttsCd();

        // when
        assertThatThrownBy(() -> service.submit(raw)).isInstanceOf(CustomException.class);

        // then — DE_IDNTF_YN 'Y' 전이도, MARKING_READY 전이도 없다.
        assertThat(raw.getDeIdntfYn()).isNotEqualTo("Y");
        assertThat(raw.getDataSttsCd()).isEqualTo(beforeStatus);
        assertThat(raw.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        verify(txService, never()).completeDeidentification(any(), any());
    }

    @Test
    @DisplayName("원본_실재_가드는_프로퍼티로_끌_수_있고_끄면_WARN_이_남는다")
    void submitSourceGuardCanBeDisabledWithWarn() {
        // given — 공유 마운트가 보이지 않는 배포를 위한 이스케이프 해치(기본은 켬).
        setField(service, "verifySourceExists", false);
        LsDataRaw raw = newRaw();
        setField(raw, "rawFilePathNm", tmp.resolve("gone.mp4").toString());
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when — 가드가 꺼져 있으면 원본이 없어도 위탁이 진행된다.
        LsDeidentProcLog procLog = service.submit(raw);

        // then — 침묵하지 않는다: 미검증 위탁임을 WARN 으로 남긴다.
        assertThat(procLog.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        verify(outcomeRecorder).onAccepted(eq(9001L), eq(1L), any());
        verify(batchTransitionService, never()).recordDeidentFailure(any(), any(), any());
        assertThat(warnLogged("source existence guard disabled")).isTrue();
    }

    // ─────────────── 위탁 export 디렉터리 정리 (HIGH — stale 오회수 방지) ───────────────

    @Test
    @DisplayName("submit_재위탁시_export디렉터리의_stale산출물이_정리되어_남지않는다")
    void submitCleansStaleExportArtifacts() throws Exception {
        // given — 이전 회차 산출물(타임스탬프명 누적 등)이 export 디렉터리에 남아있는 REDEIDENT 재위탁 상황.
        LsDataRaw raw = newRaw();
        Path exportDir = baseDeid.resolve("videos").resolve("9001");
        java.nio.file.Files.createDirectories(exportDir);
        java.nio.file.Files.writeString(exportDir.resolve("001_202601010000_mask.mp4"), "OLD");
        java.nio.file.Files.writeString(exportDir.resolve("001-mask.mp4"), "OLD");
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        service.submit(raw, true);

        // then — 기존 산출물이 모두 정리되어 디렉터리가 비어있다(이번 회차 산출물만 이후 KPST 가 write).
        try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(exportDir)) {
            assertThat(s).isEmpty();
        }
    }

    @Test
    @DisplayName("submit정리는_바로아래_정규파일만_대상이고_하위디렉터리_심링크_링크타깃을_삭제하지않는다")
    void submitCleanupPreservesSubdirsAndSymlinks() throws Exception {
        // given — 안전가드: base 하위 & 바로 아래 정규 파일만 삭제(재귀·심링크 추종 없음).
        LsDataRaw raw = newRaw();
        Path exportDir = baseDeid.resolve("videos").resolve("9001");
        java.nio.file.Files.createDirectories(exportDir);
        Path regular = exportDir.resolve("stale.mp4");
        java.nio.file.Files.writeString(regular, "OLD");
        // 하위 디렉터리 + 내부 파일 — 재귀 삭제되면 안 됨.
        Path subDir = exportDir.resolve("keep-sub");
        java.nio.file.Files.createDirectories(subDir);
        Path subFile = subDir.resolve("inner.mp4");
        java.nio.file.Files.writeString(subFile, "INNER");
        // 심링크 — 따라가거나 타깃 삭제되면 안 됨.
        Path linkTarget = tmp.resolve("outside.mp4");
        java.nio.file.Files.writeString(linkTarget, "TARGET");
        Path link = exportDir.resolve("link.mp4");
        boolean symlinkSupported = true;
        try {
            java.nio.file.Files.createSymbolicLink(link, linkTarget);
        } catch (Exception e) {
            symlinkSupported = false;
        }
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        service.submit(raw, true);

        // then — 바로 아래 정규 파일만 삭제.
        assertThat(regular).doesNotExist();
        // 하위 디렉터리와 그 내용은 보존(재귀 삭제 없음).
        assertThat(subDir).exists();
        assertThat(subFile).exists();
        // 심링크 타깃(base 밖)은 삭제/추종되지 않음.
        assertThat(linkTarget).exists();
        if (symlinkSupported) {
            // 심링크 자체도 정리 대상 아님(건너뜀).
            assertThat(java.nio.file.Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue();
        }
    }

    @Test
    @DisplayName("submit정리는_최초위탁_빈디렉터리에서_예외없이_noop이고_위탁이_정상진행된다")
    void submitCleanupNoopOnFirstCommission() {
        // given — 최초 위탁: export 디렉터리 미존재. createDirectories 후 정리 대상 0건.
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when — 정리가 예외를 던지지 않고 no-op.
        LsDeidentProcLog procLog = service.submit(raw);

        // then — 위탁 정상 진행.
        assertThat(procLog.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        verify(outcomeRecorder).onAccepted(eq(9001L), eq(1L), any());
        assertThat(baseDeid.resolve("videos").resolve("9001")).exists();
    }

    // ─────────────── co-locate(Phase 5A) — export_path 신 위치 + 원본 불변 ───────────────

    /**
     * co-locate 전략 인스턴스 — 산출 base 가 {@code dirname(원본)/{rawSn}/deid/} 로 도출된다.
     * 허용 마운트 루트는 {@code tmp}(원본 {@code tmp/clip.mp4} 의 상위).
     */
    private KpstDeidentService coLocateService() {
        return newService(kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport.coLocate(
                tmp, tmp.resolve("raw"), baseDeid));
    }

    /** co-locate 비식별 영상 디렉터리 — {@code dirname(원본)/{rawSn}/deid/}. */
    private Path coLocateDeidDir() {
        return tmp.resolve("9001").resolve("deid");
    }

    @Test
    @DisplayName("co_locate_submit_export_path는_원본디렉터리_하위_rawSn_deid_이고_사전생성된다")
    void submitExportPathIsColocatedDeidDir() {
        // given
        LsDataRaw raw = newRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        coLocateService().submit(raw);

        // then — export_path = {dirname(원본)}/{rawSn}/deid/ (KPST 가 이 디렉터리에 직접 WRITE)
        ArgumentCaptor<KpstProjectRequest> captor = ArgumentCaptor.forClass(KpstProjectRequest.class);
        verify(kpstClient).createProject(captor.capture());
        assertThat(captor.getValue().exportPath()).isEqualTo(coLocateDeidDir() + "/");
        assertThat(coLocateDeidDir()).exists();
        // 파일명은 지정하지 않는다 — KPST 소관({원본stem}-mask{ext}).
        assertThat(captor.getValue().exportPath()).doesNotContain("mask");
    }

    @Test
    @DisplayName("S5_co_locate_export정리가_형제_원본영상_파일을_삭제하지_않는다")
    void colocateCleanupNeverTouchesOriginalVideo() throws Exception {
        // given — 원본 영상과 산출 디렉터리가 같은 부모를 공유한다(co-locate). 재위탁 정리가 돌아도
        //         정리 대상은 {rawSn}/deid/ 안의 정규 파일뿐이어야 한다.
        LsDataRaw raw = newRaw();
        Path original = Path.of(raw.getRawFilePathNm());
        String originalContent = java.nio.file.Files.readString(original);
        Path sibling = tmp.resolve("other-clip.mp4");
        java.nio.file.Files.writeString(sibling, "SIBLING");
        java.nio.file.Files.createDirectories(coLocateDeidDir());
        java.nio.file.Files.writeString(coLocateDeidDir().resolve("clip-mask.mp4"), "OLD");
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(reactor.core.publisher.Mono.just(new KpstProjectResponse("success", 101L)));

        // when — REDEIDENT 재위탁(정리 수행)
        coLocateService().submit(raw, true);

        // then — 원본/형제 원본은 존재도 내용도 불변, 산출 디렉터리만 비워진다.
        assertThat(original).exists();
        assertThat(java.nio.file.Files.readString(original)).isEqualTo(originalContent);
        assertThat(sibling).exists();
        assertThat(java.nio.file.Files.readString(sibling)).isEqualTo("SIBLING");
        try (java.util.stream.Stream<Path> s = java.nio.file.Files.list(coLocateDeidDir())) {
            assertThat(s).isEmpty();
        }
    }

    @Test
    @DisplayName("co_locate_회수는_stem_mask_산출물을_신위치에서_찾는다")
    void completionRecoversMaskFileFromColocateDir() throws Exception {
        // given — 위탁 시 기록된 원본 경로(procLog.orgnlFilePathNm)로 신 위치를 도출한다.
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                9001L, null, tmp.resolve("clip.mp4").toString(), "batch");
        setField(procLog, "procLogSn", 1L);
        procLog.markKpstSubmitted(101L, null);
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));   // fileName "clip.mp4"
        Path masked = TestVideoFixtures.writeTinyMp4(coLocateDeidDir().resolve("clip-mask.mp4"));

        // when
        coLocateService().pollOne(procLog);

        // then — 신 위치의 {stem}-mask{ext} 절대경로가 DE_IDNTF_FILE_PATH_NM 으로 적재된다.
        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(masked.toString()), any());
    }

    @Test
    @DisplayName("co_locate_전환후에도_구위치에_남은_배포전_산출물을_회수한다")
    void completionFallsBackToLegacyDirAfterSwitch() throws Exception {
        // given — 전환 전 위탁분: 결과가 구 위치({base}/videos/{rawSn}/)에 있다. 신 위치는 비어 있다.
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                9001L, null, tmp.resolve("clip.mp4").toString(), "batch");
        setField(procLog, "procLogSn", 1L);
        procLog.markKpstSubmitted(101L, null);
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        writeDeidResult("clip-mask.mp4");

        // when
        coLocateService().pollOne(procLog);

        // then — 구 위치 산출물로 정상 완료(전환 경계에서 유실 없음).
        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(deidPathFor("clip-mask.mp4").toString()), any());
    }

    @Test
    @DisplayName("toMaskName_이미mask접미사면_중복부여하지않고_없으면_부여한다")
    void toMaskNameNoDoubleSuffix() throws Exception {
        // LOW-1: 이미 {stem}-mask{ext} 형태면 재부여하지 않는다(중복 001-mask-mask.mp4 방지).
        Method m = KpstDeidentService.class.getDeclaredMethod("toMaskName", String.class);
        m.setAccessible(true);
        assertThat(m.invoke(service, "001.mp4")).isEqualTo("001-mask.mp4");
        assertThat(m.invoke(service, "001-mask.mp4")).isEqualTo("001-mask.mp4");
        // 확장자 없는 경우도 동일 가드.
        assertThat(m.invoke(service, "001")).isEqualTo("001-mask");
        assertThat(m.invoke(service, "001-mask")).isEqualTo("001-mask");
    }

    // ────────────────────────── 폴링 완료 (no-copy 회수) ──────────────────────────

    @Test
    @DisplayName("downloadResult가_GET_download_미호출_복사미수행_응답fileName으로_경로를_구성한다")
    void completionUsesResponseFileNameNoCopy() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L)); // fileName "clip.mp4"
        // KPST 가 export_path 에 직접 쓴 결과(복사 없음 — 우리는 경로만 구성).
        writeDeidResult("clip.mp4");
        Path expected = deidPathFor("clip.mp4");

        service.pollOne(procLog);

        // GET /download·복사 없음 — kpstClient 는 retrieveProgress 외 상호작용이 없어야 한다.
        verify(kpstClient).retrieveProgress(eq("authoring"), eq(101L));
        // [req: R14] 완료 시점의 결과 리포트 조회는 정상 상호작용이다 — 그 외(GET /download 등)는 없어야 한다.
        verify(kpstClient).retrieveReport(any(), eq(101L));
        verifyNoMoreInteractions(kpstClient);
        // 회수 경로 = {base}/videos/{rawSn}/{응답 fileName} 으로 Y 전이 원자 위임.
        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(expected.toString()), any());
    }

    @Test
    @DisplayName("완료시_악성fileName이면_예외전파없이_failPolling으로_terminal종결된다")
    void completionRejectsMaliciousFileName() {
        // 응답 fileName 은 외부값(CWE-22) — plain filename 만 허용. 위반 시 Y 전이 차단 +
        // 예외 전파 없이 terminal 종결(HIGH-1: 무한 재폴링·raw PENDING stuck 방지).
        List<String> evils = List.of("../escape.mp4", "a/b.mp4", "/etc/passwd", "..\\x.mp4", "..");
        for (String evil : evils) {
            LsDeidentProcLog procLog = submittedProcLog();
            when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                    .thenReturn(progressWithFileName(2, 202L, evil));
            // sanitize 실패가 pollOne 밖으로 전파되면 안 됨(잡이 swallow 하면 시도증가 없이 영구 재폴링).
            service.pollOne(procLog);
        }
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService, org.mockito.Mockito.times(evils.size())).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("완료시_불량fileName이면_failPolling으로_terminal종결되고_재폴링안된다")
    void pollCompletionBadFileNameTerminatesNonRedeident() {
        // HIGH-1: 완료(procState=2) 인데 fileName 이 불량(상위참조) → sanitize 가 던지는 예외를
        // finishDownloadAndComplete 실패와 동일하게 terminal 처리(failPolling)하여 무한 재폴링을 끊는다.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "../escape.mp4"));

        service.pollOne(procLog); // 예외 전파 없이 정상 반환

        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        // 시도증가/타임아웃 카운터를 거치지 않고 즉시 종결(완료 분기이므로).
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("완료시_null_fileName이면_terminal종결된다")
    void pollCompletionNullFileNameTerminates() {
        // null/빈 fileName 은 침해 없이도 가능한 정상 엣지 — KPST 완료 응답에 fileName 누락 시.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, null));

        service.pollOne(procLog);

        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("REDEIDENT_완료시_불량fileName이면_failRedeidentCompletion으로_락해제된다")
    void pollCompletionBadFileNameRedeidentReleasesLock() {
        // HIGH-1(REDEIDENT): 위탁 시 잡은 작업락이 sanitize 실패로 영구 미해제되면 안 됨 →
        // failRedeidentCompletion 으로 'F' + 락 해제 종결.
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, ""));

        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sanitizeFileName_NUL바이트면_CustomException_원문미노출")
    void sanitizeFileNameNulByteRejectedNoLeak() throws Exception {
        // HIGH-2: NUL바이트는 Paths.get 이 InvalidPathException(메시지에 입력 원문 포함)을 던진다 →
        // CustomException(INVALID_INPUT) 으로 정규화하고 fileName 원문을 메시지에 노출하지 않는다(CWE-209).
        String evil = "x" + "\u0000" + ".mp4";
        Method m = KpstDeidentService.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> {
            try {
                m.invoke(service, evil);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
        assertThat(thrown).isInstanceOf(CustomException.class);
        assertThat(((CustomException) thrown).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        // 원문(NUL 포함) 미노출.
        assertThat(thrown.getMessage()).doesNotContain(evil);
        assertThat(thrown.getMessage()).doesNotContain(" ");
    }

    @Test
    @DisplayName("isUsableDeidFile_심볼릭링크는_정규파일로_통과하지않는다")
    void isUsableDeidFileRejectsSymlink() throws Exception {
        // LOW-1: KPST 출력 디렉터리 내 심링크가 정규파일로 통과하지 않도록 NOFOLLOW_LINKS 적용(공급망 방어심도).
        Path target = TestVideoFixtures.writeTinyMp4(tmp.resolve("real.mp4"));
        Path link = tmp.resolve("link.mp4");
        try {
            java.nio.file.Files.createSymbolicLink(link, target);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "심링크 미지원 환경 — skip");
        }
        Method m = KpstDeidentService.class.getDeclaredMethod("isUsableDeidFile", String.class);
        m.setAccessible(true);
        boolean usable = (boolean) m.invoke(service, link.toString());
        assertThat(usable).isFalse();
    }

    // ─────────────── B-ISSUE-01: 산출물 무결성(크기 하한 + 컨테이너 시그니처) ───────────────

    @Test
    @DisplayName("18바이트_스텁_산출물은_유효한_비식별본으로_인정되지_않는다")
    void pollRejectsEighteenByteStub() throws Exception {
        // given — 라이브 재현: 원본이 없어 목이 남긴 placeholder("MOCK_DEIDENTIFIED\n" 18바이트)가
        //   procState=2 와 함께 '비식별 완료'로 승인되던 결함(CWE-345).
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path stub = writeDeidBytes("clip-mask.mp4", "MOCK_DEIDENTIFIED\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(java.nio.file.Files.size(stub)).isEqualTo(18L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw()));

        // when
        service.pollOne(procLog);

        // then — Y 전이 금지, 'F' 종결.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("ftyp_시그니처가_없는_파일은_거부된다")
    void pollRejectsFileWithoutContainerSignature() throws Exception {
        // given — 크기 하한은 넘지만 영상 컨테이너 시그니처가 없는 파일(텍스트 로그/HTML 오류페이지 등).
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        byte[] text = new byte[4096];
        java.util.Arrays.fill(text, (byte) 'A');
        writeDeidBytes("clip-mask.mp4", text);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw()));

        // when
        service.pollOne(procLog);

        // then
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("정상이지만_작은_영상은_거부되지_않는다")
    void pollAcceptsSmallButValidVideo() {
        // given — 실측 최소 영상(H.264 16x16 1프레임 mp4, 1,546바이트). 오탐 거부는 운영 사고다
        //   (terminal 'F' + 자동 재시도 없음 → 외부 수동 재비식별).
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path masked = writeDeidResult("clip-mask.mp4");

        // when
        service.pollOne(procLog);

        // then — 정상 완료(거부 없음).
        verify(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), eq(masked.toString()), any());
        verify(txService, never()).failPolling(any(), any());
    }

    @Test
    @DisplayName("쓰기중이던_산출물은_유예_재확인후_정상완료된다")
    void pollRechecksInFlightArtifactAfterGrace() throws Exception {
        // given — 완료 응답 시점에 산출물이 아직 쓰이는 중(부분 기록). 즉시 terminal 'F' 로 끊으면
        //   자동 재시도가 없어 정상 건이 사고가 된다 → 짧은 유예 후 1회 재확인한다.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path partial = writeDeidBytes("clip-mask.mp4", new byte[]{0, 0, 0, 32});   // 헤더 일부만 기록됨
        setField(service, "resultRecheckDelayMs", 700L);
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(120L);
                TestVideoFixtures.writeTinyMp4(partial);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.start();

        // when
        service.pollOne(procLog);
        writer.join();

        // then — 유예 재확인으로 정상 완료(오탐 거부 없음).
        verify(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), eq(partial.toString()), any());
        verify(txService, never()).failPolling(any(), any());
    }

    @Test
    @DisplayName("완료감지_산출물_미존재면_F처리되어_Y전이안된다")
    void pollMissingDeidFileMarksF() {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        // KPST 가 결과를 쓰지 않은 상황 — 회수 경로 파일 미존재.
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        // 불완전 산출물 — Y 전이/원자완료 호출 금지, 'F' 처리.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("완료감지_산출물_0바이트면_F처리되어_Y전이안된다")
    void pollZeroByteDeidFileMarksF() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path saved = deidPathFor("clip.mp4");
        java.nio.file.Files.createDirectories(saved.getParent());
        java.nio.file.Files.createFile(saved); // 0바이트
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("REDEIDENT_완료시_불완전산출물_미존재면_failRedeidentCompletion으로_락해제된다")
    void pollMissingDeidFileRedeidentReleasesLock() {
        // HIGH-1(REDEIDENT): 완료(procState=2) + 정상 fileName 이나 KPST 가 export_path 에 0바이트/미기록한
        // 불완전 산출물 → 비-REDEIDENT 처럼 failPolling 으로 끝내면 작업락이 영구 미해제(409 영구 차단).
        // REDEIDENT 는 failRedeidentCompletion(F + 락 해제 + terminal)으로 종결해야 한다.
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L)); // fileName "clip.mp4" 정상이나 파일 미존재
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("REDEIDENT_완료시_0바이트산출물이면_failRedeidentCompletion으로_락해제된다")
    void pollZeroByteDeidFileRedeidentReleasesLock() throws Exception {
        // HIGH-1(REDEIDENT): 0바이트 산출물도 불완전 산출물 — 락 해제 포함 종결.
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        Path saved = deidPathFor("clip.mp4");
        java.nio.file.Files.createDirectories(saved.getParent());
        java.nio.file.Files.createFile(saved); // 0바이트
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("비_REDEIDENT_불완전산출물은_기존대로_failPolling이다")
    void pollMissingDeidFileNonRedeidentStillFailPolling() {
        // 회귀(HIGH-1): REQ_KIND null(배치 경로)은 락이 없어 기존 failPolling 유지 — REDEIDENT 분기 미적용.
        LsDeidentProcLog procLog = submittedProcLog(); // REQ_KIND null
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        LsDataRaw raw = newRaw();
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        service.pollOne(procLog);

        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).failRedeidentCompletion(any(), any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("다중_데이터셋_부분완료면_전체완료로_오판하지_않고_진행중으로_판정한다")
    void pollMultiDatasetPartialIsInProgress() {
        LsDeidentProcLog procLog = submittedProcLog();
        // ds[0]=완료(2), ds[1]=진행중(1) → 부분완료. 전체 AND 검증 시 진행중 처리.
        KpstProgressResponse.DsStatus done = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus running = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", 1, 40.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 70.0, 2, List.of(done, running));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);

        service.pollOne(procLog);

        // 부분완료를 완료로 오판하면 안 됨 — 완료 금지, 진행중 위임.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), any());
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
    }

    @Test
    @DisplayName("미시작_null_procState_데이터셋이_있으면_완료로_오판하지_않고_진행중으로_판정한다")
    void pollNullProcStateIsInProgress() {
        LsDeidentProcLog procLog = submittedProcLog();
        // ds[0]=완료(2), ds[1]=미시작(null) → null 가드가 없으면 NPE 또는 오판. 진행중이어야 한다.
        KpstProgressResponse.DsStatus done = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus notStarted = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", null, 0.0, null, "None", "None");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 50.0, 2, List.of(done, notStarted));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);

        service.pollOne(procLog);

        // null(미시작)을 완료로 오판/NPE 없이 진행중 위임.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), any());
    }

    @Test
    @DisplayName("모든_데이터셋이_procState2면_완료감지하여_첫_데이터셋_fileName으로_회수한다")
    void pollAllStateTwoCompletes() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog();
        KpstProgressResponse.DsStatus a = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus b = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 100.0, 2, List.of(a, b));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);
        writeDeidResult("a.mp4"); // 첫 데이터셋 fileName
        Path expected = deidPathFor("a.mp4");

        service.pollOne(procLog);

        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(expected.toString()), any());
    }

    // ────────────────────────── 폴링 터미널-실패 fast-fail ──────────────────────────

    @Test
    @DisplayName("procState99_에러sentinel이면_타임아웃대기없이_즉시_failPolling으로_F처리한다")
    void pollProcState99FastFailsImmediately() {
        // given — 실측 확인된 에러 sentinel 99 (progressRate 0, totalFrame 정상)
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(99, 202L));

        // when
        service.pollOne(procLog);

        // then — 타임아웃 카운터를 기다리지 않고 즉시 종결. 완료 금지.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(txService, never()).recordPollingProgress(any(), any());
    }

    @Test
    @DisplayName("K2_procState3_중지상태면_즉시_failPolling으로_F처리한다")
    void pollProcState3StoppedFastFailsImmediately() {
        // given — K2: procState 도메인 3=중지(터미널 실패). 재폴링해도 진행되지 않으므로 fast-fail.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(3, 202L));

        // when
        service.pollOne(procLog);

        // then — 타임아웃 대기 없이 즉시 종결. 완료/시도증가 금지.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(txService, never()).recordPollingProgress(any(), any());
    }

    @Test
    @DisplayName("K2_procState4_삭제중상태면_즉시_failPolling으로_F처리한다")
    void pollProcState4DeletingFastFailsImmediately() {
        // given — K2: procState 도메인 4=삭제중(터미널 실패, 산출물 미기대).
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(4, 202L));

        // when
        service.pollOne(procLog);

        // then — 타임아웃 대기 없이 즉시 종결. 완료/시도증가 금지.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(txService, never()).recordPollingProgress(any(), any());
    }

    @Test
    @DisplayName("K2_procState5는_procState도메인_밖이므로_실패아님_진행중으로_판정한다")
    void pollProcState5NotTerminalIsInProgress() {
        // given — K2: procState 규격 코드는 0/1/2/3/4/99 뿐이다. 5는 prjState 도메인 값으로 procState 에
        //  존재하지 않으므로(과거 오혼용 값) 실패로 보지 않고 미지 코드=진행중(타임아웃 바운드)으로 유지한다.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(5, 202L));

        // when
        service.pollOne(procLog);

        // then — 실패(F) 아님, 완료 아님 → 진행중 위임(시도증가 + 타임아웃 검사).
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), eq(202L));
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
    }

    @Test
    @DisplayName("다중데이터셋_2와99가_섞이면_완료로_오판하지않고_즉시_F처리한다")
    void pollMultiDatasetWithFailureFastFails() {
        // given — ds[0]=완료(2), ds[1]=실패(99). 하나라도 실패면 실패가 우선.
        LsDeidentProcLog procLog = submittedProcLog();
        KpstProgressResponse.DsStatus done = new KpstProgressResponse.DsStatus(
                202L, "a.mp4", 2, 100.0, 5400, "t0", "t1");
        KpstProgressResponse.DsStatus failed = new KpstProgressResponse.DsStatus(
                203L, "b.mp4", 99, 0.0, 5400, "t0", "t1");
        KpstProgressResponse.PrjStatus prj = new KpstProgressResponse.PrjStatus(
                101L, "raw9001", 50.0, 2, List.of(done, failed));
        KpstProgressResponse progress = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(1, List.of(prj)));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(progress);

        // when
        service.pollOne(procLog);

        // then — 완료 오판 금지, 즉시 F.
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("REDEIDENT경로의_터미널실패는_락해제포함_failRedeidentCompletion으로_종결한다")
    void pollRedeidentTerminalFailReleasesLock() {
        // given — REDEIDENT procLog 가 procState 99 → 락 영구잠금 방지 위해 failRedeidentCompletion 종결
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(99, 202L));

        // when
        service.pollOne(procLog);

        // then — 락 해제 포함 종결(failRedeidentCompletion), failPolling 미사용.
        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
        verify(txService, never()).failPolling(any(), any());
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    // ────────────────────────── 폴링 진행중 ──────────────────────────

    @Test
    @DisplayName("폴링이_진행중이면_recordPollingProgress로_시도증가_위임_완료_미수행")
    void pollInProgressDelegatesPolling() {
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(1, 202L)); // procState=1 (진행중)

        service.pollOne(procLog);

        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), eq(202L));
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
        verify(txService, never()).completeDeidentification(any(), any());
    }

    @Test
    @DisplayName("데이터셋_미생성이면_시도증가후_타임아웃검사만_위임한다")
    void pollNoDatasetDelegatesPollingAndTimeout() {
        LsDeidentProcLog procLog = submittedProcLog();
        KpstProgressResponse empty = new KpstProgressResponse("success",
                new KpstProgressResponse.Data(0, List.of()));
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L))).thenReturn(empty);

        service.pollOne(procLog);

        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).recordPollingProgress(eq(1L), isNull());
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
    }

    @Test
    @DisplayName("PhaseC2_prjId_미상은_ACK대기로_보고_유예안이면_아무것도_하지않는다")
    void pollMissingPrjIdWithinAckGraceIsNoop() {
        // given — 논블로킹 제출로 "원장은 커밋됐지만 ACK 는 아직" 인 창이 정상적으로 존재한다.
        //   (구 계약: prjId 미상 = 데이터 정합 깨짐 → markTimeoutIfExpired. 이제는 시도/경과 예산을
        //    헛되이 소모하지 않도록 유예 안에서는 판정 자체를 하지 않는다.)
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(procLog, "procLogSn", 1L);
        procLog.markKpstSubmitPending();

        service.pollOne(procLog);

        verify(kpstClient, never()).retrieveProgress(any(), any());
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(txService, never()).failSubmit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("PhaseC2_ACK가_유예를_넘겨도_안오면_폴러가_ACK_MISSING으로_회수한다")
    void pollReclaimsWhenAckGraceExpired() {
        // given — 유예 만료(별도 스위퍼 없이 폴러가 회수기 역할을 그대로 수행한다).
        setField(service, "submitAckGraceSec", 0L);
        LsDeidentProcLog procLog = LsDeidentProcLog.request(9001L, null, "/raw/clip.mp4", "batch");
        setField(procLog, "procLogSn", 1L);
        procLog.markKpstSubmitPending();

        service.pollOne(procLog);

        // then — 확정 실패(SUBMIT_FAILED)와 구분되는 코드로 terminal 종결. 진행조회는 호출할 수 없다.
        verify(kpstClient, never()).retrieveProgress(any(), any());
        verify(txService).failSubmit(eq(1L), eq(9001L),
                eq(KpstDeidentService.ACK_MISSING_CODE), any());
        verify(txService, never()).markTimeoutIfExpired(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    // ────────────────────────── K1: retrieveProgress 지속 예외 가드 ──────────────────────────

    @Test
    @DisplayName("K1_retrieveProgress가_지속예외를_던져도_예외전파없이_markTimeoutIfExpired를_평가하고_경과전이면_이번틱만_skip한다")
    void pollRetrieveProgressExceptionEvaluatesTimeoutAndSkips() {
        // given — KPST 5xx/커넥션거부/서킷오픈으로 retrieveProgress 가 지속 예외를 던진다.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "kpst 5xx"));
        // 아직 위탁 후 경과시간 전 — 타임아웃 아님(false) → 이번 틱만 skip.
        when(txService.markTimeoutIfExpired(eq(1L), eq(3), eq(60L))).thenReturn(false);

        // when — 예외가 pollOne 을 탈출하면 안 된다(잡이 swallow 하면 시도증가 없이 무한 재폴링 stuck).
        service.pollOne(procLog);

        // then — 예외 경로에서도 경과시간 기준 타임아웃을 반드시 평가. 완료/시도증가는 하지 않는다.
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService, never()).recordPollingProgress(any(), any());
        verify(txService, never()).failPolling(any(), any());
    }

    @Test
    @DisplayName("K1_retrieveProgress_지속예외가_경과시간초과에_도달하면_markTimeoutIfExpired가_F마킹하여_무한폴링을_끊는다")
    void pollRetrieveProgressExceptionTimesOutMarksF() {
        // given — 지속 예외가 poll-timeout-minutes 경과까지 이어진 상황. markTimeoutIfExpired 가 'F' 마킹(true).
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "kpst persistent 5xx"));
        when(txService.markTimeoutIfExpired(eq(1L), eq(3), eq(60L))).thenReturn(true);

        // when — 예외 전파 없이 정상 반환.
        service.pollOne(procLog);

        // then — 경과시간 초과로 타임아웃 처리('F' 마킹). WAITING/POLLING stuck 이 해소된다.
        verify(txService).markTimeoutIfExpired(eq(1L), eq(3), eq(60L));
        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
    }

    // ────────────────────────── 완료 위임 ──────────────────────────

    @Test
    @DisplayName("completeDeidentification은_txService에_위임한다")
    void completeDelegatesToTx() {
        service.completeDeidentification(9001L, "/deid/9001/deidentified.mp4");
        verify(txService).completeDeidentification(9001L, "/deid/9001/deidentified.mp4");
    }

    @Test
    @DisplayName("M1_completeDeidentification_검증실패시_비REDEIDENT면_markRawDeidentFailed로_F를_별도커밋한다")
    void completeFailureCommitsFForBatch() {
        // 콜백/배치 경로(REQ_KIND null) — 파일 무효로 tx 가 예외. F-마킹이 별도 커밋되어야 한다(M-1).
        LsDeidentProcLog batchLog = submittedProcLog(); // REQ_KIND null
        when(procLogRepository.findAllByDataRawSnOrderByReqDtDesc(9001L)).thenReturn(List.of(batchLog));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "invalid"))
                .when(txService).completeDeidentification(eq(9001L), any());

        assertThatThrownBy(() -> service.completeDeidentification(9001L, "/deid/x.mp4"))
                .isInstanceOf(CustomException.class);

        verify(txService).markRawDeidentFailed(9001L);
    }

    @Test
    @DisplayName("M1_completeDeidentification_검증실패라도_REDEIDENT면_markRawDeidentFailed_미호출_폴링경로종결위임")
    void completeFailureSkipsFForRedeident() {
        LsDeidentProcLog redeidentLog = submittedProcLog();
        redeidentLog.markRedeident();
        when(procLogRepository.findAllByDataRawSnOrderByReqDtDesc(9001L)).thenReturn(List.of(redeidentLog));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "invalid"))
                .when(txService).completeDeidentification(eq(9001L), any());

        assertThatThrownBy(() -> service.completeDeidentification(9001L, "/deid/x.mp4"))
                .isInstanceOf(CustomException.class);

        // REDEIDENT 는 폴링 경로(failRedeidentCompletion)가 종결 — 여기선 F-마킹 보정하지 않는다.
        verify(txService, never()).markRawDeidentFailed(any());
    }

    @Test
    @DisplayName("REDEIDENT_attach실패_무한재폴링안되고_terminal도달_failRedeidentCompletion위임_재throw안함")
    void pollRedeidentAttachFailureTerminatesNotRepoll() throws Exception {
        // 완료 감지 후 finishDownloadAndComplete(REDEIDENT) 가 attach 실패로 예외 → 메인 롤백.
        // 폴링은 failRedeidentCompletion(별도 커밋)으로 terminal 종결하고 예외를 재throw 하지 않는다
        // (재throw 시 procLog 가 WAITING/POLLING 으로 남아 무한 재폴링).
        LsDeidentProcLog procLog = submittedProcLog();
        procLog.markRedeident();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        writeDeidResult("clip.mp4");
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "해상도 불일치"))
                .when(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), any(), any());

        // 재throw 하면 안 됨 — terminal 종결 후 정상 반환.
        service.pollOne(procLog);

        verify(txService).failRedeidentCompletion(eq(1L), eq(9001L), any());
    }

    @Test
    @DisplayName("비REDEIDENT_완료후처리실패는_여전히_예외전파_failRedeidentCompletion미호출_회귀")
    void pollBatchCompletionFailureStillThrows() throws Exception {
        LsDeidentProcLog procLog = submittedProcLog(); // REQ_KIND null
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWith(2, 202L));
        writeDeidResult("clip.mp4");
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.INVALID_INPUT, "boom"))
                .when(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), any(), any());

        // 기존 BATCH 경로는 현행 유지 — 예외 전파(잡이 건별 격리), REDEIDENT 종결 핸들러 미호출.
        assertThatThrownBy(() -> service.pollOne(procLog)).isInstanceOf(CustomException.class);
        verify(txService, never()).failRedeidentCompletion(any(), any(), any());
    }

    // ────────────── 경로형 fileName + -mask 산출 회수 (2026-07-21 실서버 계약) ──────────────

    @Test
    @DisplayName("경로형_fileName이면_basename추출후_mask접미사경로로_회수한다")
    void pollPathFormFileNameRecoversMaskSuffix() throws Exception {
        // 실측 계약: 응답 fileName 은 원본 입력 절대경로, 산출물은 export_path 에 {stem}-mask{ext}.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "/nas-storage-prod2/klid_at_test/raw/001.mp4"));
        // KPST 가 export_path 에 직접 쓴 실제 산출물 = 001-mask.mp4 (>0바이트).
        writeDeidResult("001-mask.mp4");
        Path expected = deidPathFor("001-mask.mp4");

        service.pollOne(procLog);

        // 예외 없이 회수 경로 = {base}/videos/{rawSn}/001-mask.mp4 로 Y 전이 위임.
        verify(kpstClient).retrieveProgress(eq("authoring"), eq(101L));
        // [req: R14] 완료 시점의 결과 리포트 조회는 정상 상호작용이다 — 그 외(GET /download 등)는 없어야 한다.
        verify(kpstClient).retrieveReport(any(), eq(101L));
        verifyNoMoreInteractions(kpstClient);
        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(expected.toString()), any());
    }

    @Test
    @DisplayName("mask경로가_없으면_디렉터리_단일산출물을_폴백회수한다")
    void pollFallbackRecoversSingleUsableFileWhenNoMask() throws Exception {
        // 접미사/확장자 규칙 변화 대비 폴백: {stem}-mask{ext} 부재 시 export 디렉터리의 단일 산출 영상 회수.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "/nas-storage-prod2/klid_at_test/raw/001.mp4"));
        // 001-mask.mp4 는 없고 다른 이름의 단일 산출물만 존재(규칙 변화 시나리오).
        writeDeidResult("001_masked.mkv");
        Path expected = deidPathFor("001_masked.mkv");

        service.pollOne(procLog);

        verify(txService).finishDownloadAndComplete(
                eq(9001L), eq(1L), eq(202L), eq(expected.toString()), any());
    }

    @Test
    @DisplayName("폴백스캔_산출물이_2개이상이면_모호하여_예외전파없이_failPolling으로_종결한다")
    void pollFallbackAmbiguousMultipleFilesFailsSafely() throws Exception {
        // no-copy 모델은 rawSn당 산출물 1개가 정상 — 2개 이상이면 어느 것이 결과인지 모호 → 안전 실패.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "/nas-storage-prod2/klid_at_test/raw/zzz.mp4"));
        // zzz-mask.mp4 는 없고, 서로 다른 두 산출물이 있어 모호.
        writeDeidResult("a.mp4");
        writeDeidResult("b.mp4");

        service.pollOne(procLog); // 예외 전파 없이 정상 반환

        verify(txService, never()).finishDownloadAndComplete(any(), any(), any(), any(), any());
        verify(txService).failPolling(eq(1L), eq(9001L));
    }

    @Test
    @DisplayName("경로형_fileName에_상위참조가_섞여도_basename만_취해_base하위로만_회수한다")
    void pollPathFormTraversalTakesBasenameOnly() throws Exception {
        // CWE-22: 응답 fileName 에 ../ 순회 시도가 있어도 basename(001.mp4)만 취해 base 하위로 resolve.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "/nas/raw/../../../../etc/001.mp4"));
        writeDeidResult("001-mask.mp4");
        Path expected = deidPathFor("001-mask.mp4");

        service.pollOne(procLog);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), pathCaptor.capture(), any());
        // 회수 경로는 반드시 base 하위(순회 이탈 없음).
        assertThat(pathCaptor.getValue()).isEqualTo(expected.toString());
        assertThat(pathCaptor.getValue()).startsWith(baseDeid.toAbsolutePath().normalize().toString());
    }

    // ────────────── B-ISSUE-84: 목업 계약 정합(1차 회수 경로가 실제로 쓰이는지) ──────────────

    @Test
    @DisplayName("목업_계약_하에서_toMaskName_1차_회수경로가_실제로_사용된다")
    void pollUsesPrimaryMaskPathUnderMockContract() throws Exception {
        // given — 목업이 실서버 계약대로 응답할 때: fileName = 원본 입력 절대경로,
        //   산출물 = {stem}-mask{ext}. export 디렉터리에 <다른 파일이 하나 더> 있어도 1차 경로로 회수해야 한다.
        //   (폴백 스캔을 타면 파일이 2개라 '모호' 실패로 종결되므로, 성공 자체가 1차 경로 사용의 증거다.)
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "/nas-storage/klid/raw/seed/clip-9101.mp4"));
        Path masked = writeDeidResult("clip-9101-mask.mp4");
        writeDeidResult("clip-9101-mask.mp4.part");   // 잔여물 — 폴백 스캔이면 모호 실패
        Path expected = deidPathFor("clip-9101-mask.mp4");

        // when
        service.pollOne(procLog);

        // then — 1차 경로({stem}-mask{ext})로 회수, 폴백 미사용(모호 실패 없음).
        assertThat(masked).isEqualTo(expected);
        verify(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), eq(expected.toString()), any());
        verify(txService, never()).failPolling(any(), any());
        assertThat(warnLogged("primary mask path miss")).isFalse();
    }

    @Test
    @DisplayName("1차_경로_miss_시_WARN_이_남는다")
    void pollWarnsWhenPrimaryMaskPathMisses() throws Exception {
        // given — 1차 경로 부재 → 폴백 스캔으로 회수(안전망 유지). 다만 계약 드리프트를 조용히 넘기지 않도록
        //   운영에서 관측 가능한 WARN 을 남긴다.
        LsDeidentProcLog procLog = submittedProcLog();
        when(kpstClient.retrieveProgress(eq("authoring"), eq(101L)))
                .thenReturn(progressWithFileName(2, 202L, "/nas-storage/klid/raw/seed/clip-9101.mp4"));
        Path fallback = writeDeidResult("clip-9101_202607250139_mask.mp4");   // 구 목업 규칙 산출물

        // when
        service.pollOne(procLog);

        // then — 폴백으로 완료되지만 WARN 이 남는다.
        verify(txService).finishDownloadAndComplete(eq(9001L), eq(1L), eq(202L), eq(fallback.toString()), any());
        assertThat(warnLogged("primary mask path miss")).isTrue();
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void invoke(Object target, String method) {
        try {
            Method m = target.getClass().getDeclaredMethod(method);
            m.setAccessible(true);
            m.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
