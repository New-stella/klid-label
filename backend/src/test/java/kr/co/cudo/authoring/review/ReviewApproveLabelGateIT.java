package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.dto.ApproveRequest;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-ISSUE-04(a) — 검수 승인 사전 게이트 통합 테스트 (실 DB: Testcontainers PostgreSQL).
 *
 * <p>실측 결함: 프레임 1건·라벨 0건 영상(rawSn=4)을 승인하면 200 APPROVED 가 되고, 스냅샷은
 * {@code created=0}, AFTER_COMMIT export 는 {@code nothing produced — marked FAILED} 로 끝나
 * 데이터마트 {@code V_COMPLETED_VIDEO} 에 {@code OUTPUT_PATH_NM}(V174, 구 {@code EXPORT_PATH_NM})이
 * NULL 인 빈 행이 남았다.
 *
 * <p>게이트가 정상 승인을 막지 않는지(회귀)도 같은 클래스에서 고정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ReviewApproveLabelGateIT {

    @Autowired private ReviewService reviewService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private ReviewRepository statusRepository;
    @Autowired private LsTaskEventLogRepository eventLogRepository;

    private final TransactionTemplate tx;

    ReviewApproveLabelGateIT(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    private TokenClaims reviewer() {
        return new TokenClaims("9001", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    /** IN_REVIEW 상태의 영상 + 프레임 1건을 만든다. withLabel 이면 라벨 1건도 함께 만든다. */
    private long seedVideoInReview(boolean withLabel) {
        return tx.execute(s -> {
            LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                    "CLIP-APV-" + System.nanoTime(), "CCTV-1", "EVT-A", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
            Long rawSn = raw.getRawSn();
            LsDataSrc frame = srcRepository.save(
                    LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
            if (withLabel) {
                labelRepository.save(LsDataLbl.createManual(frame.getSrcSn(), "BBOX", null,
                        "person", "[[1.0,1.0],[2.0,2.0]]", "100"));
            }
            LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
            status.transitionTo(LsRawDataStatus.STTS_PENDING);
            status.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
            statusRepository.save(status);
            return rawSn;
        });
    }

    @Test
    @DisplayName("라벨_0건_영상_승인시_409_이고_상태가_유지됨")
    void approveWithoutLabelsRejectedAndStatusUnchanged() {
        long rawSn = seedVideoInReview(false);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer())))
                .isInstanceOf(CustomException.class)
                // DEV_FIX H6 — 상태코드는 409 그대로이고 사유 전용 코드(REVIEW_NO_LABEL)로 세분화됐다.
                //   화면이 '동시 승인 충돌'(CONFLICT)과 '라벨 0건'을 구분할 수 있어야 하기 때문이다.
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_NO_LABEL);
        assertThat(ErrorCode.REVIEW_NO_LABEL.status().value()).isEqualTo(409);

        // 상태 전이 이전에 거부되므로 IN_REVIEW 가 그대로다(부분 전이·빈 스냅샷·빈 export 없음).
        String status = tx.execute(s -> statusRepository.findByRawDataId(rawSn)
                .orElseThrow().getDataSttsCd());
        assertThat(status).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }

    @Test
    @DisplayName("라벨이_있는_영상_승인은_기존대로_성공함")
    void approveWithLabelsStillSucceeds() {
        long rawSn = seedVideoInReview(true);

        tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer()));

        String status = tx.execute(s -> statusRepository.findByRawDataId(rawSn)
                .orElseThrow().getDataSttsCd());
        assertThat(status).isEqualTo(LsRawDataStatus.STTS_APPROVED);
    }

    // ------------------------------------------------------------------ DEV_FIX H6 (negative sample)

    @Test
    @DisplayName("검수자가_라벨없음을_명시_확인하면_negative_sample_승인이_가능하고_이력에_남는다")
    void negativeSampleApprovableWithExplicitConfirmation() {
        long rawSn = seedVideoInReview(false);

        // 확인 없이 = 기존대로 409 (기본 게이트 유지). 위 테스트가 이미 고정하므로 여기선 확인 경로만 본다.
        tx.executeWithoutResult(s ->
                reviewService.approve(rawSn, new ApproveRequest(true), reviewer()));

        String status = tx.execute(s -> statusRepository.findByRawDataId(rawSn)
                .orElseThrow().getDataSttsCd());
        assertThat(status).isEqualTo(LsRawDataStatus.STTS_APPROVED);

        // 감사 가능성 — 기존 통합 이벤트 로그(LS_TASK_EVNT_LOG)의 APPROVE 이벤트에 확인 사유가 남는다
        //   (누가=ACTOR_USER_NO, 언제=OCRN_DT, 무엇을=RAW_DATA_ID). 신규 테이블 없이 기존 메커니즘 재사용.
        List<LsTaskEventLog> events = tx.execute(s -> eventLogRepository.findByRawDataIdOrderByOcrnDtAsc(rawSn));
        assertThat(events)
                .filteredOn(e -> LsTaskEventLog.EVENT_APPROVE.equals(e.getEventTypeCd()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getRsn()).isEqualTo(LsTaskEventLog.RSN_NO_LABEL_CONFIRMED);
                    assertThat(e.getActorUserNo()).isEqualTo(9001L);
                    assertThat(e.getOcrnDt()).isNotNull();
                });
    }

    @Test
    @DisplayName("라벨이_있는_영상에_라벨없음_확인을_보내면_400_이라_플래그_상시전송이_불가능하다")
    void noLabelConfirmationOnLabeledVideoRejected() {
        long rawSn = seedVideoInReview(true);

        // 확인 플래그를 습관적으로 항상 붙이는 클라이언트는 정상 승인부터 깨진다 → 게이트가 무력화되지 않는다.
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                reviewService.approve(rawSn, new ApproveRequest(true), reviewer())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        String status = tx.execute(s -> statusRepository.findByRawDataId(rawSn)
                .orElseThrow().getDataSttsCd());
        assertThat(status).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }

    @Test
    @DisplayName("확인_플래그_null_false_는_미확인으로_취급되어_기본_게이트가_유지된다")
    void nullOrFalseConfirmationKeepsGate() {
        long nullFlag = seedVideoInReview(false);
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                reviewService.approve(nullFlag, new ApproveRequest(null), reviewer())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_NO_LABEL);

        long falseFlag = seedVideoInReview(false);
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                reviewService.approve(falseFlag, new ApproveRequest(false), reviewer())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.REVIEW_NO_LABEL);
    }
}
