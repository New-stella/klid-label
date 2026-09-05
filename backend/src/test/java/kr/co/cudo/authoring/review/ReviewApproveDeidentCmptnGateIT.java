package kr.co.cudo.authoring.review;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14 — 검수 승인의 <b>비식별화완료여부</b>({@code LS_RAW_DATA_STATUS.DE_IDNTF_CMPTN_YN}) 게이트
 * 통합 테스트 (실 DB: Testcontainers PostgreSQL).
 *
 * <h3>무엇을 고정하는가</h3>
 * 외부 산출물을 <b>원본이라고 지정해</b> 이관한 영상은 비식별이 끝나기 전까지 검수 승인이 막힌다.
 * 그 차단을 표현하는 값이 이 컬럼이고, 기본값은 {@code 'Y'} 라 <b>이 경로와 무관한 기존 영상은
 * 영향을 받지 않는다</b>. 그 무영향이야말로 이 컬럼을 새로 만든 이유이므로 함께 고정한다.
 *
 * <h3>왜 별도 클래스인가</h3>
 * 같은 {@code approve} 의 게이트라도 <b>축이 다르다</b>. 라벨 0건 게이트는 "내보낼 것이 없다"이고
 * 비식별 누락 신고 게이트는 "마스킹이 잘못됐다"이며, 이 게이트는 "아직 비식별하지 않았다"다.
 * 한 클래스에 몰면 어느 축이 깨졌는지 실패 메시지로 구분되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class ReviewApproveDeidentCmptnGateIT {

    @Autowired private ReviewService reviewService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private ReviewRepository statusRepository;

    private final TransactionTemplate tx;

    ReviewApproveDeidentCmptnGateIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    private TokenClaims reviewer() {
        return new TokenClaims("9001", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    /**
     * IN_REVIEW + 프레임 1건 + 라벨 1건인 영상을 만든다.
     *
     * <p>라벨을 반드시 넣는 이유 — 라벨 0건이면 승인이 <b>다른 게이트</b>(409)로도 막혀
     * 이 테스트가 무엇을 검증했는지 알 수 없게 된다. 라벨을 채워 두면 남는 거부 사유가 하나뿐이다.
     *
     * @param deidentCompleted 비식별화완료여부. {@code false} 면 이관 원본과 같은 상태가 된다.
     * @param deIdntfYn        영상의 비식별 여부 코드({@code Y}/{@code N}/{@code F}).
     */
    private long seed(boolean deidentCompleted, String deIdntfYn) {
        return tx.execute(s -> {
            LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                    "CLIP-DCG-" + System.nanoTime(), "CCTV-1", "EVT-A", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
            raw.markDeidentified(deIdntfYn);
            Long rawSn = raw.getRawSn();

            LsDataSrc frame = srcRepository.save(
                    LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
            labelRepository.save(LsDataLbl.createManual(frame.getSrcSn(), "BBOX", null,
                    "person", "[[1.0,1.0],[2.0,2.0]]", "100"));

            LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
            status.transitionTo(LsRawDataStatus.STTS_PENDING);
            status.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
            if (!deidentCompleted) {
                status.markDeidentNotCompleted();
            }
            statusRepository.save(status);
            return rawSn;
        });
    }

    private String statusOf(long rawSn) {
        return tx.execute(s -> statusRepository.findByRawDataId(rawSn).orElseThrow().getDataSttsCd());
    }

    // ------------------------------------------------------------------ 차단

    @Test
    @DisplayName("비식별화완료여부가_N_이면_승인이_412_이고_상태가_그대로_유지된다")
    void approveBlockedWhenDeidentNotCompleted() {
        long rawSn = seed(false, "N");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(ErrorCode.PRECONDITION_FAILED.status().value()).isEqualTo(412);

        // 상태 전이 <이전>에 거부되므로 승인 파이프라인(버전 스냅샷·동결·산출·통지)이 하나도 돌지 않는다.
        //   게이트를 전이 뒤로 옮기면 이 단언이 깨진다 — 위치가 곧 사양이다.
        assertThat(statusOf(rawSn)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }

    // ------------------------------------------------------------------ 무영향 (이 컬럼을 만든 이유)

    @Test
    @DisplayName("기본값_Y_인_기존_영상은_이_게이트에_걸리지_않고_기존대로_승인된다")
    void approveUnaffectedForExistingVideos() {
        // 이 경로와 무관한 영상은 아무도 값을 세워 주지 않는다 — 기본값 그대로 승인돼야 한다.
        long rawSn = seed(true, "Y");

        tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer()));

        assertThat(statusOf(rawSn)).isEqualTo(LsRawDataStatus.STTS_APPROVED);
    }

    @Test
    @DisplayName("적재_직후_상태의_기본값은_Y_라_명시로_세우지_않으면_승인을_막지_않는다")
    void defaultsToCompletedOnCreation() {
        Long rawSn = tx.execute(s -> {
            LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                    "CLIP-DCG-DEF-" + System.nanoTime(), "CCTV-1", "EVT-A", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
            return raw.getRawSn();
        });

        LsRawDataStatus fresh = tx.execute(s -> statusRepository.save(LsRawDataStatus.initial(rawSn)));

        // 애플리케이션 기본값(필드 초기화)과 DB 기본값이 갈리면 적재 경로마다 다른 값이 들어간다.
        assertThat(fresh.getDeIdntfCmptnYn()).isEqualTo(LsRawDataStatus.DE_IDNTF_CMPTN_YES);
        assertThat(fresh.isDeidentCompleted()).isTrue();
    }

    // ------------------------------------------------------------------ 축이 갈리지 않음

    @Test
    @DisplayName("비식별_누락_신고_구간도_같은_412_라_응답이_어느_조건인지_알려주지_않는다")
    void reportedVideoAlsoReturns412() {
        // 두 조건이 서로 다른 코드를 내면 응답 자체가 "이 영상이 신고 중인가"를 알려주는 오라클이 된다.
        //   실제로는 이관 원본이 신고를 접수할 수 없어 두 조건이 동시에 성립하지 않지만,
        //   그 사실에 기대지 않고 코드를 같게 두는 것이 이 게이트의 사양이다.
        long rawSn = seed(true, "F");

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer())))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        assertThat(statusOf(rawSn)).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }
}
