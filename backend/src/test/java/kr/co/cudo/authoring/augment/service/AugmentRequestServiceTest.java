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
import kr.co.cudo.authoring.augment.integration.AugmentExternalModePolicy;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.integration.dto.GenAiContract;
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
import reactor.core.publisher.Mono;

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
 * @design AC-001
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
    /**
     * 외부 연동 모드 판정 — 실제 주입 여부(배선)를 관측하기 위해 mock 으로 대체한다.
     * 기본 반환은 {@code false}(=연동됨, local 프로파일의 {@code mode=http} 와 동일)라
     * <b>다른 모든 케이스의 동작은 종전 그대로</b>다 (R8 · {@code @design API-060}).
     */
    @MockBean private AugmentExternalModePolicy externalModePolicy;

    private TransactionTemplate tx;
    private TokenClaims reviewer;
    private TokenClaims worker;

    /** 본 테스트가 직접 만든 rawSn 추적 — 커밋되므로 종료 시 직접 정리한다. */
    private final List<Long> seededRawSns = new java.util.ArrayList<>();
    private final List<Long> seededSrcSns = new java.util.ArrayList<>();
    private final List<String> seededIdemKeys = new java.util.ArrayList<>();

    /** 테스트 격리용 고유 rawSn 시퀀스 (다른 테스트 데이터와 충돌 회피). */
    private static final AtomicLong RAW_SN_SEQ = new AtomicLong(990_000_000L);

    /**
     * 생성 조건 5항목 — 조건 자체가 관심사가 아닌 케이스에서 계약(전부 필수)을 채우는 고정값.
     * 조건 검증·전달 자체는 아래 전용 테스트와 {@code AugmentRequestContractTest} 가 본다.
     */
    private static final AugmentRequestRequest.Mtdt MTDT = new AugmentRequestRequest.Mtdt(
            AugmentPrompts.Time.NIGHT, AugmentPrompts.Season.WINTER, AugmentPrompts.Weather.RAIN,
            AugmentPrompts.Terrain.ROAD, AugmentPrompts.Severity.HIGH);

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(controlTransactionManager);
        reviewer = new TokenClaims("1",   Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        worker   = new TokenClaims("100", Role.WORKER,   Channel.INTERNAL, Instant.now().plusSeconds(3600));
        // Phase C-3 — 클라이언트는 Mono 를 반환한다(제출은 ACK 를 기다리지 않는다).
        given(externalClient.requestAugment(any()))
                .willReturn(Mono.just(AugmentSubmitResult.accepted("ext-job-" + UUID.randomUUID())));
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
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
                List.of(raw), List.of(AugmentTypeCode.NIGHT),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
                List.of(raw), List.of(AugmentTypeCode.RAIN),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
            return Mono.just(AugmentSubmitResult.accepted("ext-job-" + UUID.randomUUID()));
        });

        Long raw = nextRawSn();
        Long frame = seedFrame(raw, 0);
        String key = "idmp" + UUID.randomUUID().toString().replace("-", "");
        Long augSn = tx.execute(s -> augRepository.saveAndFlush(
                        LsDataAug.createRequested(frame, LsDataAug.AUG_WINTER, "1", key, null))
                .getDataAugSn());

        // when — 위탁 진입점을 프록시 경유로 동기 호출(비동기 브리지·커넥션 겹침 없음)
        AugmentJobSubmitService.SubmitOutcome outcome = submitService.submit(
                new AugmentRequestedItemEvent(augSn, raw, LsDataAug.AUG_WINTER,
                        java.util.Map.of("time", "NIGHT"), null, "FLOOD", null, key,
                        "http://localhost/v1/genai/callback", "1"));

        assertThat(syncActive.get())
                .as("외부 위탁 중 트랜잭션 동기화가 열려 있으면 그 스코프가 EM/커넥션을 붙잡은 채 "
                        + "REQUIRES_NEW 선기록이 두 번째 커넥션을 요구해 풀 데드락이 된다")
                .isFalse();
        assertThat(emBound.get())
                .as("외부 위탁 중 EntityManager 가 스레드에 바인딩돼 있으면 그것이 잡은 커넥션이 "
                        + "HTTP 왕복 내내 반납되지 않는다")
                .isFalse();
        assertThat(outcome.dispatched())
                .as("위탁이 실제로 개시돼 외부 호출 시점이 관측됐어야 한다").isEqualTo(1);
        assertThat(jobsOf(augSn)).as("청크 선기록도 정상 수행된다").hasSize(1);
    }

    // ============================================================
    // 구조화 프롬프트 (2026-07-31 신설)
    // ============================================================

    /**
     * 사용자 입력 5필드가 <b>가공 없이</b> 외부 위탁 페이로드의 {@code prompt} 로 나가는지 고정한다.
     * 구 구현은 증강 유형별 고정 문구를 서버가 만들어 보냈으므로, 이 테스트가 없으면 입력이 조용히
     * 무시되고 예전 문구가 나가도 아무도 모른다.
     */
    @Test
    @DisplayName("생성조건_5항목을_고르면_외부전송_mtdt_객체에_그대로_담긴다")
    void mtdtIsPassedThroughToExternalPayload() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        seedFrame(raw, 0);

        AugmentRequestRequest.Mtdt fields = new AugmentRequestRequest.Mtdt(
                AugmentPrompts.Time.DAWN, AugmentPrompts.Season.SUMMER, AugmentPrompts.Weather.FOG,
                AugmentPrompts.Terrain.UNDERPASS, AugmentPrompts.Severity.LOW);
        service.request(new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.RAIN),
                GenAiContract.EventType.WILDFIRE, null, fields, "지시문"), reviewer);
        awaitExternalSubmitted(1);

        ArgumentCaptor<AugmentSubmitCommand> captor =
                ArgumentCaptor.forClass(AugmentSubmitCommand.class);
        verify(externalClient, times(1)).requestAugment(captor.capture());

        assertThat(captor.getValue().mtdt())
                .as("고른 5항목이 그대로 외부 mtdt 객체가 된다(서버 고정 문구 아님)")
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "time", "DAWN", "season", "SUMMER", "weather", "FOG",
                        "terrain", "UNDERPASS", "severity", "LOW"));
        assertThat(captor.getValue().promptText())
                .as("자유 지시문은 mtdt 와 분리된 문자열로 나간다(v1.3)")
                .isEqualTo("지시문");
        assertThat(captor.getValue().evntType())
                .as("이벤트 유형은 요청자가 고른 값이며 영상의 관제 이벤트 코드가 아니다")
                .isEqualTo("WILDFIRE");
    }

    /**
     * 같은 (영상 × 종류) 반복 요청이 허용되므로(위 정책 블록), 결과물을 구분하려면 "어떤 조건으로
     * 만들었는가" 가 DB 에 남아야 한다. 저장본이 <b>실제로 나간 값과 동일</b>한지도 함께 본다 —
     * 둘이 갈라지면 역추적이 거짓이 된다.
     */
    @Test
    @DisplayName("생성조건이_DB에_분리형태로_보관되어_결과에서_역추적된다")
    void mtdtIsPersistedForTraceability() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest.Mtdt fields = new AugmentRequestRequest.Mtdt(
                AugmentPrompts.Time.DAWN, AugmentPrompts.Season.SUMMER, AugmentPrompts.Weather.FOG,
                AugmentPrompts.Terrain.UNDERPASS, AugmentPrompts.Severity.LOW);
        service.request(new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, fields, "지시문"), reviewer);

        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        String stored = augs.get(0).getPromptCn();
        assertThat(stored)
                .as("전송한 생성 조건이 나간 바디와 같은 분리 형태로 남아야 한다(v1.3)")
                .isNotNull()
                .contains("\"mtdt\":{")
                .contains("\"time\":\"DAWN\"")
                .contains("\"season\":\"SUMMER\"")
                .contains("\"weather\":\"FOG\"")
                .contains("\"terrain\":\"UNDERPASS\"")
                .contains("\"severity\":\"LOW\"")
                .contains("\"prompt\":\"지시문\"");

        // 결과 조회 경로(AugmentSummaryResponse)에서 도달 가능해야 한다.
        assertThat(kr.co.cudo.authoring.augment.dto.AugmentSummaryResponse.from(augs.get(0)).prompt())
                .as("결과 조회 응답에서 생성 조건을 되짚을 수 있어야 한다")
                .isEqualTo(stored);

        awaitJobsOf(augs.get(0).getDataAugSn());
    }

    /**
     * 개행이 섞인 입력이 <b>정규화 없이</b> 로그·저장·전송으로 흐르면 로그 위조(CWE-117)가 된다.
     * 정규화 단일 원천({@code VisibleTextNormalizer})을 실제로 통과하는지 값 축으로 고정한다.
     *
     * <p><b>대상은 이제 자유 지시문 하나다</b>(v1.3) — 생성 조건 다섯 항목은 허용 코드 enum 이라
     * 제어문자가 바인딩 단계를 통과할 수 없다. 반대로 자유 지시문은 여전히 사용자 자유 입력이라
     * 이 방어가 그대로 필요하다.
     *
     * <p>로그 축은 "요청 원문을 아예 로그에 싣지 않는다" 는 설계로 닫혀 있다(서비스는 jobId·actor·
     * rawSn·augType 만 남긴다) — 개행을 제거하는 것과 애초에 찍지 않는 것 <b>두 겹</b> 방어다.
     */
    @Test
    @DisplayName("개행이_포함된_자유지시문은_저장·전송본에_원본_개행이_남지_않는다")
    void controlCharactersAreStrippedFromPromptText() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        String injected = "NIGHT\n2026-01-01 FAKE LOG LINE\r\nINJECTED\tTAB" + (char) 0 + " ";
        service.request(new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.NIGHT),
                GenAiContract.EventType.FLOOD, null, MTDT, injected), reviewer);

        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        assertThat(augs.get(0).getPromptCn())
                .as("제어문자(개행/CR/탭)는 제거된다 — 원문자와 JSON 이스케이프 양쪽으로 확인한다. "
                        + "이스케이프 축까지 보지 않으면 Jackson 이 escape 로 바꿔 담은 것을 "
                        + "'제거됐다'고 오판한다(정규화를 지워도 통과하는 공허한 테스트가 된다)")
                .doesNotContain("\n").doesNotContain("\r").doesNotContain("\t")
                .doesNotContain("\\n").doesNotContain("\\r").doesNotContain("\\t")
                .contains("NIGHT2026-01-01 FAKE LOG LINEINJECTEDTAB");

        awaitJobsOf(augs.get(0).getDataAugSn());
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
                List.of(r1), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
                List.of(r1), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
                List.of(r1), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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

    // ============================================================
    // R8 · @design API-060 — 외부 미연동이면 접수 자체를 거부한다
    // ============================================================

    /**
     * 미연동({@code authoring.augment.external.mode=noop})이면 위탁도 콜백도 없는데 접수만 성공해,
     * 화면에는 만료 스윕이 돌 때까지 「진행 중」으로 보였다. 이제 접수 단계에서 503 으로 끊는다.
     *
     * <p>이 케이스는 <b>배선</b>을 본다 — 실제 스프링 컨텍스트에서 서비스가 판정 컴포넌트를 주입받아
     * 호출하는지. 순수 판정 로직·평가 순서는 {@code AugmentRequestContractTest} 가 본다.
     */
    @Test
    @DisplayName("외부_연동이_미연동이면_요청_접수를_503으로_거부한다")
    void 미연동이면_503으로_거부된다() {
        given(externalModePolicy.isNotLinked()).willReturn(true);
        Long r1 = nextRawSn();
        seedStatus(r1, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(r1, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(r1), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    // 배선 상세(모드 값·프로퍼티 키)는 응답으로 새지 않는다(CWE-209).
                    assertThat(ce.getMessage()).doesNotContain("noop");
                    assertThat(ce.getMessage())
                            .doesNotContain(AugmentExternalModePolicy.KEY_MODE);
                });

        // 고착될 PENDING 행을 만들지 않고, 외부로도 나가지 않는다.
        assertThat(augsOf(frame)).isEmpty();
        verify(externalClient, never()).requestAugment(any());
    }

    /**
     * 신규 게이트가 <b>기존 판정을 가리지 않는다</b> — 연동(http)이면 종전 접수 경로가 그대로 성립한다.
     * local 프로파일 기본값이 {@code mode=http} 이므로 이것이 정상 형상이다.
     */
    @Test
    @DisplayName("외부_연동이_http면_기존_접수_경로가_그대로_동작한다")
    void 연동이면_기존_접수경로가_유지된다() {
        given(externalModePolicy.isNotLinked()).willReturn(false);
        Long r1 = nextRawSn();
        seedStatus(r1, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(r1, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(r1), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.createdCount()).isEqualTo(1);
        List<LsDataAug> augs = augsOf(frame);
        assertThat(augs).hasSize(1);
        assertThat(augs.get(0).getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        awaitJobsOf(augs.get(0).getDataAugSn());
    }

    @Test
    @DisplayName("AugmentRequestService_검수_미완료_영상_요청시_NOT_REVIEWED_blockedVideoIds_포함")
    void rejectsWhenVideoNotApproved() {
        Long r2 = nextRawSn();
        seedStatus(r2, LsRawDataStatus.STTS_IN_REVIEW);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(r2), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
    @DisplayName("파생영상_증강요청시_400_이고_사유가_파생차단이다")
    void rejectsDerivativeVideoBeforeAnyOtherReason() {
        // given — 파생 영상이면서 <동시에> 미검수(상태 row 없음) + 비식별 신고 구간('F')이다.
        //         가드 순서가 틀리면 "미검수(NOT_REVIEWED)" 나 "신고 구간(PRECONDITION_FAILED)" 같은
        //         엉뚱한 사유가 먼저 뜨고 요청자는 진짜 사유(파생 차단)에 도달하지 못한다.
        Long parentRawSn = nextRawSn();
        Long derivativeRawSn = nextRawSn();
        jdbcTemplate.update("UPDATE LS_DATA_RAW SET ORGNL_RAW_SN = ?, DE_IDENT_YN = 'F' WHERE RAW_SN = ?",
                parentRawSn, derivativeRawSn);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(derivativeRawSn), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

        // when / then — 해상도 변경 경로와 동일한 400(INVALID_INPUT) 계열.
        assertThatThrownBy(() -> service.request(req, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
                    assertThat(ce.getMessage()).contains("파생 영상");
                    // 원본으로 유도하지 않는다 — 부모 rawSn 을 응답에 싣지 않는다(CWE-209/639).
                    assertThat(String.valueOf(ce.getDetails())).doesNotContain(String.valueOf(parentRawSn));
                });
        verify(externalClient, never()).requestAugment(any());
    }

    @Test
    @DisplayName("AugmentRequestService_LsRawDataStatus_row가_없는_영상_요청시_NOT_REVIEWED")
    void rejectsWhenStatusRowMissing() {
        Long missing = nextRawSn(); // status row 없음

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(missing), List.of(AugmentTypeCode.NIGHT),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
                List.of(r1, r2), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.request(new AugmentRequestRequest(
                List.of(r1), List.of(AugmentTypeCode.WINTER, AugmentTypeCode.NIGHT),
                GenAiContract.EventType.FLOOD, null, MTDT, null), reviewer))
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
                .willReturn(Mono.error(new RuntimeException("외부 시스템 장애 (mock)")));

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

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
    // 중복 재요청 허용 (2026-07-31 정책 전환)
    //
    // ★ 이 블록의 기대값을 <다시 뒤집지 말 것>. 구 정책("요청 1회 = 파생영상 1건", 2026-07-29)은
    //   활성 중복 요청을 409 로 막고 부분 유니크 인덱스 UK_LS_DATA_AUG_ACTVTN(V143)으로 최종
    //   방어했으나, <사용자가 2026-07-31 에 명시적으로 폐기>했다. 근거 원문:
    //   "증강 이미지가 요청때마다 다르게 나올텐데 원하는 이미지가 안 나오면 동일하게 다시 요청할 수도 있다".
    //   즉 같은 영상·같은 종류의 재요청은 결함이 아니라 <정상 운영 동선>이다.
    //   연타(오조작) 방어는 FE 단독 책임으로 이관됐다 — BE 에 409 를 되살리는 것이 아니다.
    // ============================================================

    @Test
    @DisplayName("같은_영상_같은_종류로_두_번_요청해도_모두_성공한다")
    void repeatedRequestForSameVideoAndTypeSucceeds() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

        assertThat(service.request(req, reviewer).createdCount()).isEqualTo(1);
        assertThat(service.request(req, reviewer).createdCount()).isEqualTo(1);

        assertThat(augsOf(frame)).hasSize(2);
    }

    /**
     * 채택(ACCEPTED)은 되돌릴 수 없는 종결 상태라 구 정책에서는 <b>같은 (영상 × 종류) 재요청이 영구
     * 불가</b>했다(409). 지금은 채택된 파생본이 이미 있어도 "다른 결과를 받아보고 싶다" 는 요구가
     * 정당하므로 그대로 접수된다.
     */
    @Test
    @DisplayName("채택된_증강이_있어도_같은_종류로_다시_요청할_수_있다")
    void acceptedAugmentAllowsReRequest() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.NIGHT),
                GenAiContract.EventType.FLOOD, null, MTDT, null);
        service.request(req, reviewer);
        // 검수 승인(ACCEPTED) — 채택된 파생본이 이미 존재하는 상태.
        tx.executeWithoutResult(s -> augRepository.findBySrcSnOrderByAugTypeCd(frame)
                .forEach(a -> a.applyGenerationResult(LsDataAug.STTS_ACCEPTED)));

        assertThat(service.request(req, reviewer).createdCount()).isEqualTo(1);

        assertThat(augsOf(frame)).hasSize(2);
        assertThat(augsOf(frame)).extracting(LsDataAug::getAugProcSttsCd)
                .containsExactlyInAnyOrder(LsDataAug.STTS_ACCEPTED, LsDataAug.STTS_PENDING);
    }

    @Test
    @DisplayName("반려된_증강은_같은_종류로_다시_요청할_수_있다")
    void rejectedAugmentAllowsReRequest() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.RAIN),
                GenAiContract.EventType.FLOOD, null, MTDT, null);
        service.request(req, reviewer);
        // 반려(REJECTED) 후 재요청 — 구 정책에서도 허용되던 동선이며 정책 전환 후에도 그대로다(회귀 가드).
        tx.executeWithoutResult(s -> augRepository.findBySrcSnOrderByAugTypeCd(frame)
                .forEach(a -> a.applyGenerationResult(LsDataAug.STTS_REJECTED)));

        AugmentRequestResponse resp = service.request(req, reviewer);

        assertThat(resp.jobId()).isNotNull();
        assertThat(augsOf(frame)).hasSize(2);
        assertThat(augsOf(frame)).extracting(LsDataAug::getAugProcSttsCd)
                .containsExactlyInAnyOrder(LsDataAug.STTS_REJECTED, LsDataAug.STTS_PENDING);
    }

    /**
     * 활성 중복 INSERT 가 <b>DB 레벨에서도 허용</b>되는지 확인한다 — 서비스 가드만 지우고 인덱스를
     * 남기면 재요청이 500(제약 위반)으로 죽어 정책 전환이 반쪽이 된다.
     *
     * <p>동시에 <b>해상도 파생 전용 유니크는 살아 있어야</b> 한다. 두 인덱스는 같은 컬럼쌍
     * {@code (SRC_SN, AUG_TYPE_CD)} 위에 있어 술어만 다르므로, 정리 과정에서 함께 지워지기 쉽다.
     * {@code UK_LS_DATA_AUG_RESL}(V125)은 저작도구 <b>내부</b> 생성물(같은 프리셋을 두 번 만들 이유가
     * 없다)의 이중 생성을 막는 별개 계약이라 이번 정책 전환과 무관하다.
     */
    @Test
    @DisplayName("해상도파생_유니크_UK_LS_DATA_AUG_RESL_은_유지되고_활성중복_유니크만_사라진다")
    void resolutionUniqueSurvivesWhileActiveUniqueIsDropped() {
        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE lower(tablename) = 'ls_data_aug'",
                String.class);

        assertThat(indexNames).extracting(String::toLowerCase)
                .as("해상도 파생 이중 생성 방어(V125)는 이번 정책 전환 대상이 아니다")
                .contains("uk_ls_data_aug_resl")
                .as("활성 중복 유니크(V143)는 V153 에서 제거됐다")
                .doesNotContain("uk_ls_data_aug_actvtn");

        // 행 레벨에서도 확인 — 같은 (SRC_SN, AUG_TYPE_CD) 활성 행 2건이 실제로 적재된다.
        Long raw = nextRawSn();
        Long frame = seedFrame(raw, 0);
        tx.executeWithoutResult(s -> augRepository.saveAndFlush(
                LsDataAug.createPending(frame, LsDataAug.AUG_WINTER, null, "1")));
        tx.executeWithoutResult(s -> augRepository.saveAndFlush(
                LsDataAug.createPending(frame, LsDataAug.AUG_WINTER, null, "1")));

        assertThat(augsOf(frame)).hasSize(2);
    }

    /**
     * 동시 재요청도 <b>둘 다 성공</b>한다. 구 구현에서는 부분 유니크 인덱스가 패자를 409 로 떨어뜨렸고
     * 그것이 의도된 계약이었다 — 정책 전환으로 인덱스가 사라졌으므로 두 건 모두 접수돼야 한다.
     * (한쪽이 500 으로 죽으면 인덱스나 제약이 어딘가 남아 있다는 신호다.)
     */
    @Test
    @DisplayName("동시_증강_요청_2건이_모두_성공한다")
    void concurrentRequests_bothSucceed() throws Exception {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);
        Long frame = seedFrame(raw, 0);
        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

        int threads = 2;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger created = new java.util.concurrent.atomic.AtomicInteger();
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

        assertThat(created.get()).as("동시 재요청 2건이 모두 접수돼야 한다").isEqualTo(threads);
        assertThat(other.get()).as("제약 위반 등으로 떨어진 요청이 없어야 한다").isZero();
        assertThat(augsOf(frame)).as("요청 수만큼 증강 행이 생긴다").hasSize(threads);
    }

    @Test
    @DisplayName("AugmentRequestService_WORKER_요청시_FORBIDDEN")
    void workerCannotRequest() {
        Long raw = nextRawSn();
        seedStatus(raw, LsRawDataStatus.STTS_APPROVED);

        AugmentRequestRequest req = new AugmentRequestRequest(
                List.of(raw), List.of(AugmentTypeCode.WINTER),
                GenAiContract.EventType.FLOOD, null, MTDT, null);

        assertThatThrownBy(() -> service.request(req, worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
