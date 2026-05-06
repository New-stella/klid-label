package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
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

    private TokenClaims reviewer;
    private TokenClaims worker;

    @BeforeEach
    void setup() {
        reviewer = new TokenClaims("1",   Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));
        worker   = new TokenClaims("100", Role.WORKER,   Channel.INTERNAL, Instant.now().plusSeconds(3600));
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
    @DisplayName("AugmentReviewService_REVIEWER_accept시_AUG_PROC_STTS_CD_ACCEPTED_+_검수자_USER_ID_+_일시_기록")
    void reviewerAcceptUpdatesStatusAndAudit() {
        LsDataAug seed = seedPending(LsDataAug.AUG_WINTER);

        var resp = service.accept(seed.getDataAugSn(), reviewer);

        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(resp.decisionUserNo()).isEqualTo("1");
        assertThat(resp.decisionAt()).isNotNull();

        LsDataAug after = repository.findById(seed.getDataAugSn()).orElseThrow();
        assertThat(after.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(after.getDecisionUserNo()).isEqualTo("1");
        assertThat(after.getDecisionAt()).isNotNull();
    }

    @Test
    @DisplayName("AugmentReviewService_REJECTED_시_사유_저장")
    void rejectStoresReason() {
        LsDataAug seed = seedPending(LsDataAug.AUG_NIGHT);

        var resp = service.reject(seed.getDataAugSn(), "야간 명도 조정 부정확", reviewer);

        assertThat(resp.augProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(resp.rejectReason()).isEqualTo("야간 명도 조정 부정확");

        LsDataAug after = repository.findById(seed.getDataAugSn()).orElseThrow();
        assertThat(after.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(after.getRejectReason()).isEqualTo("야간 명도 조정 부정확");
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
