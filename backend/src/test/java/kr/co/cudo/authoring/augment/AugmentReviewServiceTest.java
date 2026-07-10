package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.dto.AugmentJobStatus;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
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

    private TokenClaims reviewer;
    private TokenClaims worker;

    @BeforeEach
    void setup() {
        reviewer = new TokenClaims("1",   Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        worker   = new TokenClaims("100", Role.WORKER,   Channel.INTERNAL, Instant.now().plusSeconds(3600));
        reviewRepository.deleteAll();
        repository.deleteAll();
    }

    private LsDataAug seedPending(String augType) {
        LsDataAug aug = LsDataAug.createPending(500L, augType, new BigDecimal("85.50"), "system");
        return repository.save(aug);
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
    @DisplayName("AugmentReviewService_REVIEWER_accept시_AUG_PROC_STTS_CD_ACCEPTED_+_LS_DATA_AUG_RVW_row_INSERT")
    void reviewerAcceptUpdatesStatusAndAudit() {
        LsDataAug seed = seedPending(LsDataAug.AUG_WINTER);

        var resp = service.accept(seed.getDataAugSn(), reviewer);

        // 응답 — LS_DATA_AUG_RVW 의 ACCEPTED status / 검수자 / 시각
        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(resp.decisionUserNo()).isEqualTo("1");
        assertThat(resp.decisionAt()).isNotNull();

        // LsDataAug.augProcSttsCd 동기 갱신 (DB 설계서 라인 162-169 호환)
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
        LsDataAug seed = seedPending(LsDataAug.AUG_NIGHT);

        var resp = service.reject(seed.getDataAugSn(), "야간 명도 조정 부정확", reviewer);

        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(resp.rejectReason()).isEqualTo("야간 명도 조정 부정확");

        // LsDataAug.augProcSttsCd 동기 갱신
        LsDataAug after = repository.findById(seed.getDataAugSn()).orElseThrow();
        assertThat(after.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);

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
        LsDataAug seed = seedPending(LsDataAug.AUG_NIGHT);

        assertThatThrownBy(() -> service.reject(seed.getDataAugSn(), "", reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("AugmentReviewService_이미_ACCEPTED인_AUG_재처리시_409_CONFLICT")
    void duplicateAcceptReturnsConflict() {
        LsDataAug seed = seedPending(LsDataAug.AUG_RAIN);
        service.accept(seed.getDataAugSn(), reviewer);

        assertThatThrownBy(() -> service.accept(seed.getDataAugSn(), reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("AugmentReviewService_NOT_FOUND_404_미존재_dataAugSn")
    void acceptNonExistentReturnsNotFound() {
        assertThatThrownBy(() -> service.accept(99999L, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
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
        Long srcSn = 920L;
        LsDataAug a1 = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        LsDataAug a2 = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT, new BigDecimal("80.00"), "system"));
        service.accept(a1.getDataAugSn(), reviewer);
        service.reject(a2.getDataAugSn(), "야간 명도 조정 부정확", reviewer);

        var page = service.listAll(PageRequest.of(0, 10));

        var job = page.getContent().get(0);
        assertThat(job.status()).isEqualTo(AugmentJobStatus.COMPLETED.name());
        assertThat(job.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("listAll_일부만_종료면_status_IN_PROGRESS_completedAt_null")
    void listAllInProgressWhenPartialTerminal() {
        Long srcSn = 930L;
        LsDataAug a1 = repository.save(
                LsDataAug.createPending(srcSn, LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        repository.save(LsDataAug.createPending(srcSn, LsDataAug.AUG_NIGHT, new BigDecimal("80.00"), "system"));
        service.accept(a1.getDataAugSn(), reviewer);

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
}
