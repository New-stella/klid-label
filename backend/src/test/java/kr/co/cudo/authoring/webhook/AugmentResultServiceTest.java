package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.InMemoryWebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AugmentResultServiceTest {

    @Mock LsDataAugRepository augRepository;
    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();

    private AugmentResultService service;

    @BeforeEach
    void setup() {
        service = new AugmentResultService(augRepository, ledger);
        ledger.clear();
    }

    @Test
    @DisplayName("POST_v1_augments_result_미발급_idempotencyKey_시_401")
    void unknownIdempotencyKey_throws401() {
        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-UNK", "EXT-1", "SUCCESS", 1L, "WINTER",
                "/storage/augment/1.mp4", List.of());

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(augRepository, never()).findById(any());
    }

    @Test
    @DisplayName("AugmentResultService_적재_시_LS_DATA_AUG_상태_ACCEPTED_갱신")
    void appliesAcceptedStatus() throws Exception {
        ledger.recordIssued("K-A-OK", "EXT-AO");
        LsDataAug aug = newAug(10L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findById(10L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-OK", "EXT-AO", "SUCCESS", 10L, "WINTER",
                "/storage/augment/10.mp4", List.of());

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(ledger.isProcessed("K-A-OK")).isTrue();
    }

    @Test
    @DisplayName("AugmentResultService_FAILED_status_시_LS_DATA_AUG_REJECTED")
    void failedStatus_mapsToRejected() throws Exception {
        ledger.recordIssued("K-A-FAIL", "EXT-AF");
        LsDataAug aug = newAug(11L, "NIGHT", LsDataAug.STTS_PENDING);
        when(augRepository.findById(11L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-FAIL", "EXT-AF", "FAILED", 11L, "NIGHT",
                null, null);

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
    }

    @Test
    @DisplayName("POST_v1_augments_result_idempotencyKey_재인계_시_200_OK_멱등")
    void replay_returnsFalse() throws Exception {
        ledger.recordIssued("K-A-DUP", "EXT-AD");
        LsDataAug aug = newAug(12L, "RAIN", LsDataAug.STTS_PENDING);
        when(augRepository.findById(12L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-DUP", "EXT-AD", "SUCCESS", 12L, "RAIN",
                null, null);

        assertThat(service.handle(req)).isTrue();
        assertThat(service.handle(req)).isFalse();
    }

    @Test
    @DisplayName("augType_불일치_시_409_CONFLICT")
    void augTypeMismatch_returns409() throws Exception {
        ledger.recordIssued("K-A-MISMATCH", "EXT-AM");
        LsDataAug aug = newAug(13L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findById(13L)).thenReturn(Optional.of(aug));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-MISMATCH", "EXT-AM", "SUCCESS", 13L, "NIGHT",
                null, null);

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private LsDataAug newAug(Long sn, String type, String status) throws Exception {
        LsDataAug aug = LsDataAug.createPending(1L, type, BigDecimal.valueOf(0.95), "registrar");
        Field f = LsDataAug.class.getDeclaredField("dataAugSn");
        f.setAccessible(true);
        f.set(aug, sn);
        if (!LsDataAug.STTS_PENDING.equals(status)) {
            // forcibly override
            Field s = LsDataAug.class.getDeclaredField("augProcSttsCd");
            s.setAccessible(true);
            s.set(aug, status);
        }
        return aug;
    }
}
