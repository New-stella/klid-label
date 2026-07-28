package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest;
import kr.co.cudo.authoring.augment.dto.AugmentRequestRequest.AugmentTypeCode;
import kr.co.cudo.authoring.augment.dto.AugmentRequestResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
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
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository jobRepository;
    @Autowired private LsWebhookIdempotencyRepository idempotencyRepository;
    @Autowired private WebhookIdempotencyLedger ledger;
    @Autowired private PlatformTransactionManager controlTransactionManager;

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
        seededRawSns.clear();
        seededSrcSns.clear();
        seededIdemKeys.clear();
    }

    private long nextRawSn() {
        return RAW_SN_SEQ.incrementAndGet();
    }

    private void seedStatus(Long rawDataId, String dataSttsCd) {
        tx.executeWithoutResult(s -> {
            LsRawDataStatus status = LsRawDataStatus.initial(rawDataId);
            status.transitionTo(dataSttsCd);
            statusRepository.save(status);
        });
        seededRawSns.add(rawDataId);
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
