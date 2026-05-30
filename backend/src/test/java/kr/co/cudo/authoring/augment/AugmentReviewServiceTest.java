package kr.co.cudo.authoring.augment;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

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
}
