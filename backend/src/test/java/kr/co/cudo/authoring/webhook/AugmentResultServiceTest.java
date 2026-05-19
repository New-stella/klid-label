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
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    @DisplayName("AugmentResultService_동시_webhook_인계_시_DataIntegrityViolation_멱등_흡수")
    void concurrentWebhookRace_absorbsDataIntegrityViolation() throws Exception {
        // Phase 4 — DeidentifyResultService / VlmResultService 와 동일한 race 흡수 패턴 적용.
        // 동시 webhook 인계 시 findByIdempotencyKey 모두 empty → 두 트랜잭션이 신규 save
        // 시도 → 한쪽이 DataIntegrityViolationException. 서비스는 catch 후 재조회하여 멱등 흡수해야 함.
        ledger.recordIssued("K-A-RACE", "EXT-A-RACE");

        LsDataAug raceWinner = newAug(50L, "WINTER", LsDataAug.STTS_PENDING);
        when(augRepository.findById(50L)).thenReturn(Optional.of(raceWinner));

        // 첫 호출: empty (둘 다 신규 갱신 시도)
        // 두 번째 호출: 다른 트랜잭션이 이미 적용한 row 반환 (race 흡수 경로)
        when(augRepository.findByIdempotencyKey("K-A-RACE"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(raceWinner));

        // 첫 save 호출 시 UNIQUE 위반 시뮬레이션, 두 번째 save 는 정상 (race 흡수 후)
        when(augRepository.save(any(LsDataAug.class)))
                .thenThrow(new DataIntegrityViolationException("UNIQUE violation"))
                .thenReturn(raceWinner);

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-RACE", "EXT-A-RACE", "SUCCESS", 50L, "WINTER",
                "/storage/augment/50.mp4", List.of());

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        // race 흡수 후 ACCEPTED 로 갱신
        assertThat(raceWinner.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        // save 두 번 호출 — 실패 + 재조회 후 갱신
        verify(augRepository, times(2)).save(any(LsDataAug.class));
        // 멱등 마킹 정상 수행
        assertThat(ledger.isProcessed("K-A-RACE")).isTrue();
    }

    @Test
    @DisplayName("AugmentResultService_동일_idempotencyKey_재인계_시_단일_LsDataAug_갱신")
    void sameIdempotencyKey_singleRowUpdate() throws Exception {
        // Phase 4 — findByIdempotencyKey 가 기존 row 를 반환하면 동일 row 갱신 (신규 save 없음).
        ledger.recordIssued("K-A-SAME", "EXT-A-SAME");

        LsDataAug existing = newAug(51L, "RAIN", LsDataAug.STTS_PENDING);
        existing.assignIdempotencyKey("K-A-SAME");
        when(augRepository.findById(51L)).thenReturn(Optional.of(existing));
        when(augRepository.findByIdempotencyKey("K-A-SAME")).thenReturn(Optional.of(existing));
        when(augRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));

        AugmentResultRequest req = new AugmentResultRequest(
                "K-A-SAME", "EXT-A-SAME", "SUCCESS", 51L, "RAIN",
                "/storage/augment/51.mp4", List.of());

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(existing.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_ACCEPTED);
        // 단일 save 호출 — UNIQUE 위반 없음
        verify(augRepository, times(1)).save(any(LsDataAug.class));
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
