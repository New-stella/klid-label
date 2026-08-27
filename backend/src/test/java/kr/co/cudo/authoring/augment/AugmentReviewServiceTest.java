package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.dto.AugmentJobStatus;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.support.RawVideoFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class AugmentReviewServiceTest {

    @Autowired private AugmentReviewService service;
    @Autowired private LsDataAugRepository repository;
    @Autowired private LsDataAugRvwRepository reviewRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private TokenClaims reviewer;
    private TokenClaims worker;

    /**
     * 증강 대표 프레임(srcSn) — <b>실재하는</b> 영상의 프레임이어야 한다.
     *
     * <p>V146(DB-ISSUE-01) 이후 {@code LS_DATA_AUG_RVW.DATA_RAW_SN} 이 {@code LS_DATA_RAW} 를 FK 로
     * 참조한다. 검수 서비스는 srcSn → rawSn 을 역해석해 검수 이력에 적는데, 프레임이 실재하지 않으면
     * rawSn 이 null 로 나와 {@code 0L} 센티널이 적히고 FK 에 걸린다. 구 픽스처는 존재하지 않는
     * srcSn(500)을 썼기 때문에 그 경로를 탔다 — 운영 형태(프레임 → 영상 연결 존재)로 시드한다.
     */
    private Long srcSn;

    @BeforeEach
    void setup() {
        reviewer = new TokenClaims("1",   Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        worker   = new TokenClaims("100", Role.WORKER,   Channel.INTERNAL, Instant.now().plusSeconds(3600));
        reviewRepository.deleteAll();
        repository.deleteAll();
        srcSn = newFrame();
    }

    /**
     * 실재하는 영상 1건 + 그 대표프레임 1건을 시드하고 SRC_SN 을 돌려준다.
     *
     * <p>{@code accept}/{@code reject} 는 검수 이력에 원본 RAW_SN 을 적어야 하므로 프레임 → 영상
     * 연결이 실재해야 한다(연결이 없으면 서비스가 CONFLICT 로 <b>정당하게</b> 거부한다 —
     * {@link #unresolvableRawSnRejectedWithoutSentinel} 참조).
     */
    private Long newFrame() {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        return srcRepository.saveAndFlush(
                LsDataSrc.create(rawSn, 0, "/storage/raw/" + rawSn + "_0.jpg", null)).getSrcSn();
    }

    private LsDataAug seedPending(String augType) {
        LsDataAug aug = LsDataAug.createPending(srcSn, augType, new BigDecimal("85.50"), "system");
        return repository.save(aug);
    }

    /**
     * <b>생성이 성공해 결과물(파생영상)이 실재하는</b> 증강 행 — accept/reject 정상 경로의 전제.
     *
     * <p>외부 증강은 웹훅이 성공 결과를 인계하며 {@code AUG_PROC_STTS_CD} 를 ACCEPTED 로 확정하고
     * <b>같은 트랜잭션</b>에서 파생 RAW 를 만든다({@code AugmentResultService.handle}). 검수는 그
     * 뒤에 온다 — 그래서 결정 테스트의 정상 픽스처는 PENDING 이 아니라 이 상태다.
     */
    private LsDataAug seedGenerated(String augType) {
        LsDataAug aug = seedPending(augType);
        aug.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        return repository.saveAndFlush(aug);
    }

    @Test
    @DisplayName("AugmentReviewService_WORKER가_accept_호출시_403_FORBIDDEN")
    void workerCannotAccept() {
        LsDataAug seed = seedPending(LsDataAug.AUG_WINTER);

        assertThatThrownBy(() -> service.accept(seed.getDataAugSn(), worker))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("검수_승인은_생성결과_컬럼을_바꾸지_않는다")
    void reviewerAcceptUpdatesStatusAndAudit() {
        LsDataAug seed = seedGenerated(LsDataAug.AUG_WINTER);

        var resp = service.accept(seed.getDataAugSn(), reviewer);

        // 응답 — LS_DATA_AUG_RVW 의 ACCEPTED status / 검수자 / 시각
        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(resp.decisionUserNo()).isEqualTo("1");
        assertThat(resp.decisionAt()).isNotNull();

        // ★ 축 분리 — 생성 결과 컬럼(AUG_PROC_STTS_CD)은 생성기(웹훅/finalize)만 쓴다.
        //   검수는 LS_DATA_AUG_RVW 만 건드리므로 이 값은 웹훅이 남긴 그대로여야 한다.
        LsDataAug after = repository.findById(seed.getDataAugSn()).orElseThrow();
        assertThat(after.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);

        // LS_DATA_AUG_RVW row INSERT 검증
        LsDataAugRvw rvw = reviewRepository.findLatestByDataAugSn(seed.getDataAugSn()).orElseThrow();
        assertThat(rvw.getRvwSttsCd()).isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
        assertThat(rvw.getRvwId()).isEqualTo("1");
        assertThat(rvw.getRvwDt()).isNotNull();
        assertThat(rvw.getDataAugSn()).isEqualTo(seed.getDataAugSn());
        assertThat(rvw.getDataSrcSn()).isEqualTo(seed.getSrcSn());
        assertThat(rvw.getRejectRsn()).isNull();
    }

    @Test
    @DisplayName("AugmentReviewService_REJECTED_시_사유_LS_DATA_AUG_RVW_REJECT_RSN_저장")
    void rejectStoresReason() {
        LsDataAug seed = seedGenerated(LsDataAug.AUG_NIGHT);

        var resp = service.reject(seed.getDataAugSn(), "야간 명도 조정 부정확", reviewer);

        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(resp.rejectReason()).isEqualTo("야간 명도 조정 부정확");

        // ★ 축 분리 — 반려도 생성 결과 컬럼을 건드리지 않는다(웹훅이 남긴 ACCEPTED 유지).
        LsDataAug after = repository.findById(seed.getDataAugSn()).orElseThrow();
        assertThat(after.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);

        // LS_DATA_AUG_RVW.REJECT_RSN 저장 검증
        LsDataAugRvw rvw = reviewRepository.findLatestByDataAugSn(seed.getDataAugSn()).orElseThrow();
        assertThat(rvw.getRvwSttsCd()).isEqualTo(LsDataAugRvw.STTS_REJECTED);
        assertThat(rvw.getRejectRsn()).isEqualTo("야간 명도 조정 부정확");
        assertThat(rvw.getRvwId()).isEqualTo("1");
        assertThat(rvw.getRvwDt()).isNotNull();
    }

    @Test
    @DisplayName("AugmentReviewService_반려_사유_누락시_INVALID_INPUT")
    void rejectWithoutReasonFails() {
        LsDataAug seed = seedGenerated(LsDataAug.AUG_NIGHT);

        assertThatThrownBy(() -> service.reject(seed.getDataAugSn(), "", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("AugmentReviewService_이미_ACCEPTED인_AUG_재처리시_409_CONFLICT")
    void duplicateAcceptReturnsConflict() {
        LsDataAug seed = seedGenerated(LsDataAug.AUG_RAIN);
        service.accept(seed.getDataAugSn(), reviewer);

        assertThatThrownBy(() -> service.accept(seed.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("생성성공으로_ACCEPTED_인_증강도_검수자가_승인할_수_있다")
    void webhookAcceptedAugmentIsStillReviewable() {
        // given — 외부 증강은 <웹훅이 항상 먼저> 도착해 생성 결과를 ACCEPTED 로 확정한다.
        LsDataAug seed = seedPending(LsDataAug.AUG_WINTER);
        seed.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        repository.saveAndFlush(seed);

        // when — 그 뒤에 REVIEWER 가 사용 결정을 내린다.
        //        구 구현은 두 주체가 같은 컬럼을 써서 여기서 <영구히 409> 였다(워크플로 도달 불가).
        var resp = service.accept(seed.getDataAugSn(), reviewer);

        // then — 409 없이 검수 행이 생기고, 생성 결과 컬럼은 웹훅이 남긴 값 그대로다.
        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
        LsDataAugRvw rvw = reviewRepository.findLatestByDataAugSn(seed.getDataAugSn()).orElseThrow();
        assertThat(rvw.getRvwSttsCd()).isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
        assertThat(rvw.getRvwId()).isEqualTo("1");
        assertThat(repository.findById(seed.getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_ACCEPTED);
    }

    @Test
    @DisplayName("생성성공으로_ACCEPTED_인_증강도_검수자가_반려할_수_있다")
    void webhookAcceptedAugmentIsStillRejectable() {
        LsDataAug seed = seedPending(LsDataAug.AUG_RAIN);
        seed.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        repository.saveAndFlush(seed);

        var resp = service.reject(seed.getDataAugSn(), "우천 질감이 부자연스럽다", reviewer);

        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAugRvw.STTS_REJECTED);
        assertThat(repository.findById(seed.getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_ACCEPTED);
    }

    // ============================================================
    // ★ 결과물이 없으면 결정할 수 없다 (2026-07-31 DEV_FIX HIGH — 등재 게이트 무력화 차단)
    // ============================================================

    /**
     * <b>회귀 방지의 핵심</b> — 결정은 결과물 실재를 전제로만 성립한다.
     *
     * <p>이 가드가 없으면 "요청 직후 승인 → 나중에 도착한 콜백이 파생영상 생성 → 등재 게이트 통과"
     * 순서가 성립해, 사람이 <b>결과 이미지를 한 번도 보지 않은</b> 파생영상이 작업목록에 오른다.
     * 상위 요구("이미지를 비교해 보고 사용 여부를 선택")를 정면으로 깨는 경로다.
     *
     * <p>검수 행이 <b>만들어지지도 않아야</b> 한다 — PENDING 검수 행만 남아도 화면 상태가 달라진다.
     */
    @Test
    @DisplayName("생성이_끝나지_않은_증강은_승인도_반려도_할_수_없다")
    void undecidableWhileGenerationInProgress() {
        // given — 요청만 접수된 상태(외부 콜백 전). 결과물이 아직 없다.
        LsDataAug requested = seedPending(LsDataAug.AUG_WINTER);
        assertThat(requested.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);

        // when / then — 승인·반려 모두 409 로 거부된다.
        assertThatThrownBy(() -> service.accept(requested.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    // 사유 구분 — "아직 생성되지 않음"(기다리면 된다)
                    assertThat(ce.getMessage()).contains("아직 생성되지 않았");
                });
        assertThatThrownBy(() -> service.reject(requested.getDataAugSn(), "사유", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 검수 행 자체가 생기지 않는다(게이트가 보는 ACCEPTED 행은 물론이고 PENDING 행도 없다).
        assertThat(reviewRepository.findLatestByDataAugSn(requested.getDataAugSn())).isEmpty();
    }

    /**
     * 생성이 <b>결과물 없이 종결</b>된 증강(실패 dead-letter / 사용자 취소)은 결정 대상이 아니다.
     * 기다려도 결과물이 생기지 않으므로 "아직 생성 중" 과 사유를 구분해 알린다.
     */
    @Test
    @DisplayName("생성_실패나_취소로_종결된_증강은_결정_대상이_아니다")
    void undecidableWhenGenerationTerminatedWithoutResult() {
        // given — ① 웹훅 실패 인계(REJECTED + dead-letter) ② 사용자 취소(CANCELED)
        LsDataAug failed = seedPending(LsDataAug.AUG_NIGHT);
        failed.applyGenerationResult(LsDataAug.STTS_REJECTED);
        failed.markDeadLetter();
        repository.saveAndFlush(failed);

        LsDataAug canceled = seedPending(LsDataAug.AUG_RAIN);
        canceled.markCanceled();
        repository.saveAndFlush(canceled);

        // when / then
        assertThatThrownBy(() -> service.accept(failed.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ce.getMessage()).contains("실패");
                });
        assertThatThrownBy(() -> service.reject(canceled.getDataAugSn(), "사유", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(reviewRepository.findLatestByDataAugSn(failed.getDataAugSn())).isEmpty();
        assertThat(reviewRepository.findLatestByDataAugSn(canceled.getDataAugSn())).isEmpty();
    }

    /**
     * <b>추출이 영구 실패(dead-letter)한 증강은 결정할 수 없다</b> — 2026-07-31 DEV_FIX MEDIUM(FIX-B).
     *
     * <p>비동기 확정(Phase A/B/C) 실패는 상태 컬럼을 건드리지 못한다 —
     * {@code applyGenerationResult} 가 PENDING 에서만 전이를 허용하므로
     * {@code AugmentExtractPersist.markAugProcessingFailed} 는 <b>{@code ACCEPTED} 를 그대로 둔 채</b>
     * {@code DEAD_LETTER_AT} 만 찍는다. 상태만 보던 구 판정은 그래서 <b>프레임 0건으로 영구 실패한
     * 파생</b>을 "결정 가능" 으로 통과시켰고, 채택하면 등재 게이트({@code acceptedReviewExists})까지
     * 통과해 <b>비교 이미지가 영영 없는 파생영상이 작업목록에 올랐다</b>.
     *
     * <p>같은 화면의 헤더는 이미 {@code isProcessingFailed} 로 FAILED 를 표시하고 있었으므로, 이
     * 가드가 없으면 한 화면에서 두 축이 상반된다.
     */
    @Test
    @DisplayName("추출_영구실패_증강은_결정할_수_없다")
    void deadLetteredAugmentCannotBeDecided() {
        // given — 웹훅은 성공(ACCEPTED)으로 종결했지만 비동기 프레임 확정이 영구 실패했다.
        LsDataAug failedAfterAccept = seedGenerated(LsDataAug.AUG_WINTER);
        failedAfterAccept.markDeadLetter();
        repository.saveAndFlush(failedAfterAccept);
        assertThat(failedAfterAccept.getAugProcSttsCd())
                .as("실패 마킹은 상태 컬럼을 건드리지 않는다 — 그래서 상태만 보는 판정이 뚫렸다")
                .isEqualTo(LsDataAug.STTS_ACCEPTED);

        // when / then — 채택·반려 모두 409. 기다려도 결과물이 생기지 않으므로 "생성 중" 이 아닌 실패 사유.
        assertThatThrownBy(() -> service.accept(failedAfterAccept.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> {
                    CustomException ce = (CustomException) e;
                    assertThat(ce.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ce.getMessage()).contains("실패");
                });
        assertThatThrownBy(() -> service.reject(failedAfterAccept.getDataAugSn(), "사유", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 등재 게이트가 보는 ACCEPTED 검수 행은 물론 PENDING 행도 생기지 않는다.
        assertThat(reviewRepository.findLatestByDataAugSn(failedAfterAccept.getDataAugSn())).isEmpty();

        // 두 축이 같은 사실을 말한다 — 실패로 못박힌 행은 "생성 성공" 으로도 보이지 않는다.
        LsDataAug reloaded = repository.findById(failedAfterAccept.getDataAugSn()).orElseThrow();
        assertThat(reloaded.isProcessingFailed()).isTrue();
        assertThat(reloaded.isGenerationSucceeded()).isFalse();
    }

    /**
     * 음성 케이스 — 가드가 <b>정상 동선까지</b> 막지 않는다는 반증. 생성이 성공하면 그 즉시 결정 가능하다.
     * (이 단언이 없으면 위 두 테스트는 "전부 막기" 로도 통과한다.)
     */
    @Test
    @DisplayName("생성이_성공하면_그_즉시_승인할_수_있다")
    void decidableOnceGenerationSucceeded() {
        LsDataAug requested = seedPending(LsDataAug.AUG_WINTER);
        assertThatThrownBy(() -> service.accept(requested.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class);

        // when — 외부 콜백이 도착해 생성 결과가 확정된다(같은 트랜잭션에서 파생영상이 만들어진다).
        requested.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        repository.saveAndFlush(requested);

        // then
        var resp = service.accept(requested.getDataAugSn(), reviewer);
        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
        assertThat(reviewRepository.findLatestByDataAugSn(requested.getDataAugSn()))
                .get()
                .extracting(LsDataAugRvw::getRvwSttsCd)
                .isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
    }

    @Test
    @DisplayName("AugmentReviewService_NOT_FOUND_404_미존재_dataAugSn")
    void acceptNonExistentReturnsNotFound() {
        assertThatThrownBy(() -> service.accept(99999L, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("해상도파생_행에_accept_또는_reject호출시_INVALID_INPUT으로_차단된다")
    void resolutionDerivativeRowBlockedFromReview() {
        // 해상도 파생(RES_ 접두)은 저작도구 내부 생성물 — 생성 즉시 ACCEPTED, 외부 검수 대상 아님.
        LsDataAug resAug = repository.save(
                LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_720P, "system"));

        assertThatThrownBy(() -> service.accept(resAug.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.reject(resAug.getDataAugSn(), "사유", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        // 차단만 — 상태는 ACCEPTED 그대로 유지(재처리 없음).
        LsDataAug after = repository.findById(resAug.getDataAugSn()).orElseThrow();
        assertThat(after.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
    }

    @Test
    @DisplayName("대표프레임이_실재하지_않으면_accept_reject가_CONFLICT로_거부되고_rawSn_0_센티널_이력이_생기지_않는다")
    void unresolvableRawSnRejectedWithoutSentinel() {
        // given — LS_DATA_AUG.SRC_SN 에는 프레임 FK 가 없어(V146 범위 밖) 프레임이 사라진 뒤에도
        //         증강 행이 남는다. 그 상태에서는 SRC_SN → RAW_SN 역해석이 실패한다.
        // ⚠ 작은 상수(구 값 500L)를 쓰면 안 된다 — LS_DATA_SRC 의 PK 는 IDENTITY 라 1 부터 순차
        //    발급되고, 테스트 JVM 하나가 컨테이너 DB 를 공유한다. 즉 <다른 클래스가 프레임을 몇 개
        //    시드했는가>에 따라 그 id 가 실재하게 되어, 이 클래스를 단독 실행하면 통과하고 넓은
        //    스코프로 함께 돌리면 실패하는 순서 의존이 된다(실제로 그렇게 깨졌다).
        //    IDENTITY 가 도달할 수 없는 값을 쓰고, 그럼에도 아래 isEmpty() 가드를 남겨 fail-closed 한다.
        Long danglingSrcSn = 9_000_000_500L; // LS_DATA_SRC 에 없는 프레임
        assertThat(srcRepository.findById(danglingSrcSn)).isEmpty();
        // 생성은 성공한(결과물 실재) 행이어야 한다 — 그래야 결정 사전조건을 통과해 <rawSn 역해석>
        // 단계까지 도달한다. PENDING 으로 두면 앞단 가드가 먼저 CONFLICT 를 내 이 테스트가 검증하려는
        // 경로를 가린다(같은 409 라 통과처럼 보이는 위양성).
        LsDataAug orphanForAccept = seedGeneratedOn(danglingSrcSn, LsDataAug.AUG_WINTER);
        LsDataAug orphanForReject = seedGeneratedOn(danglingSrcSn, LsDataAug.AUG_NIGHT);

        // when / then — 센티널(0L) 저장도, FK 위반 500 도 아니라 명시적 409 CONFLICT 로 거부한다.
        assertThatThrownBy(() -> service.accept(orphanForAccept.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.reject(orphanForReject.getDataAugSn(), "사유", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // 검수 이력 row 자체가 생기지 않는다(구 구현은 DATA_RAW_SN=0 행을 남겼다).
        assertThat(reviewRepository.findLatestByDataAugSn(orphanForAccept.getDataAugSn())).isEmpty();
        assertThat(reviewRepository.findLatestByDataAugSn(orphanForReject.getDataAugSn())).isEmpty();
        assertThat(reviewRepository.findAllByDataRawSnAndRvwSttsCd(0L, LsDataAugRvw.STTS_PENDING)).isEmpty();
    }

    /** 지정한 srcSn(정합 여부 무관) 위에 PENDING 증강 행을 만든다. */
    private LsDataAug seedPendingOn(Long targetSrcSn, String augType) {
        return repository.save(
                LsDataAug.createPending(targetSrcSn, augType, new BigDecimal("85.50"), "system"));
    }

    /** 지정한 srcSn 위에 <b>생성 성공(ACCEPTED)</b> 증강 행을 만든다. */
    private LsDataAug seedGeneratedOn(Long targetSrcSn, String augType) {
        LsDataAug aug = seedPendingOn(targetSrcSn, augType);
        aug.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        return repository.saveAndFlush(aug);
    }

    // ============================================================
    // 증강 결과 상태 집계 — /{jobId}/result (FE 결과 화면 실상태 반영)
    // ============================================================

    @Test
    @DisplayName("aggregateResultStatus_전부_종료된_aug면_COMPLETED_반환")
    void aggregateResultStatusCompletedWhenAllTerminal() {
        LsDataAug seed = seedPending(LsDataAug.AUG_WINTER);
        // 집계 축은 <생성 결과> 다 — 웹훅 성공 인계를 재현한다(검수 승인은 이 컬럼을 바꾸지 않는다).
        seed.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        repository.saveAndFlush(seed);

        // jobId 를 SRC_SN 으로 넘기는 구 경로 → findByOriginalRawSn 미매칭 후 srcSn 폴백으로 집계.
        assertThat(service.aggregateResultStatus(srcSn)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("aggregateResultStatus_dead_letter_aug면_FAILED_반환")
    void aggregateResultStatusFailedWhenDeadLetter() {
        LsDataAug seed = seedPending(LsDataAug.AUG_WINTER);
        seed.markDeadLetter();
        repository.save(seed);

        assertThat(service.aggregateResultStatus(srcSn)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("aggregateResultStatus_미종료_PENDING_aug면_PROCESSING_반환")
    void aggregateResultStatusProcessingWhenPending() {
        seedPending(LsDataAug.AUG_WINTER); // PENDING → REQUESTED → PROCESSING

        assertThat(service.aggregateResultStatus(srcSn)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("aggregateResultStatus_집계대상_aug가_전무하면_PROCESSING_반환")
    void aggregateResultStatusProcessingWhenNoRows() {
        assertThat(service.aggregateResultStatus(404404L)).isEqualTo("PROCESSING");
    }

    // ============================================================
    // 증강 잡 카드(영상 단위 그룹) 조회 — FE AugmentJob 계약 정합
    // ============================================================

    @Test
    @DisplayName("listAll_증강행이_0건이면_빈_Page_반환")
    void listAllEmptyWhenNoRows() {
        var page = service.listAll(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("listAll_한_영상에_여러_유형이면_types_distinct_집계_videoCount_1_status_REQUESTED")
    void listAllGroupsTypesDistinctPerVideo() {
        Long srcSn = 900L;
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT,  new BigDecimal("80.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_RAIN,   new BigDecimal("70.00"), "system"));

        var page = service.listAll(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        var job = page.getContent().get(0);
        assertThat(job.videoCount()).isEqualTo(1);
        assertThat(job.types()).containsExactly("WINTER", "NIGHT", "RAIN"); // AUG_ORDER 정렬
        assertThat(job.status()).isEqualTo(AugmentJobStatus.REQUESTED.name());
        assertThat(job.requestedAt()).isNotNull();
        assertThat(job.completedAt()).isNull();
        // LS_DATA_SRC 매핑 없음 → videoId/jobId 는 srcSn 폴백, jobId == videoId
        assertThat(job.videoId()).isEqualTo(srcSn);
        assertThat(job.jobId()).isEqualTo(job.videoId());
    }

    @Test
    @DisplayName("listAll_그룹내_dead_letter_1건이면_status_FAILED")
    void listAllFailedWhenAnyDeadLetter() {
        Long srcSn = 910L;
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        LsDataAug failed = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT, new BigDecimal("80.00"), "system"));
        failed.markDeadLetter();
        repository.save(failed);

        var page = service.listAll(PageRequest.of(0, 10));

        assertThat(page.getContent().get(0).status()).isEqualTo(AugmentJobStatus.FAILED.name());
    }

    @Test
    @DisplayName("listAll_전부_종료_상태면_status_COMPLETED_completedAt_채워짐")
    void listAllCompletedWhenAllTerminal() {
        Long srcSn = newFrame(); // accept/reject 대상 → 영상·프레임이 실재해야 한다
        LsDataAug a1 = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        LsDataAug a2 = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT, new BigDecimal("80.00"), "system"));
        // 집계 축은 <생성 결과> 다 — 웹훅 성공/실패 인계를 재현한다.
        a1.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        a2.applyGenerationResult(LsDataAug.STTS_REJECTED);
        repository.saveAllAndFlush(java.util.List.of(a1, a2));

        var page = service.listAll(PageRequest.of(0, 10));

        var job = page.getContent().get(0);
        assertThat(job.status()).isEqualTo(AugmentJobStatus.COMPLETED.name());
        assertThat(job.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("listAll_일부만_종료면_status_IN_PROGRESS_completedAt_null")
    void listAllInProgressWhenPartialTerminal() {
        Long srcSn = newFrame(); // accept 대상 → 영상·프레임이 실재해야 한다
        LsDataAug a1 = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT, new BigDecimal("80.00"), "system"));
        // 집계 축은 <생성 결과> 다 — 1건만 웹훅 인계 완료된 상태를 재현한다.
        a1.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        repository.saveAndFlush(a1);

        var page = service.listAll(PageRequest.of(0, 10));

        var job = page.getContent().get(0);
        assertThat(job.status()).isEqualTo(AugmentJobStatus.IN_PROGRESS.name());
        assertThat(job.completedAt()).isNull();
    }

    @Test
    @DisplayName("listAll_동일_REG_DT_다수그룹_페이지경계_중복없이_srcSn_DESC_결정적정렬")
    void listAllTieBreakDeterministicAcrossPages() {
        // given — 한 번의 증강 요청처럼 동일 REG_DT 를 공유하는 서로 다른 영상 3건(그룹)
        //         (MIN(REG_DT) 동률 → tie-break 없으면 OFFSET 페이징에서 중복/누락 발생)
        LocalDateTime sameRegDt = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        repository.save(tiedRow(950L, LsDataAug.AUG_WINTER, sameRegDt));
        repository.save(tiedRow(951L, LsDataAug.AUG_NIGHT, sameRegDt));
        repository.save(tiedRow(952L, LsDataAug.AUG_RAIN, sameRegDt));

        // when — size=2 로 두 페이지 분할 조회
        var page0 = service.listAll(PageRequest.of(0, 2));
        var page1 = service.listAll(PageRequest.of(1, 2));

        // then — 총 3그룹, srcSn DESC 결정적 정렬, 페이지 경계 중복/누락 0
        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getContent()).extracting(j -> j.videoId())
                .containsExactly(952L, 951L); // srcSn DESC (매핑 부재 → videoId == srcSn)
        assertThat(page1.getContent()).extracting(j -> j.videoId())
                .containsExactly(950L);

        var seen = new java.util.HashSet<Long>();
        page0.getContent().forEach(j -> seen.add(j.videoId()));
        long dupOnPage1 = page1.getContent().stream().filter(j -> !seen.add(j.videoId())).count();
        assertThat(dupOnPage1).as("페이지 경계 중복").isZero();
        assertThat(seen).containsExactlyInAnyOrder(950L, 951L, 952L); // 누락 0
    }

    /** tie-break 검증용 — REG_DT 를 명시 고정한 PENDING 증강 행. */
    private LsDataAug tiedRow(Long srcSn, String augType, LocalDateTime regDt) {
        return LsDataAug.builder()
                .srcSn(srcSn)
                .augTypeCd(augType)
                .augProcSttsCd(LsDataAug.STTS_PENDING)
                .lblIntgrtPct(new BigDecimal("50.00"))
                .regDt(regDt)
                .regUserNo("system")
                .build();
    }

    @Test
    @DisplayName("findBySource_srcSn_필터시_해당_영상_잡카드만_Page로_반환")
    void findBySourceFiltersToSingleVideoJob() {
        Long srcA = 940L;
        Long srcB = 941L;
        repository.save(LsDataAug.createPending(srcA, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(srcA, LsDataAug.AUG_RAIN,   new BigDecimal("70.00"), "system"));
        repository.save(LsDataAug.createPending(srcB, LsDataAug.AUG_NIGHT,  new BigDecimal("80.00"), "system"));

        var page = service.findBySource(srcA);

        assertThat(page.getContent()).hasSize(1);
        var job = page.getContent().get(0);
        assertThat(job.videoId()).isEqualTo(srcA);
        assertThat(job.types()).containsExactly("WINTER", "RAIN"); // AUG_ORDER 정렬
    }

    // ============================================================
    // Phase 3 — 해상도 파생(RESL_) 이력 노출 + 증강검수 오염 방지 (SFR-06-03)
    // ============================================================

    @Test
    @DisplayName("해상도_파생_RESL_행이_증강이력_listAll에_노출된다")
    void resolutionDerivativeShownInListAll() {
        Long srcSn = 960L;
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_720P, "system"));

        var page = service.listAll(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        var job = page.getContent().get(0);
        // 검수 대상 증강(WINTER)은 types 에, 해상도 파생(RESL_)은 resolutionTypes 에 분리 노출
        assertThat(job.types()).containsExactly("WINTER");
        assertThat(job.resolutionTypes()).containsExactly("RESL_720P");
    }

    @Test
    @DisplayName("해상도만_있는_영상도_이력에_노출된다")
    void resolutionOnlyVideoShownInHistory() {
        Long srcSn = 961L;
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_1080P, "system"));

        var page = service.listAll(PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        var job = page.getContent().get(0);
        assertThat(job.videoId()).isEqualTo(srcSn);
        assertThat(job.videoCount()).isEqualTo(1);
        // 검수 대상 증강 없음 → types 비어있고, 해상도 파생만 존재
        assertThat(job.types()).isEmpty();
        assertThat(job.resolutionTypes()).containsExactly("RESL_1080P");
        // 검수 대상 없음 → 증강검수 진행상태는 미결(대기) 없음 = COMPLETED (파생 생성 완료)
        assertThat(job.status()).isEqualTo(AugmentJobStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("해상도_파생이_증강_집계에_포함되어_카운트된다")
    void resolutionRowIncludedInAggregateStatus() {
        // given — PENDING WINTER + ACCEPTED RESL_720P 같은 srcSn 그룹
        Long srcSn = 962L;
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_720P, "system"));

        // when
        var page = service.listAll(PageRequest.of(0, 10));

        // then — RESL_720P(ACCEPTED=terminal)가 집계에 포함되어, WINTER(PENDING)만 남은 미종료와 합쳐
        //        REQUESTED 가 아닌 IN_PROGRESS 로 카운트된다(집계에 해상도 파생이 반영됨).
        var job = page.getContent().get(0);
        assertThat(job.status()).isEqualTo(AugmentJobStatus.IN_PROGRESS.name());
        assertThat(job.completedAt()).isNull();
        // FE 구분은 유지 — 해상도 파생은 resolutionTypes 로 별도 노출.
        assertThat(job.resolutionTypes()).containsExactly("RESL_720P");
        assertThat(job.types()).containsExactly("WINTER");
    }

    @Test
    @DisplayName("해상도만_있는_영상은_집계상_COMPLETED로_카운트된다")
    void resolutionOnlyVideoCountedAsCompleted() {
        // given — RESL_ 파생만 존재(성공 파생, ACCEPTED=terminal). 고아 파생은 releaseReservedAug 로
        //         예약 aug 행이 삭제되므로 ACCEPTED 로 남는 RESL_ 행은 성공 파생뿐이다.
        Long srcSn = 966L;
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_1080P, "system"));
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_720P, "system"));

        // when
        var page = service.listAll(PageRequest.of(0, 10));

        // then — 전부 terminal → COMPLETED, 파생 완료로 집계됨. completedAt 채워짐(요청 일시 폴백 포함).
        var job = page.getContent().get(0);
        assertThat(job.status()).isEqualTo(AugmentJobStatus.COMPLETED.name());
        assertThat(job.completedAt()).isNotNull();
        assertThat(job.resolutionTypes()).containsExactly("RESL_1080P", "RESL_720P");
        assertThat(job.types()).isEmpty();
    }

    @Test
    @DisplayName("해상도_파생_예약직후_finalize전에는_PENDING이라_집계가_COMPLETED가_아니다")
    void reservedResolutionPendingGroupNotCompleted() {
        // given — 예약만 커밋되고 finalize 전(in-flight) 상태 = createResolutionPending(생성 중, PENDING).
        //         파생 RAW 는 아직 PENDING(생성 중)이므로 집계가 조기 COMPLETED 로 오표기되면 안 된다.
        Long srcSn = 965L;
        repository.save(LsDataAug.createResolutionPending(srcSn, LsDataAug.AUG_RESL_720P, "system"));

        // when
        var job = service.listAll(PageRequest.of(0, 10)).getContent().get(0);

        // then — PENDING(non-terminal)만 존재 → COMPLETED 아님(REQUESTED), completedAt null (오표기 방지).
        assertThat(job.status()).isNotEqualTo(AugmentJobStatus.COMPLETED.name());
        assertThat(job.status()).isEqualTo(AugmentJobStatus.REQUESTED.name());
        assertThat(job.completedAt()).isNull();
        assertThat(job.resolutionTypes()).containsExactly("RESL_720P");
    }

    @Test
    @DisplayName("해상도_파생_finalize성공후_ACCEPTED로_전이되어_집계가_COMPLETED다")
    void resolutionPendingTransitionsToAcceptedThenCompleted() {
        // given — 예약(PENDING) 행을 finalize 성공 확정 전이(markResolutionGenerated)로 ACCEPTED 로 만든다.
        Long srcSn = 968L;
        LsDataAug reserved = repository.save(
                LsDataAug.createResolutionPending(srcSn, LsDataAug.AUG_RESL_720P, "system"));
        assertThat(reserved.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);

        reserved.markResolutionGenerated(); // finalize 성공 경로 전이
        repository.save(reserved);

        // when
        var job = service.listAll(PageRequest.of(0, 10)).getContent().get(0);

        // then — ACCEPTED(terminal)만 존재 → COMPLETED(파생 생성 완료).
        assertThat(job.status()).isEqualTo(AugmentJobStatus.COMPLETED.name());
        assertThat(job.completedAt()).isNotNull();
        assertThat(job.resolutionTypes()).containsExactly("RESL_720P");
    }

    @Test
    @DisplayName("WINTER_PENDING과_RESL_ACCEPTED_혼합그룹의_집계상태")
    void mixedPendingAndResolutionAcceptedAggregateStatus() {
        // given — WINTER(PENDING) + NIGHT(ACCEPTED) + RESL_720P(ACCEPTED) 혼합 그룹
        Long srcSn = newFrame(); // NIGHT accept 대상 → 영상·프레임이 실재해야 한다
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        LsDataAug night = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT, new BigDecimal("80.00"), "system"));
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_720P, "system"));
        // 집계 축은 <생성 결과> 다 — 웹훅 성공 인계를 재현한다. (검수 승인은 이 컬럼을 바꾸지 않으므로
        //  accept 로는 이 그룹의 종결 수가 변하지 않는다 — 축 분리 이후 accept 를 쓰면 픽스처가 무의미해진다.)
        night.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        repository.saveAndFlush(night);

        // when
        var page = service.listAll(PageRequest.of(0, 10));

        // then — 종료(NIGHT ACCEPTED + RESL_720P ACCEPTED)=2, 미종료(WINTER PENDING)=1 → IN_PROGRESS.
        //        전부 종료가 아니므로 completedAt 은 null.
        var job = page.getContent().get(0);
        assertThat(job.status()).isEqualTo(AugmentJobStatus.IN_PROGRESS.name());
        assertThat(job.completedAt()).isNull();
    }

    @Test
    @DisplayName("해상도_이력항목은_검수액션_불가로_식별된다")
    void resolutionItemIdentifiedAsNonReviewable() {
        Long srcSn = 963L;
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_480P, "system"));

        var job = service.listAll(PageRequest.of(0, 10)).getContent().get(0);

        // FE 가 비-검수 렌더링할 수 있도록 해상도 파생 타입이 resolutionTypes 로 분리 식별된다.
        assertThat(job.resolutionTypes()).isNotEmpty();
        assertThat(job.resolutionTypes()).allMatch(t -> t.startsWith("RESL_"));
        assertThat(job.types()).noneMatch(t -> t.startsWith("RESL_"));
    }

    @Test
    @DisplayName("이력_정렬에_RESL_프리셋이_반영된다")
    void resolutionPresetSortedByAugOrder() {
        Long srcSn = 964L;
        // 입력 순서를 뒤섞어 저장 → AUG_ORDER(1080→720→480) 정렬 검증
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_480P, "system"));
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_1080P, "system"));
        repository.save(LsDataAug.createResolutionAccepted(srcSn, LsDataAug.AUG_RESL_720P, "system"));

        var job = service.listAll(PageRequest.of(0, 10)).getContent().get(0);

        assertThat(job.resolutionTypes()).containsExactly("RESL_1080P", "RESL_720P", "RESL_480P");
    }

    @Test
    @DisplayName("기존_증강3종_이력_표시가_변하지_않는다")
    void existingThreeAugmentTypesUnchanged() {
        Long srcSn = 965L;
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT,  new BigDecimal("80.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_RAIN,   new BigDecimal("70.00"), "system"));

        var job = service.listAll(PageRequest.of(0, 10)).getContent().get(0);

        assertThat(job.types()).containsExactly("WINTER", "NIGHT", "RAIN");
        assertThat(job.resolutionTypes()).isEmpty();
        assertThat(job.status()).isEqualTo(AugmentJobStatus.REQUESTED.name());
    }
}
