package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitCommand;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitResult;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 콜백 충실 플로우 Phase 1 — 증강 요청 서비스 테스트 (DEV_FIX).
 *
 * <p>request() 는 키를 실은 PENDING 행을 단일 save 한 뒤 건별 이벤트를 발행하고,
 * 외부 위탁(청크 선기록 + 외부 호출)은 {@code AugmentRequestBridge} 가 AFTER_COMMIT 에서 수행한다.
 * 따라서 AFTER_COMMIT 발화를 검증하려면 <b>실제 커밋</b>이 필요하므로 본 테스트는
 * {@code @Transactional} 롤백을 쓰지 않고 {@link TransactionTemplate} 로 커밋한 뒤
 * 생성된 행/멱등 키를 명시적으로 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AugmentRequestServiceTest {

    @Autowired private AugmentRequestService service;
    /** 위탁 진입점 — 트랜잭션 전파(NOT_SUPPORTED) 불변식을 프록시 경유로 직접 관측하기 위해 주입한다. */
    @Autowired private AugmentJobSubmitService submitService;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository jobRepository;
    @Autowired private LsWebhookIdempotencyRepository idempotencyRepository;
    @Autowired private WebhookIdempotencyLedger ledger;
    @Autowired private PlatformTransactionManager controlTransactionManager;
    /**
     * EM 바인딩 관측용 — {@code TransactionSynchronizationManager} 의 JPA 리소스 키는 EMF 인스턴스다.
     * {@code controlTransactionManager} 가 쓰는 것과 <b>같은</b> EMF 여야 키가 일치한다.
     */
    @Autowired @Qualifier("controlEntityManagerFactory") private EntityManagerFactory controlEmf;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockBean private ExternalAugmentClient externalClient;

    private TransactionTemplate tx;
    private TokenClaims reviewer;
    private TokenClaims worker;

    /** 본 테스트가 직접 만든 rawSn 추적 — 커밋되므로 종료 시 직접 정리한다. */
    private final List<Long> seededRawSns = new java.util.ArrayList<>();
    private final List<Long> seededSrcSns = new java.util.ArrayList<>();
    private final List<String> seededIdemKeys = new java.util.ArrayList<>();

    /** 테스트 격리용 고유 rawSn 시퀀스 (다른 테스트 데이터와 충돌 회피). */
    private static final AtomicLong RAW_SN_SEQ = new AtomicLong(990_000_000L);

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(controlTransactionManager);
        reviewer = new TokenClaims("1",   Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        worker   = new TokenClaims("100", Role.WORKER,   Channel.INTERNAL, Instant.now().plusSeconds(3600));
        given(externalClient.requestAugment(any()))
                .willReturn(AugmentSubmitResult.accepted("ext-job-" + UUID.randomUUID()));
    }

    @AfterEach
    void cleanup() {
        tx.executeWithoutResult(s -> {
            for (Long srcSn : seededSrcSns) {
                augRepository.findBySrcSnOrderByAugTypeCd(srcSn)
                        .forEach(a -> {
                            if (a.getIdempotencyKey() != null) seededIdemKeys.add(a.getIdempotencyKey());
                            augRepository.delete(a);
                        });
                srcRepository.findById(srcSn).ifPresent(srcRepository::delete);
            }
            for (Long rawSn : seededRawSns) {
                statusRepository.findByRawDataIdIn(List.of(rawSn))
                        .forEach(statusRepository::delete);
            }
        });
        // 멱등 키는 REQUIRES_NEW 로 이미 커밋됨 — 별도 트랜잭션에서 정리
        tx.executeWithoutResult(s -> seededIdemKeys.forEach(k -> {
            if (idempotencyRepository.existsById(k)) idempotencyRepository.deleteById(k);
        }));
        // 마지막으로 부모 영상 삭제 — V146 FK ON DELETE CASCADE 로 남은 자식(작업상태·프레임·증강)까지 정리된다.
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
        seededSrcSns.clear();
        seededIdemKeys.clear();
    }

    /**
     * 격리된 새 영상 1건을 <b>실제로 적재</b>하고 그 rawSn 을 반환한다.
     *
     * <p>V146(DB-ISSUE-01) 이후 작업상태·프레임·증강 행이 모두 {@code LS_DATA_RAW} 를 FK 로 참조하므로
     * 임의 정수를 rawSn 으로 쓰던 구 방식은 성립하지 않는다(그렇게 만든 데이터는 실제로는 고아였다).
     */
    private long nextRawSn() {
        long rawSn = RAW_SN_SEQ.incrementAndGet();
        RawVideoFixture.seedRaw(jdbcTemplate, rawSn);
        seededRawSns.add(rawSn);
        return rawSn;
    }

    private void seedStatus(Long rawDataId, String dataSttsCd) {
        tx.executeWithoutResult(s -> {
            LsRawDataStatus status = LsRawDataStatus.initial(rawDataId);
            status.transitionTo(dataSttsCd);
            statusRepository.save(status);
        });
    }

    /** 영상(rawSn)에 대표 프레임을 적재하고 첫 프레임 srcSn 을 반환. */
    private Long seedFrame(Long rawSn, int frameNo) {
        Long srcSn = tx.execute(s -> srcRepository.save(
                // Phase 7-A1 — 외부 위탁 input_files 는 비식별 경로만 쓴다. 비식별 경로가 없으면
                // fail-closed 로 위탁이 거부되므로 정상 시드는 비식별 경로를 함께 채운다.
                LsDataSrc.create(rawSn, frameNo, null,
                        "/storage/raw/" + rawSn + "_" + frameNo + ".jpg",
                        "/storage/deidentified/" + rawSn + "_" + frameNo + ".jpg", null))
                .getSrcSn());
        seededSrcSns.add(srcSn);
        return srcSn;
    }

    private List<LsDataAug> augsOf(Long srcSn) {
        return tx.execute(s -> augRepository.findBySrcSnOrderByAugTypeCd(srcSn));
    }

    /** 위탁 job(=발급 원장 LS_DATA_AUG_JOB) 조회 — 구 webhook 원장을 대체하는 진실원. */
    private List<LsDataAugJob> jobsOf(Long dataAugSn) {
        return tx.execute(s -> jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn));
    }

    /** 청크 request_id 로 위탁 job 존재 여부 조회. */
    private java.util.Optional<LsDataAugJob> jobByKey(String requestId) {
        return tx.execute(s -> jobRepository.findByIdempotencyKey(requestId));
    }

    /**
     * 위탁 job 이 선기록될 때까지 대기한 뒤 반환한다 (Phase 8 DEV_FIX HIGH-1).
     *
     * <p>{@code AugmentRequestBridge} 의 AFTER_COMMIT 리스너는 이제 {@code @Async} 다 — 커밋 스레드에서
     * 그대로 돌면 {@code PROPAGATION_REQUIRED} 인계가 <b>이미 커밋된</b> 트랜잭션에 참여해 커밋되지 않고
     * {@code FOR UPDATE} 잠금 조회가 {@code TransactionRequiredException} 으로 튀기 때문이다. 따라서
     * 커밋 후 효과는 <b>동기 관측을 강요하지 않고</b> 대기해서 본다(강요하면 그 함정을 되살리게 된다).
     */
    private List<LsDataAugJob> awaitJobsOf(Long dataAugSn) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .until(() -> !jobsOf(dataAugSn).isEmpty());
        return jobsOf(dataAugSn);
    }

    /** 외부 위탁 호출이 비동기 스레드에서 실제로 나갈 때까지 대기한다. */
    private void awaitExternalSubmitted(int expectedCalls) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> verify(externalClient, times(expectedCalls)).requestAugment(any()));
    }

    // ============================================================
    // 신규 RED — AFTER_COMMIT 고아 키 방지 / 단일 save
    // ============================================================

    @Test
    @DisplayName("요청트랜잭션_커밋후_AFTER_COMMIT에서_위탁job이_선기록된다")
    void submitJobRecordedAfterCommit() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER));

        // when — 실제 커밋 → AFTER_COMMIT 발화
        service.request(req, reviewer);

        // then — 커밋 후 발급 원장(LS_DATA_AUG_JOB.IDMP_KEY)에 청크 키가 선기록됨.
        //   구 LS_WEBHOOK_IDEMPOTENCY(AUGMENT) write 는 제거됐다 — 수신 게이트가 읽지 않는 죽은 원장이었다.
        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        String key = augs.get(0).getIdempotencyKey();
        assertThat(key).isNotNull();
        assertThat(awaitJobsOf(augs.get(0).getDataAugSn()))
                .as("위탁 job 이 선기록돼야 한다(발급 원장)")
                .isNotEmpty()
                .allSatisfy(job -> assertThat(job.getIdempotencyKey()).startsWith(key + "-"));
        assertThat(ledger.isIssued(key))
                .as("구 webhook 원장에는 더 이상 기록하지 않는다").isFalse();
    }

    @Test
    @DisplayName("요청트랜잭션_롤백시_증강행도_위탁도_남지_않는다")
    void noOrphanLedgerKeyOnRollback() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.NIGHT));

        // when — request() 를 트랜잭션 안에서 호출 후 강제 롤백 (커밋 미발생)
        List<String> capturedKeys = tx.execute(s -> {
            service.request(req, reviewer);
            // request() 가 생성한(아직 미커밋) aug 의 키 확보
            List<String> keys = augRepository.findBySrcSnOrderByAugTypeCd(frame).stream()
                    .map(LsDataAug::getIdempotencyKey).toList();
            s.setRollbackOnly();
            return keys;
        });

        // then — 롤백되었으므로 aug 행도 위탁 job 도 남지 않는다 (고아 없음)
        assertThat(augsOf(frame)).isEmpty();
        capturedKeys.forEach(k -> {
            if (k != null) seededIdemKeys.add(k); // 만에 하나 남으면 cleanup 안전망
            assertThat(jobByKey(AugmentJobSubmitService.chunkRequestId(k, 1)))
                    .as("롤백된 요청의 위탁 job 은 선기록되지 않아야 한다").isEmpty();
        });
        // 외부 콜백도 발생하지 않음
        verify(externalClient, never()).requestAugment(any());
    }

    @Test
    @DisplayName("aug는_단일_save로_idempotencyKey와_함께_저장된다")
    void augSavedWithIdempotencyKeyInSingleSave() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER));

        service.request(req, reviewer);

        // then — 저장된 aug 는 처음부터 IDMP_KEY/OTSD_JOB_ID 를 보유 (중간 null 상태 없음)
        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        LsDataAug aug = augs.get(0);
        assertThat(aug.getIdempotencyKey())
                .isNotNull().matches("^[A-Za-z0-9_-]+$").hasSizeLessThanOrEqualTo(64);
        // Phase 7-A1 — job_id 는 외부가 202 로 발급한다. 요청 시점 aug 행에는 없다(우리가 짓지 않음).
        assertThat(aug.getExternalJobId()).isNull();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        // @Async 위탁이 뒤늦게 정리(@AfterEach)와 겹치지 않도록 종료를 기다린다.
        awaitJobsOf(aug.getDataAugSn());
    }

    @Test
    @DisplayName("AFTER_COMMIT에서_externalClient에_콜백컨텍스트가_전달된다")
    void passesCallbackContextToExternalClientAfterCommit() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.RAIN));

        service.request(req, reviewer);
        awaitExternalSubmitted(1);

        ArgumentCaptor<AugmentSubmitCommand> captor =
                ArgumentCaptor.forClass(AugmentSubmitCommand.class);
        verify(externalClient, times(1)).requestAugment(captor.capture());

        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        LsDataAug aug = augs.get(0);
        AugmentSubmitCommand command = captor.getValue();
        assertThat(command.originAugSn()).isEqualTo(aug.getDataAugSn());
        assertThat(command.augType()).isEqualTo(LsDataAug.AUG_RAIN);
        // 청크 request_id = aug 멱등키 + 청크순서
        assertThat(command.requestId()).startsWith(aug.getIdempotencyKey()).endsWith("-1");
        assertThat(command.callbackUrl()).endsWith("/v1/genai/callback");
        // 외부로 나가는 경로는 비식별 프레임뿐이다(원본 경로 유출 금지).
        assertThat(command.inputFiles()).isNotEmpty()
                .allSatisfy(f -> assertThat(f.filePath()).startsWith("/storage/deidentified/"));
    }

    /**
     * 위탁 경로가 <b>커넥션을 겹쳐 잡지 않는지</b> 고정한다 (HikariPool 고갈 flaky 의 근본 원인).
     *
     * <h3>무엇이 문제였나</h3>
     * <p>{@code AugmentJobSubmitService} 는 클래스 레벨 {@code @Transactional(readOnly=true)} 를 갖고
     * 있어 {@code submit()} <b>전 구간</b>(외부 HTTP 왕복 포함)에 트랜잭션이 열려 있었다. 그 트랜잭션은
     * 첫 조회 시점에 커넥션 1개를 잡고 커밋까지 놓지 않는데, 그 안에서 청크 선기록
     * ({@code AugmentJobRecorder}, {@code REQUIRES_NEW})이 <b>같은 풀에서 두 번째 커넥션</b>을 요구한다.
     * 즉 위탁 1건이 스레드당 커넥션 2개를 동시 점유한다.
     *
     * <p>따라서 위탁이 2건 겹치면(요청 2회의 AFTER_COMMIT 이 {@code batchAsyncExecutor} 에서 병렬 실행)
     * 두 스레드가 각자 첫 커넥션을 잡고 서로의 두 번째 커넥션을 기다려 <b>풀 데드락</b>이 된다 —
     * 테스트 풀(2)에서는 30s 타임아웃({@code HikariPool … request timed out}) 뒤
     * {@code CannotCreateTransactionException} 이 터지고, 그 30초 동안 다른 모든 스레드
     * (Quartz·다음 테스트 본체)까지 대기열에 쌓여 <b>엉뚱한 테스트가 실패</b>한다(간헐 실패의 정체).
     * 운영 풀에서도 동시 위탁 수의 2배 커넥션을 요구하는 증폭은 그대로다.
     *
     * <h3>불변식 — 관측 지표는 <b>EM/커넥션 점유를 반영하는 축</b>이어야 한다</h3>
     * <p>외부 HTTP 왕복 시점에 <b>트랜잭션 동기화가 열려 있지 않고</b>(=EM/커넥션을 붙잡을 스코프가 없고)
     * <b>EntityManager 가 스레드에 바인딩돼 있지 않아야</b> 한다. 이는 {@code AugmentJobSubmitService}
     * 클래스 주석이 명시한 설계 의도("외부 HTTP 왕복 동안 쓰기 트랜잭션과 커넥션을 붙잡지 않는다")와 같다.
     *
     * <h3>★ {@code isActualTransactionActive()} 로 관측하면 이 테스트는 <b>공허해진다</b> (함정)</h3>
     * <p>구 구현은 {@code TransactionSynchronizationManager.isActualTransactionActive()} 를 봤다. 그런데
     * {@code AbstractPlatformTransactionManager.getTransaction} 은 {@code NOT_SUPPORTED}(기존 트랜잭션 없음)
     * 에서 {@code startTransaction}(→{@code setActualTransactionActive(true)}) 경로를 <b>타지 않고</b>
     * "empty transaction"({@code prepareTransactionStatus(def, null, …)})을 만든다. 즉 클래스에
     * {@code @Transactional(propagation = NOT_SUPPORTED)} 를 다시 붙여도 이 플래그는 {@code false} 로 남아
     * <b>테스트가 GREEN 을 유지</b>했다 — 정작 실측으로 데드락이 재현된 형태가 무방비였다.
     *
     * <p>{@code NOT_SUPPORTED} 가 실제로 켜는 것은 <b>동기화</b>({@code initSynchronization()})이고, EM 은
     * 그 동기화 위에서 스레드에 바인딩된다. 그래서 관측 축을
     * {@code isSynchronizationActive()} + {@code getResource(controlEmf)} 로 바꾼다 — 같은 코드베이스의
     * {@code AugmentJobExpiryTxService}·{@code AugmentResultService} 도 이 둘을 구분해 쓴다.
     * 이 축이면 {@code readOnly}(REQUIRED) 재부착과 {@code NOT_SUPPORTED} 재부착이 <b>둘 다 RED</b> 다.
     *
     * <h3>왜 요청 API 가 아니라 위탁 진입점을 직접 호출하나</h3>
     * <p>{@code service.request(...)} 로 재현하면 검증 대상이 아닌 <b>비동기 위탁</b>이 딸려 온다 —
     * 이 테스트가 관측을 끝낸 뒤에도 {@code batchAsyncExecutor} 스레드의 in-flight 작업이 남아
     * 커넥션(풀 2)을 물고 <b>뒤따르는 테스트</b>를 대기시킨다(정작 이 테스트가 잡으려는 고갈 현상을
     * 테스트가 스스로 만드는 셈). 불변식은 "{@code submit()} 이 트랜잭션을 여는가" 하나이므로,
     * 프록시를 통해 <b>테스트 스레드에서 동기 1회 호출</b>하면 그대로 관측된다 — 어느 순간에도
     * 커넥션은 1개이고 in-flight 도 남지 않는다.
     */
    @Test
    @DisplayName("외부_위탁_HTTP_왕복중에는_트랜잭션_동기화와_EntityManager를_잡지_않는다")
    void externalSubmitDoesNotHoldTransactionConnection() {
        AtomicBoolean syncActive = new AtomicBoolean(true);
        AtomicBoolean emBound = new AtomicBoolean(true);
        given(externalClient.requestAugment(any())).willAnswer(invocation -> {
            // 커넥션 점유를 반영하는 축으로 관측한다 — isActualTransactionActive() 는 NOT_SUPPORTED 에서
            // false 로 남아(위 클래스 주석 "함정") 회귀를 놓친다.
            syncActive.set(TransactionSynchronizationManager.isSynchronizationActive());
            emBound.set(TransactionSynchronizationManager.getResource(controlEmf) != null);
            return AugmentSubmitResult.accepted("ext-job-" + UUID.randomUUID());
        });

        Long raw = nextRawSn();
        Long frame = seedFrame(raw, 0);
        String key = "idmp" + UUID.randomUUID().toString().replace("-", "");
        Long augSn = tx.execute(s -> augRepository.saveAndFlush(
                        LsDataAug.createRequested(frame, LsDataAug.AUG_WINTER, "1", key, null))
                .getDataAugSn());

        // when — 위탁 진입점을 프록시 경유로 동기 호출(비동기 브리지·커넥션 겹침 없음)
        AugmentJobSubmitService.SubmitOutcome outcome = submitService.submit(
                new AugmentRequestedItemEvent(augSn, raw, LsDataAug.AUG_WINTER, key,
                        "http://localhost/v1/genai/callback", "1"));

        assertThat(syncActive.get())
                .as("외부 위탁 중 트랜잭션 동기화가 열려 있으면 그 스코프가 EM/커넥션을 붙잡은 채 "
                        + "REQUIRES_NEW 선기록이 두 번째 커넥션을 요구해 풀 데드락이 된다")
                .isFalse();
        assertThat(emBound.get())
                .as("외부 위탁 중 EntityManager 가 스레드에 바인딩돼 있으면 그것이 잡은 커넥션이 "
                        + "HTTP 왕복 내내 반납되지 않는다")
                .isFalse();
        assertThat(outcome.accepted()).as("위탁이 실제로 수락돼 외부 호출 시점이 관측됐어야 한다").isEqualTo(1);
        assertThat(jobsOf(augSn)).as("청크 선기록도 정상 수행된다").hasSize(1);
    }

    // ============================================================
    // 회귀 — 기존 동작 보존
    // ============================================================

    @Test
    @DisplayName("AugmentRequestService_검수_완료_영상_단건_요청시_정상_jobId_반환")
    void requestSucceedsWhenVideoApproved() {
        Long r1 = nextRawSn();
        seedStatus(r1, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(r1, 0);

        // 단건 계약(E-ISSUE-08) — 영상 1건 × 종류 1개
        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(r1), List.of(AugmentTypeCode.WINTER));

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.jobId()).isNotNull();
        assertThat(resp.videoCount()).isEqualTo(1);
        assertThat(resp.typeCount()).isEqualTo(1);
        assertThat(resp.createdCount())
                .as("요청 echo 가 아니라 실제 적재된 증강 행 수(E-ISSUE-09)").isEqualTo(1);
        assertThat(resp.requestedAt()).isNotNull().isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
        awaitJobsOf(augsOf(frame).get(0).getDataAugSn());
    }

    @Test
    @DisplayName("증강요청시_LS_DATA_AUG가_PENDING으로_생성된다")
    void createsPendingAug() {
        Long r1 = nextRawSn();
        seedStatus(r1, LsRawDataStatus.STTS_APPROVED);
        Long frame1 = seedFrame(r1, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(r1), List.of(AugmentTypeCode.WINTER));

        service.request(req, reviewer);

        List<LsDataAug> aug1 = augsOf(frame1);
        assertThat(aug1).hasSize(1);
        assertThat(aug1).allSatisfy(a -> {
            assertThat(a.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
            assertThat(a.getSrcSn()).isEqualTo(frame1);
        });
        assertThat(aug1).extracting(LsDataAug::getAugTypeCd)
                .containsExactly(LsDataAug.AUG_WINTER);
        awaitJobsOf(aug1.get(0).getDataAugSn());
    }

    @Test
    @DisplayName("증강요청시_idempotencyKey가_발급된다")
    void issuesIdempotencyKeyPerAug() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER));

        service.request(req, reviewer);

        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        LsDataAug aug = augs.get(0);
        assertThat(aug.getIdempotencyKey())
                .isNotNull().matches("^[A-Za-z0-9_-]+$").hasSizeLessThanOrEqualTo(64);
        assertThat(aug.getExternalJobId()).as("job_id 발급 주체는 외부다").isNull();
        assertThat(awaitJobsOf(aug.getDataAugSn()))
                .as("발급 원장은 LS_DATA_AUG_JOB.IDMP_KEY 다").isNotEmpty();
    }

    /**
     * E-ISSUE-09 — 구 구현은 프레임 없는 영상을 조용히 스킵하고 200 을 돌려줬다(생성 0건 = silent
     * no-op). 지금은 412 로 종결하고 어떤 영상이 막혔는지 알린다.
     */
    @Test
    @DisplayName("프레임없는_영상_요청은_412로_거부되고_위탁도_나가지_않는다")
    void videoWithoutFrameIsRejected() {
        Long r1 = nextRawSn();
        seedStatus(r1, LsRawDataStatus.STTS_APPROVED);
        // 프레임 미적재

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(r1), List.of(AugmentTypeCode.WINTER));

        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.PRECONDITION_FAILED);
                    @SuppressWarnings("unchecked")
                    var details = (java.util.Map<String, Object>) ce.getDetails();
                    assertThat((List<?>) details.get("skippedVideoIds")).hasSize(1);
                });

        verify(externalClient, never()).requestAugment(any());
    }

    @Test
    @DisplayName("AugmentRequestService_검수_미완료_영상_요청시_NOT_REVIEWED_blockedVideoIds_포함")
    void rejectsWhenVideoNotApproved() {
        Long r2 = nextRawSn();
        seedStatus(r2, LsRawDataStatus.STTS_IN_REVIEW);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(r2), List.of(AugmentTypeCode.WINTER));

        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.NOT_REVIEWED);
                    assertThat(ce.getDetails()).isNotNull();
                    @SuppressWarnings("unchecked")
                    var details = (java.util.Map<String, Object>) ce.getDetails();
                    @SuppressWarnings("unchecked")
                    List<Long> blocked = (List<Long>) details.get("blockedVideoIds");
                    assertThat(blocked).containsExactly(r2);
                });
    }

    @Test
    @DisplayName("AugmentRequestService_LsRawDataStatus_row가_없는_영상_요청시_NOT_REVIEWED")
    void rejectsWhenStatusRowMissing() {
        Long missing = nextRawSn(); // status row 없음

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(missing), List.of(AugmentTypeCode.NIGHT));

        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.NOT_REVIEWED);
                    @SuppressWarnings("unchecked")
                    var details = (java.util.Map<String, Object>) ce.getDetails();
                    @SuppressWarnings("unchecked")
                    List<Long> blocked = (List<Long>) details.get("blockedVideoIds");
                    assertThat(blocked).containsExactly(missing);
                });
    }

    /**
     * E-ISSUE-08 — 단건 계약이 정본이다. 구 테스트(중복 입력 distinct 처리)는 DTO 가 길이 1 만
     * 허용하므로 실행될 수 없는 <b>사문 다건 로직</b>을 계약처럼 고정하고 있었다. 지금은 초과 입력이
     * 조용히 잘리지 않고 거부되는 것을 서비스 레벨에서도 고정한다(컨트롤러 400 은 {@code
     * AugmentRequestControllerTest} 가 담당).
     */
    @Test
    @DisplayName("단건_계약을_초과하면_400")
    void multiSelectionRejectedAtServiceLayer() {
        Long r1 = nextRawSn(), r2 = nextRawSn();
        seedStatus(r1, LsRawDataStatus.STTS_APPROVED);
        seedStatus(r2, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(r1, 0);
        seedFrame(r2, 0);

        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(r1, r2), List.of(AugmentTypeCode.WINTER)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(r1), List.of(AugmentTypeCode.WINTER, AugmentTypeCode.NIGHT)), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 초과 요청이 "일부만" 접수되지 않는다.
        assertThat(augsOf(frame)).isEmpty();
        verify(externalClient, never()).requestAugment(any());
    }

    @Test
    @DisplayName("AugmentRequestService_ExternalAugmentClient_실패시_트랜잭션_영향_없이_성공_응답")
    void externalFailureDoesNotBlockSuccess() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);
        given(externalClient.requestAugment(any()))
                .willThrow(new RuntimeException("외부 시스템 장애 (mock)"));

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER));

        // when — 외부 호출은 AFTER_COMMIT 에서 실패하지만 요청 트랜잭션/응답에는 영향 없음
        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.jobId()).isNotNull();
        assertThat(resp.videoCount()).isEqualTo(1);
        // aug 행과 위탁 job(실패 사유 포함)은 외부 실패와 무관하게 유지 (재시도 가능 양성 상태)
        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        assertThat(awaitJobsOf(augs.get(0).getDataAugSn()))
                .as("외부 호출 실패도 job 행에 사유와 함께 남는다(조용한 유실 금지)").isNotEmpty();
    }

    // ============================================================
    // HIGH-3 — 중복 증강 요청 차단 (파생 트리 팬아웃 DoS 근원 제거)
    // ============================================================

    @Test
    @DisplayName("이미_요청된_증강을_같은_종류로_다시_요청하면_409로_차단된다")
    void duplicateActiveAugmentRequestRejected() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER));
        service.request(req, reviewer);

        // when — 동일 (영상 × 종류) 재요청
        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    @SuppressWarnings("unchecked")
                    var details = (java.util.Map<String, Object>) ce.getDetails();
                    @SuppressWarnings("unchecked")
                    List<java.util.Map<String, Object>> dup =
                            (List<java.util.Map<String, Object>>) details.get("duplicatedRequests");
                    assertThat(dup).hasSize(1);
                    assertThat(dup.get(0)).containsEntry("videoId", raw)
                            .containsEntry("type", LsDataAug.AUG_WINTER);
                });

        // then — 파생 트리를 부풀릴 두 번째 증강 행이 생기지 않았다.
        assertThat(augsOf(frame)).hasSize(1);
    }

    @Test
    @DisplayName("채택된_증강도_같은_종류로_다시_요청하면_409로_차단된다")
    void acceptedAugmentBlocksReRequest() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.NIGHT));
        service.request(req, reviewer);
        // 검수 승인(ACCEPTED) — 채택된 파생본이 이미 존재하는 상태.
        tx.executeWithoutResult(s -> augRepository.findBySrcSnOrderByAugTypeCd(frame)
                .forEach(a -> a.applyReviewStatus(LsDataAug.STTS_ACCEPTED)));

        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(augsOf(frame)).hasSize(1);
    }

    @Test
    @DisplayName("반려된_증강은_같은_종류로_다시_요청할_수_있다")
    void rejectedAugmentAllowsReRequest() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.RAIN));
        service.request(req, reviewer);
        // 반려(REJECTED) — 종결 상태이므로 활성 유니크 대상에서 빠진다(정당한 재요청 동선 보존).
        tx.executeWithoutResult(s -> augRepository.findBySrcSnOrderByAugTypeCd(frame)
                .forEach(a -> a.applyReviewStatus(LsDataAug.STTS_REJECTED)));

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.jobId()).isNotNull();
        assertThat(augsOf(frame)).hasSize(2);
        assertThat(augsOf(frame)).extracting(LsDataAug::getAugProcSttsCd)
                .containsExactlyInAnyOrder(LsDataAug.STTS_REJECTED, LsDataAug.STTS_PENDING);
    }

    /**
     * DB 최종 방어 자체의 결정론적 가드 — 서비스 경로를 <b>우회</b>해 리포지토리로 직접 중복 INSERT 를
     * 시도한다. 부분 유니크 인덱스({@code UK_LS_DATA_AUG_ACTVTN}, V143)가 없으면 2행이 저장돼 RED.
     * REJECTED(종결)는 술어 밖이라 같은 키로 여러 건이 허용된다는 것도 함께 고정한다.
     */
    @Test
    @DisplayName("활성_중복_INSERT는_DB_부분유니크_인덱스가_거부하고_반려행은_허용한다")
    void partialUniqueIndexRejectsActiveDuplicateInsert() {
        Long raw = nextRawSn();
        Long frame = seedFrame(raw, 0);

        tx.executeWithoutResult(s -> augRepository.saveAndFlush(
                LsDataAug.createPending(frame, LsDataAug.AUG_WINTER, null, "1")));

        // 같은 (SRC_SN, AUG_TYPE_CD) 활성 행 두 번째 INSERT → 인덱스 위반
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> augRepository.saveAndFlush(
                LsDataAug.createPending(frame, LsDataAug.AUG_WINTER, null, "1"))))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // 반려(종결) 행은 술어 밖 — 같은 키로도 적재 가능해야 한다(재요청 동선 보존).
        tx.executeWithoutResult(s -> {
            LsDataAug rejected = LsDataAug.createPending(frame, LsDataAug.AUG_WINTER, null, "1");
            rejected.applyReviewStatus(LsDataAug.STTS_REJECTED);
            augRepository.saveAndFlush(rejected);
        });

        assertThat(augsOf(frame)).hasSize(2);
    }

    /**
     * 동시 요청은 서로의 미커밋 행을 보지 못하므로 서비스 사전 조회(1선)만으로는 전부 통과한다.
     * 실제 방어는 부분 유니크 인덱스 {@code UK_LS_DATA_AUG_ACTVTN}(V143)이며, 위반은
     * {@code DataIntegrityViolationException} → 409 로 표면화된다. 인덱스를 지우면 2건이 저장돼 RED.
     *
     * <p>PostgreSQL 은 제약 위반 시 트랜잭션 전체를 abort 시키므로 같은 트랜잭션 안에서 재시도할 수
     * 없다 — 패자는 요청 트랜잭션째 롤백되어 409 로 끝난다(부분 처리 금지).
     */
    @Test
    @DisplayName("동시_증강_요청_2건이어도_활성_증강행은_1건만_생성된다")
    void concurrentRequests_onlyOneActiveAugRow() throws Exception {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);
        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER));

        int threads = 2;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger conflicted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger other = new java.util.concurrent.atomic.AtomicInteger();
        try {
            java.util.concurrent.Future<?>[] futures = new java.util.concurrent.Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        service.request(req, reviewer);
                        created.incrementAndGet();
                    } catch (CustomException e) {
                        if (e.getErrorCode() == ErrorCode.CONFLICT) {
                            conflicted.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await(5, java.util.concurrent.TimeUnit.SECONDS);
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(created.get()).as("동시 요청 중 정확히 1건만 성공해야 한다").isEqualTo(1);
        assertThat(conflicted.get()).as("패자는 409(CONFLICT) 로 표면화돼야 한다(500 누수 금지)")
                .isEqualTo(threads - 1);
        assertThat(other.get()).isZero();
        assertThat(augsOf(frame)).as("활성 증강 행은 1건이어야 한다(파생 트리 팬아웃 방지)").hasSize(1);
    }

    @Test
    @DisplayName("AugmentRequestService_WORKER_요청시_FORBIDDEN")
    void workerCannotRequest() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER));

        assertThatThrownBy(() -> service.request(req, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
