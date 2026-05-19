package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.DeidentifyResultRequest;
import kr.co.cudo.authoring.webhook.idempotency.InMemoryWebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import kr.co.cudo.authoring.webhook.service.DeidentifyResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class DeidentifyResultServiceTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDeidentProcLogRepository procLogRepository;
    private final WebhookIdempotencyLedger ledger = new InMemoryWebhookIdempotencyLedger();

    private DeidentifyResultService service;

    @BeforeEach
    void setup() {
        service = new DeidentifyResultService(videoRepository, procLogRepository, ledger);
        ledger.clear();
    }

    @Test
    @DisplayName("POST_v1_deidentify_result_미발급_idempotencyKey_시_401")
    void unknownIdempotencyKey_throws401() {
        DeidentifyResultRequest req = new DeidentifyResultRequest(
                "K-UNKNOWN", "EXT-1", "SUCCESS", 100L,
                "/storage/deidentified/v100.mp4", List.of());

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(videoRepository, never()).findById(any());
    }

    @Test
    @DisplayName("DeidentifyResultService_적재_시_LS_DATA_RAW_DE_IDNTF_YN_Y_토글")
    void appliesDeidentifiedFlagYOnSuccess() throws Exception {
        ledger.recordIssued("K-OK", "EXT-OK");
        LsDataRaw raw = newRaw(100L, "/storage/raw/100.mp4");
        when(videoRepository.findById(100L)).thenReturn(Optional.of(raw));

        DeidentifyResultRequest req = new DeidentifyResultRequest(
                "K-OK", "EXT-OK", "SUCCESS", 100L,
                "/storage/deidentified/100.mp4", List.of());

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        assertThat(raw.getDeIdntfYn()).isEqualTo("Y");
        verify(procLogRepository).save(any(LsDeidentProcLog.class));
        assertThat(ledger.isProcessed("K-OK")).isTrue();
    }

    @Test
    @DisplayName("POST_v1_deidentify_result_idempotencyKey_재인계_시_200_OK_멱등")
    void replay_returnsFalseWithoutSave() throws Exception {
        ledger.recordIssued("K-DUP", "EXT-D");
        LsDataRaw raw = newRaw(101L, "/storage/raw/101.mp4");
        when(videoRepository.findById(101L)).thenReturn(Optional.of(raw));

        DeidentifyResultRequest req = new DeidentifyResultRequest(
                "K-DUP", "EXT-D", "SUCCESS", 101L,
                "/storage/deidentified/101.mp4", List.of());

        boolean first = service.handle(req);
        boolean second = service.handle(req);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        // 두 번째 호출은 적재 스킵
        verify(procLogRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("DeidentifyResultService_동일_externalJobId_재인계_시_단일_LsDeidentProcLog_갱신")
    void sameExternalJobIdUpsertsSingleRow() throws Exception {
        // DEV_FIX H-3: 동일 externalJobId 재인계 시 findByExternalJobId 로 기존 row 조회 → 갱신
        ledger.recordIssued("K-UPSERT", "EXT-UPSERT");
        LsDataRaw raw = newRaw(300L, "/storage/raw/300.mp4");
        when(videoRepository.findById(300L)).thenReturn(Optional.of(raw));

        LsDeidentProcLog existingRow = LsDeidentProcLog.request(
                300L, "EXT-UPSERT", "/storage/raw/300.mp4", "webhook", "EXT-UPSERT");
        when(procLogRepository.findByExternalJobId("EXT-UPSERT"))
                .thenReturn(Optional.of(existingRow));

        DeidentifyResultRequest req = new DeidentifyResultRequest(
                "K-UPSERT", "EXT-UPSERT", "SUCCESS", 300L,
                "/storage/deidentified/300.mp4", List.of());

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        // 단일 row save 호출 — 신규 LsDeidentProcLog 생성이 아닌 기존 row 갱신
        verify(procLogRepository, org.mockito.Mockito.times(1)).save(existingRow);
        // 기존 row 가 SUCCEEDED 로 갱신되었어야 함
        assertThat(existingRow.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(existingRow.getDeIdntfFilePath()).isEqualTo("/storage/deidentified/300.mp4");
    }

    @Test
    @DisplayName("deidentifiedFilePath_사설망_URL_시_400_SSRF")
    void privateNetworkUrl_throwsInvalidInput() throws Exception {
        ledger.recordIssued("K-SSRF", "EXT-S");

        DeidentifyResultRequest req = new DeidentifyResultRequest(
                "K-SSRF", "EXT-S", "SUCCESS", 100L,
                "http://169.254.169.254/latest/meta-data/", List.of());

        assertThatThrownBy(() -> service.handle(req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("DeidentifyResultService_동시_webhook_인계_시_DataIntegrityViolation_멱등_흡수")
    void concurrentWebhookRace_absorbsDataIntegrityViolation() throws Exception {
        // DEV_FIX 2차 H-3 race: 동시 webhook 인계 시 findByExternalJobId 모두 empty →
        // 두 트랜잭션이 동시에 신규 save 시도 → 한쪽이 DataIntegrityViolationException.
        // 서비스는 catch 후 재조회하여 멱등 흡수해야 함 (500 노출 금지).
        ledger.recordIssued("K-RACE", "EXT-RACE");
        LsDataRaw raw = newRaw(400L, "/storage/raw/400.mp4");
        when(videoRepository.findById(400L)).thenReturn(Optional.of(raw));

        // 첫 조회: empty (다른 트랜잭션이 먼저 insert 하기 전)
        // 두 번째 조회: 다른 트랜잭션이 이미 insert 한 row 반환 (race 흡수 경로)
        LsDeidentProcLog raceWinner = LsDeidentProcLog.request(
                400L, "EXT-RACE", "/storage/raw/400.mp4", "webhook", "EXT-RACE");
        when(procLogRepository.findByExternalJobId("EXT-RACE"))
                .thenReturn(Optional.empty())   // 첫 호출: 둘 다 신규 생성 시도
                .thenReturn(Optional.of(raceWinner)); // 재조회: 상대가 먼저 insert 한 row

        // 첫 save 호출 시 UNIQUE 위반 시뮬레이션, 두 번째 save 는 정상 (race 흡수 후)
        when(procLogRepository.save(any(LsDeidentProcLog.class)))
                .thenThrow(new DataIntegrityViolationException("UNIQUE violation"))
                .thenReturn(raceWinner);

        DeidentifyResultRequest req = new DeidentifyResultRequest(
                "K-RACE", "EXT-RACE", "SUCCESS", 400L,
                "/storage/deidentified/400.mp4", List.of());

        boolean applied = service.handle(req);

        assertThat(applied).isTrue();
        // race 흡수 경로 — 재조회한 raceWinner 가 SUCCEEDED 로 갱신되어야 함
        assertThat(raceWinner.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(raceWinner.getDeIdntfFilePath()).isEqualTo("/storage/deidentified/400.mp4");
        // save 는 두 번 호출됨 (실패 + 재조회 후 갱신)
        verify(procLogRepository, org.mockito.Mockito.times(2)).save(any(LsDeidentProcLog.class));
        // 멱등 마킹은 정상 수행
        assertThat(ledger.isProcessed("K-RACE")).isTrue();
    }

    /** 테스트용 LsDataRaw 생성 (protected ctor 우회). */
    private LsDataRaw newRaw(Long rawSn, String filePath) throws Exception {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-1", "EVT-1", "LCL-1",
                LsDataRaw.PRVC_TYPE_PRVC, filePath, null, 30);
        Field f = LsDataRaw.class.getDeclaredField("rawSn");
        f.setAccessible(true);
        f.set(raw, rawSn);
        return raw;
    }
}
