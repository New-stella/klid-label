package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase C-2 — KPST 위탁 <b>논블로킹 제출</b>의 커밋 경계 통합 검증 (Testcontainers PostgreSQL).
 *
 * <h3>왜 IT 인가 (ambient tx 없는 검증이 필수)</h3>
 * <p>이 레포에는 {@code @Transactional} 자기호출로 트랜잭션 경계가 통째로 사라졌는데도 테스트가
 * <b>ambient tx 때문에 GREEN</b> 이던 실사고 이력이 있다(배치 전면 불통). 비동기 제출의 실패 종결은
 * 파이프라인 스레드 밖(전용 풀)에서 일어나 ambient tx 가 전혀 없으므로, 경계가 실제로 열리는지는
 * <b>테스트 메서드에 {@code @Transactional} 을 붙이지 않은</b> 이 IT 로만 확인할 수 있다.
 * 각 단언은 DB 에서 다시 읽어(=커밋 관측) 수행한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "kpst.deid.enabled=true",
        "kpst.deid.base-url=https://localhost:9201",
        "kpst.deid.ca-cert-path=src/test/resources/kpst/test-ca.crt",
        // 폴링 잡 트리거가 검증 중 자동 발화하지 않도록 충분히 늦춘다(테스트는 pollOne 직접 호출).
        "kpst.deid.poll-interval-sec=3600"
})
class KpstSubmitAsyncCommitIT {

    private static final Path DEID_BASE;
    private static final Path RAW_MOUNT_ROOT;

    static {
        try {
            DEID_BASE = Files.createTempDirectory("kpst-submit-deid-base");
            RAW_MOUNT_ROOT = Files.createTempDirectory("kpst-submit-raw-mount");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storagePaths(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.deidentified-path", DEID_BASE::toString);
        registry.add("authoring.storage.raw-mount-roots", RAW_MOUNT_ROOT::toString);
    }

    @MockBean
    private KpstDeidentifyClient kpstClient;

    @Autowired
    private KpstDeidentService kpstDeidentService;
    @Autowired
    private KpstDeidentTxService txService;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDeidentProcLogRepository procLogRepository;
    @Autowired
    private LsAuthWorkLockRepository workLockRepository;

    /** M3 — 호출자 트랜잭션을 테스트에서 직접 열고 롤백시키기 위한 트랜잭션 매니저. */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("controlTransactionManager")
    private org.springframework.transaction.PlatformTransactionManager controlTxManager;

    private final List<Long> createdRawSns = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long rawSn : createdRawSns) {
            workLockRepository.deleteAll(
                    workLockRepository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                            LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED));
            procLogRepository.deleteAll(procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn));
            videoRepository.findById(rawSn).ifPresent(videoRepository::delete);
        }
        createdRawSns.clear();
    }

    private LsDataRaw persistRaw() {
        Path rawFile = RAW_MOUNT_ROOT.resolve("clip-" + System.nanoTime() + ".mp4");
        try {
            Files.writeString(rawFile, "raw-video-bytes");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60);
        LsDataRaw saved = videoRepository.save(raw);
        createdRawSns.add(saved.getRawSn());
        return saved;
    }

    // ────────────────────────── 재위탁 프로젝트 이름 (INT-004 · AC-1133) ──────────────────────────

    @Test
    @DisplayName("실DB_같은_영상을_다시_위탁하면_KPST에_나가는_프로젝트_이름이_회차마다_다르다")
    void resubmitUsesDistinctProjectNamesOnRealLedger() {
        // given — 첫 위탁이 폴링에서 실패해 종결된 뒤 재시작으로 다시 위탁되는 상황(246 raw44 실측)
        LsDataRaw raw = persistRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.just(new KpstProjectResponse("success", 101L)));

        // when
        LsDeidentProcLog first = kpstDeidentService.submit(raw);
        LsDeidentProcLog second = kpstDeidentService.submit(raw);
        LsDeidentProcLog third = kpstDeidentService.submit(raw, true);

        // then — 첫 이름은 종전 그대로, 이후는 그 회차 원장 번호 접미(실제 선커밋 번호)
        org.mockito.ArgumentCaptor<KpstProjectRequest> captor =
                org.mockito.ArgumentCaptor.forClass(KpstProjectRequest.class);
        org.mockito.Mockito.verify(kpstClient, org.mockito.Mockito.times(3)).createProject(captor.capture());
        List<String> names = captor.getAllValues().stream().map(KpstProjectRequest::projectName).toList();
        assertThat(names).containsExactly(
                "raw" + raw.getRawSn(),
                "raw" + raw.getRawSn() + "r" + second.getProcLogSn(),
                "raw" + raw.getRawSn() + "r" + third.getProcLogSn());
        assertThat(names).doesNotHaveDuplicates();
        assertThat(first.getProcLogSn()).isLessThan(second.getProcLogSn());
    }

    // ────────────────────────── 선커밋 원장 ──────────────────────────

    @Test
    @DisplayName("제출전_원장이_WAITING_prjId_null_로_선커밋되고_ACK_수신시_prjId가_기록된다")
    void ledgerPrecommittedThenAckRecorded() {
        // given
        LsDataRaw raw = persistRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.just(new KpstProjectResponse("success", 101L)));

        // when — ACK 왕복을 기다리지 않고 즉시 반환한다.
        LsDeidentProcLog submitted = kpstDeidentService.submit(raw);

        // then — 반환 시점 원장은 이미 커밋돼 있고(DB 재조회로 관측), prjId 는 아직 없다.
        LsDeidentProcLog persisted = procLogRepository.findById(submitted.getProcLogSn()).orElseThrow();
        assertThat(persisted.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        assertThat(submitted.getKpstPrjId()).isNull();

        // then — ACK 는 전용 풀에서 비동기로 기록된다(prjId 확정 → 폴링 가능).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(procLogRepository.findById(submitted.getProcLogSn()).orElseThrow()
                        .getKpstPrjId()).isEqualTo(101L));
        // 비식별 완료 전이는 없다(완료는 폴링 잡 단일 지점).
        assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isNotEqualTo("Y");
    }

    // ────────────────────────── 실패 커밋 경계 (난제 2·4) ──────────────────────────

    @Test
    @DisplayName("비동기_제출실패는_예외없이_F와_원장FAILED로_커밋된다_ambient_tx_없음")
    void asyncSubmitFailureCommitsFailureWithoutAmbientTx() {
        // given — 외부 제출이 에러 신호로 종료(4xx/타임아웃/서킷오픈 등).
        LsDataRaw raw = persistRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.error(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "boom")));

        // when — 제출 이후 실패는 호출 스레드로 전파되지 않는다(예외 없음).
        LsDeidentProcLog submitted = kpstDeidentService.submit(raw);

        // then — 완료 핸들러가 별도 REQUIRES_NEW 로 종단 상태를 <b>커밋</b>한다.
        //   (구 동기 코드는 같은 트랜잭션에서 'F' 를 찍고 예외를 던져 마킹이 함께 롤백됐다.)
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            LsDeidentProcLog reloaded = procLogRepository.findById(submitted.getProcLogSn()).orElseThrow();
            assertThat(reloaded.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
            assertThat(reloaded.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
            assertThat(reloaded.getErrorCd()).isEqualTo(KpstDeidentService.SUBMIT_FAILED_CODE);
            assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("F");
        });
        // 마킹 조기 진입 금지 — 어느 경로에서도 MARKING_READY 로 전이하지 않는다.
        assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDataSttsCd())
                .isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("재비식별_제출실패시_작업락이_해제되어_재요청이_가능해진다")
    void redeidentSubmitFailureReleasesWorkLock() {
        // given — 재비식별은 요청 시 작업락을 잡는다. 위탁이 실패로 끝나면 해제되어야 재요청이 가능하다.
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        workLockRepository.save(LsAuthWorkLock.lockRawForRedeident(rawSn, "reviewer-1"));
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.error(new CustomException(ErrorCode.EXTERNAL_API_ERROR, "boom")));

        // when
        kpstDeidentService.submit(raw, true);

        // then — 락 해제 커밋 관측(해제되지 않으면 재요청이 409 로 영구 차단된다).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(workLockRepository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                        LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED)).isFalse());
    }

    // ────────────────────────── 상태 강등 금지 (회귀 위험 1) ──────────────────────────

    @Test
    @DisplayName("ACK_수신후_도착한_지각실패는_영상상태를_강등하지_않는다")
    void lateFailureAfterAckDoesNotDemote() {
        // given — 이미 ACK 를 받아 폴링 대기 중인 원장(prjId 확정).
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        LsDeidentProcLog ledger = txService.issueSubmitLedger(rawSn, raw.getRawFilePathNm(), false);
        assertThat(txService.recordSubmitAck(ledger.getProcLogSn(), rawSn, 101L)).isTrue();

        // when — 지각 실패 신호가 도착한다.
        boolean applied = txService.failSubmit(
                ledger.getProcLogSn(), rawSn, KpstDeidentService.SUBMIT_FAILED_CODE, "late");

        // then — 조건부 UPDATE 가 0행이라 종결되지 않고, 영상도 'F' 로 강등되지 않는다.
        assertThat(applied).isFalse();
        LsDeidentProcLog reloaded = procLogRepository.findById(ledger.getProcLogSn()).orElseThrow();
        assertThat(reloaded.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        assertThat(reloaded.getProcSttsCd()).isNotEqualTo(LsDeidentProcLog.FAILED);
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("종결된_원장에_도착한_지각ACK는_prjId를_기록하지_않는다_부활금지")
    void lateAckDoesNotResurrectTerminalLedger() {
        // given — ACK 미수신으로 이미 회수(terminal)된 원장.
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        LsDeidentProcLog ledger = txService.issueSubmitLedger(rawSn, raw.getRawFilePathNm(), false);
        assertThat(txService.failSubmit(
                ledger.getProcLogSn(), rawSn, KpstDeidentService.ACK_MISSING_CODE, "reclaimed")).isTrue();

        // when — 뒤늦게 ACK 가 도착한다.
        boolean recorded = txService.recordSubmitAck(ledger.getProcLogSn(), rawSn, 101L);

        // then — 되살아나지 않는다(폴링 대상 부활 금지).
        assertThat(recorded).isFalse();
        LsDeidentProcLog reloaded = procLogRepository.findById(ledger.getProcLogSn()).orElseThrow();
        assertThat(reloaded.getKpstPrjId()).isNull();
        assertThat(reloaded.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
    }

    @Test
    @DisplayName("동시_실패신호_2건_중_1건만_종결에_성공한다_2노드_원자클레임")
    void concurrentFailureSignalsSettleOnce() {
        // given — 2노드 Active-Active 에서 같은 원장에 신호가 겹치는 상황.
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        LsDeidentProcLog ledger = txService.issueSubmitLedger(rawSn, raw.getRawFilePathNm(), false);

        // when — 동일 인자로 두 번 종결을 시도한다(재시도·중복 신호와 동형).
        boolean first = txService.failSubmit(
                ledger.getProcLogSn(), rawSn, KpstDeidentService.SUBMIT_FAILED_CODE, "a");
        boolean second = txService.failSubmit(
                ledger.getProcLogSn(), rawSn, KpstDeidentService.SUBMIT_FAILED_CODE, "b");

        // then — 조건부 UPDATE 라 정확히 1건만 성립한다(멱등).
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    // ────────────────────────── ACK 대기 회수 (난제 1·3) ──────────────────────────

    @Test
    @DisplayName("폴러는_ACK대기_유예안에서는_진행조회를_호출하지_않고_시도카운터도_소모하지_않는다")
    void pollerSkipsWithinAckGrace() {
        // given — 방금 위탁된(ACK 대기) 원장. prjId 가 없어 진행조회 자체가 불가능하다.
        LsDataRaw raw = persistRaw();
        LsDeidentProcLog ledger = txService.issueSubmitLedger(raw.getRawSn(), raw.getRawFilePathNm(), false);

        // when — 폴링 잡이 이 건을 집는다.
        kpstDeidentService.pollOne(procLogRepository.findById(ledger.getProcLogSn()).orElseThrow());

        // then — 외부 호출 0건 + 상태/카운터 불변(유예 예산을 헛되이 태우지 않는다).
        org.mockito.Mockito.verify(kpstClient, org.mockito.Mockito.never()).retrieveProgress(any(), any());
        LsDeidentProcLog reloaded = procLogRepository.findById(ledger.getProcLogSn()).orElseThrow();
        assertThat(reloaded.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_WAITING);
        assertThat(reloaded.getPollAttemptCnt()).isNull();
        assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isNotEqualTo("F");
    }

    @Test
    @DisplayName("ACK가_끝내_오지_않으면_폴러가_유예만료후_ACK_MISSING으로_회수한다")
    void pollerReclaimsWhenAckNeverArrives() throws Exception {
        // given — 유예를 0 으로 낮춰 "ACK 가 영영 오지 않는" 상태를 즉시 재현한다.
        LsDataRaw raw = persistRaw();
        LsDeidentProcLog ledger = txService.issueSubmitLedger(raw.getRawSn(), raw.getRawFilePathNm(), false);
        setField(kpstDeidentService, "submitAckGraceSec", 0L);
        try {
            // when — 별도 스위퍼가 아니라 <b>폴링 잡</b>이 회수한다(이중 인프라 금지).
            kpstDeidentService.pollOne(procLogRepository.findById(ledger.getProcLogSn()).orElseThrow());

            // then — terminal 종결 + 'F' 커밋. 확정 실패와 구분되는 코드가 남는다.
            LsDeidentProcLog reloaded = procLogRepository.findById(ledger.getProcLogSn()).orElseThrow();
            assertThat(reloaded.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
            assertThat(reloaded.getErrorCd()).isEqualTo(KpstDeidentService.ACK_MISSING_CODE);
            assertThat(videoRepository.findById(raw.getRawSn()).orElseThrow().getDeIdntfYn()).isEqualTo("F");
            org.mockito.Mockito.verify(kpstClient, org.mockito.Mockito.never()).retrieveProgress(any(), any());
        } finally {
            setField(kpstDeidentService, "submitAckGraceSec", 180L);
        }
    }

    // ────────────────────────── M3 — 호출자 tx 롤백 시 영상을 'F' 로 만들지 않는다 ──────────────────────────

    /**
     * ★ M3 — 요청 트랜잭션이 <b>롤백</b>되면 외부로 아무것도 나가지 않는다. 그런데 원장은
     * {@code REQUIRES_NEW} 로 이미 독립 커밋돼 있어 그대로 두면 폴러가 ACK 유예(기본 180초) 만료로
     * 회수하고, <b>그 회수의 종착이 {@code DE_IDNTF_YN='F'}</b> 다.
     *
     * <p>원래 결함: 실패한 요청이 3분 뒤 그 영상을 신고 게이트에 밀어 넣어
     * <b>라벨 조회 412 · 스트리밍 404 · export 보류</b>로 만들었다(APPROVED 영상 재비식별 요청 실패 시
     * 특히 해롭다 — 검수 완료된 영상이 조용히 차단 상태가 된다).
     *
     * <p>고정 내용: ①원장은 terminal 로 종결되어 폴링 대상에서 빠지고 ②영상 {@code DE_IDNTF_YN} 은
     * 'F' 로 바뀌지 않으며 ③이후 폴러가 그 행을 집어도 'F' 로 만들지 않는다(조건부 UPDATE 0행).
     *
     * <p><b>왜 ambient tx 를 붙이지 않는가</b>: 클래스에 {@code @Transactional} 을 달면 여기서 여는
     * 바깥 트랜잭션이 테스트 트랜잭션에 흡수돼 {@code afterCompletion} 관측 자체가 무효가 된다. 이
     * 레포에는 자기호출 tx 경계 유실이 ambient tx 때문에 GREEN 으로 감춰진 실사고 이력이 있다.
     *
     * <p><b>RED 실증</b>: {@code KpstDeidentService.dispatchSubmit} 의 {@code afterCompletion} 배선을
     * 제거하면 원장이 {@code WAITING} 으로 남아 ①이 RED 이고, 폴러 유예 만료 회수가 ③에서 'F' 를 찍어
     * 그 단언도 RED 가 된다.
     */
    @Test
    @DisplayName("호출자_tx_롤백시_원장만_취소종결되고_영상은_F로_내려가지_않는다")
    void callerRollbackCancelsLedgerWithoutMarkingVideoFailed() throws Exception {
        // given — 위탁이 개시되면 안 되므로 외부 호출은 스텁만 두고 호출 여부를 단언한다.
        LsDataRaw raw = persistRaw();
        Long rawSn = raw.getRawSn();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.just(new KpstProjectResponse("success", 101L)));

        // when — 호출자(예: 재비식별 요청)가 자기 트랜잭션 안에서 위탁한 뒤 그 트랜잭션이 롤백된다.
        java.util.concurrent.atomic.AtomicReference<Long> procLogSnRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        new org.springframework.transaction.support.TransactionTemplate(controlTxManager)
                .executeWithoutResult(status -> {
                    procLogSnRef.set(kpstDeidentService.submit(raw).getProcLogSn());
                    status.setRollbackOnly();
                });
        Long procLogSn = procLogSnRef.get();

        // then ① — 외부로 나간 것이 없다(구독은 afterCommit 에서만 일어난다).
        org.mockito.Mockito.verify(kpstClient, org.mockito.Mockito.never())
                .createProject(any(KpstProjectRequest.class));

        // then ② — 원장은 취소 종결(terminal). 확정 실패와 구분되는 코드가 남는다.
        LsDeidentProcLog reloaded = procLogRepository.findById(procLogSn).orElseThrow();
        assertThat(reloaded.getPollSttsCd()).isEqualTo(LsDeidentProcLog.POLL_FAILED);
        assertThat(reloaded.getErrorCd()).isEqualTo(KpstDeidentService.SUBMIT_CANCELED_CODE);

        // then ③ — ★영상은 'F' 가 아니다. 'F' 면 그 영상이 신고 게이트에 걸려 차단 상태가 된다.
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn())
                .as("외부로 아무것도 나가지 않은 롤백이 영상을 비식별 실패로 만들면 안 된다")
                .isNotEqualTo("F");

        // then ④ — 폴러 후보에서 빠진다(terminal 이라 WAITING/POLLING 조회에 잡히지 않는다).
        assertThat(procLogRepository.findByPollSttsCdIn(
                        List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING),
                        org.springframework.data.domain.PageRequest.of(0, 500)))
                .extracting(LsDeidentProcLog::getProcLogSn)
                .doesNotContain(procLogSn);

        // then ⑤ — 유예를 0 으로 낮춰 폴러가 그 행을 직접 집어도 'F' 로 만들지 않는다
        //          (claimSubmitFailure 술어가 WAITING + prjId null 이라 0행 no-op).
        setField(kpstDeidentService, "submitAckGraceSec", 0L);
        try {
            kpstDeidentService.pollOne(reloaded);
        } finally {
            setField(kpstDeidentService, "submitAckGraceSec", 180L);
        }
        assertThat(videoRepository.findById(rawSn).orElseThrow().getDeIdntfYn()).isNotEqualTo("F");
        assertThat(procLogRepository.findById(procLogSn).orElseThrow().getErrorCd())
                .as("취소 종결 코드가 ACK 미수신 회수 코드로 덮이면 원인 추적이 뒤집힌다")
                .isEqualTo(KpstDeidentService.SUBMIT_CANCELED_CODE);
    }

    @Test
    @DisplayName("호출자_tx가_커밋되면_취소되지_않고_정상_위탁된다_M3_대조군")
    void callerCommitStillSubmits() {
        // given — 위양성 방지 대조군: 커밋 경로는 종전대로 구독·ACK 기록까지 간다.
        LsDataRaw raw = persistRaw();
        when(kpstClient.createProject(any(KpstProjectRequest.class)))
                .thenReturn(Mono.just(new KpstProjectResponse("success", 202L)));

        java.util.concurrent.atomic.AtomicReference<Long> procLogSnRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        new org.springframework.transaction.support.TransactionTemplate(controlTxManager)
                .executeWithoutResult(status ->
                        procLogSnRef.set(kpstDeidentService.submit(raw).getProcLogSn()));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(procLogRepository.findById(procLogSnRef.get()).orElseThrow().getKpstPrjId())
                        .isEqualTo(202L));
        assertThat(procLogRepository.findById(procLogSnRef.get()).orElseThrow().getErrorCd())
                .isNotEqualTo(KpstDeidentService.SUBMIT_CANCELED_CODE);
    }

    /** 프록시 대상 빈의 필드 주입값을 테스트에서 바꾸기 위한 헬퍼(AOP 프록시 언랩 포함). */
    private static void setField(Object target, String name, Object value) {
        try {
            Object unwrapped = org.springframework.aop.support.AopUtils.isAopProxy(target)
                    ? ((org.springframework.aop.framework.Advised) target).getTargetSource().getTarget()
                    : target;
            java.lang.reflect.Field f = unwrapped.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(unwrapped, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
