package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.review.repository.ReviewRepository;
import kr.co.cudo.authoring.review.service.ReviewService;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.support.TestVideoFixtures;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-048 / AC-046 — 저작도구가 <b>직접 태운</b> 비식별이 성공으로 기록되면 검수 승인 보류가 풀린다
 * (실 DB: Testcontainers PostgreSQL).
 *
 * <h3>왜 이 시험이 필요한가</h3>
 * <p>외부 산출물을 <b>원본이라고 지정해</b> 이관하면 {@code LS_RAW_DATA_STATUS.DE_IDNTF_CMPTN_YN='N'}
 * 이 서서 검수 승인이 막힌다. 그 보류를 푸는 갈래는 둘인데, <b>영상 파일 없이 프레임만</b> 이관한
 * 갈래는 외부 비식별 산출물을 받아 기록하는 통로가 이미 담당한다. 나머지 한 갈래 — <b>영상 파일을
 * 함께 준 원본</b> — 은 저작도구가 자기 비식별 단계를 태우고 그 성공 기록이 보류를 풀어야 하는데,
 * 그 배선이 없으면 <b>비식별이 성공해도 승인 보류가 영구 고착</b>한다.
 *
 * <h3>무엇을 고정하는가</h3>
 * <ol>
 *   <li><b>수용기준</b> — 성공 기록 전에는 승인이 412, 성공 기록 뒤에는 같은 영상의 승인이 통과한다.</li>
 *   <li><b>거짓 성공 차단</b> — 산출물이 실재하지 않는(또는 위장된) 완료에서는 보류가 유지된다.
 *       외부 비식별이 원본 없이 위탁받고도 완료로 응답하는 경우가 실제로 있어, 여기서 풀면
 *       처리되지 않은 산출물이 승인을 통과한다.</li>
 *   <li><b>기존 영상 무영향</b> — 이 경로와 무관한 영상은 그 값이 기본 {@code 'Y'} 라 비식별 완료
 *       처리가 아무것도 바꾸지 않는다.</li>
 * </ol>
 *
 * <h3>왜 완료 처리를 직접 호출하나</h3>
 * <p>외부 비식별 서버 왕복(위탁→폴링→다운로드)은 이 시험의 관심사가 아니다. 관심사는 <b>완료가
 * 기록되는 그 트랜잭션</b>이 보류를 함께 푸는가이므로, 그 지점({@code finishDownloadAndComplete})을
 * 실제 빈으로 호출하고 앞뒤의 승인 시도만 실 DB 로 확인한다.
 *
 * @design ADR-048
 * @design AC-046
 * @design ERD-015
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // KpstDeidentTxService 는 @ConditionalOnProperty(kpst.deid.enabled=true) — 켜야 빈이 등록된다.
        "kpst.deid.enabled=true",
        "kpst.deid.base-url=https://localhost:9201",
        "kpst.deid.ca-cert-path=src/test/resources/kpst/test-ca.crt",
        // 폴링 잡이 검증 도중 스스로 깨어나지 않게 충분히 늦춘다(본 시험은 완료 지점을 직접 호출).
        "kpst.deid.poll-interval-sec=3600"
})
class DeidentApprovalHoldReleaseIT {

    @TempDir
    Path tmp;

    @Autowired private KpstDeidentTxService kpstDeidentTxService;
    @Autowired private ReviewService reviewService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private ReviewRepository statusRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final TransactionTemplate tx;
    private final List<Long> seededRawSns = new ArrayList<>();

    DeidentApprovalHoldReleaseIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
    }

    @AfterEach
    void cleanup() {
        // 부모 삭제 = 자식(프레임·라벨·작업상태·비식별 이력) CASCADE 삭제.
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    private TokenClaims reviewer() {
        return new TokenClaims("9001", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    /**
     * 검수 대기(IN_REVIEW) + 프레임 1건 + 라벨 1건인 영상을 만든다.
     *
     * <p>라벨을 반드시 넣는 이유 — 라벨 0건이면 승인이 <b>다른 게이트</b>로도 막혀 이 시험이 무엇을
     * 검증했는지 알 수 없게 된다. 라벨을 채워 두면 남는 거부 사유가 하나뿐이다.
     *
     * @param importedOriginal {@code true} 면 이관 <b>원본</b> 지정 영상 — 승인 보류가 선다.
     */
    private Seed seed(boolean importedOriginal) {
        Seed s = tx.execute(status -> {
            String clipId = "CLIP-DAHR-" + System.nanoTime();
            LsDataRaw raw = importedOriginal
                    ? LsDataRaw.createFromImport(clipId, "EVT-A", "11680", LsDataRaw.PRVC_TYPE_PRVC,
                    "/var/import/clip.mp4", LocalDateTime.now(), 30, false)
                    : LsDataRaw.createFromIngest(clipId, "CCTV-1", "EVT-A", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30);
            raw = rawRepository.saveAndFlush(raw);
            Long rawSn = raw.getRawSn();

            LsDataSrc frame = srcRepository.save(
                    LsDataSrc.create(rawSn, 0, "/var/raw/f0.jpg", LocalDateTime.now()));
            labelRepository.save(LsDataLbl.createManual(frame.getSrcSn(), "BBOX", null,
                    "person", "[[1.0,1.0],[2.0,2.0]]", "100"));

            LsRawDataStatus workStatus = LsRawDataStatus.initial(rawSn);
            workStatus.transitionTo(LsRawDataStatus.STTS_PENDING);
            workStatus.transitionTo(LsRawDataStatus.STTS_IN_REVIEW);
            if (importedOriginal) {
                // 이관 적재가 세우는 승인 보류 — 이 시험의 출발점.
                workStatus.markDeidentNotCompleted();
            }
            statusRepository.save(workStatus);

            // 저작도구가 태운 비식별의 위탁 원장(ACK 완료 = 폴링 대기).
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    rawSn, null, raw.getRawFilePathNm(), "batch");
            procLog.markKpstSubmitted(101L, null);
            return new Seed(rawSn, procLogRepository.saveAndFlush(procLog).getProcLogSn());
        });
        seededRawSns.add(s.rawSn());
        return s;
    }

    private boolean holdReleased(long rawSn) {
        return Boolean.TRUE.equals(
                tx.execute(s -> statusRepository.findByRawDataId(rawSn).orElseThrow().isDeidentCompleted()));
    }

    private String workStatusOf(long rawSn) {
        return tx.execute(s -> statusRepository.findByRawDataId(rawSn).orElseThrow().getDataSttsCd());
    }

    private ErrorCode approveExpectingRejection(long rawSn) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> tx.executeWithoutResult(s -> reviewService.approve(rawSn, reviewer())));
        assertThat(thrown).isInstanceOf(CustomException.class);
        return ((CustomException) thrown).getErrorCode();
    }

    // ---------------------------------------------------------------- 수용기준

    @Test
    @DisplayName("이관원본은_비식별_성공이_기록되기_전에는_승인_412_이고_기록된_뒤에는_승인된다")
    void holdReleasedWhenDeidentSucceeds() {
        Seed seed = seed(true);

        // (1) 성공 기록 전 — 승인 보류가 서 있어 412 이고 상태 전이도 일어나지 않는다.
        assertThat(approveExpectingRejection(seed.rawSn())).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(ErrorCode.PRECONDITION_FAILED.status().value()).isEqualTo(412);
        assertThat(workStatusOf(seed.rawSn())).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
        assertThat(holdReleased(seed.rawSn())).isFalse();

        // (2) 저작도구가 태운 비식별이 성공으로 기록된다(실재하는 산출물 + 무결성 통과).
        String deidFile = TestVideoFixtures.writeTinyMp4(tmp.resolve("deid.mp4")).toString();
        kpstDeidentTxService.finishDownloadAndComplete(seed.rawSn(), seed.procLogSn(), 202L, deidFile);

        // 성공의 정의 그대로 — 비식별 여부 'Y' + 배치 단계 MARKING_READY 와 <b>같은 트랜잭션</b>에서
        //   보류가 풀린다. 두 값이 갈리면 아래 세 단언 중 하나가 깨진다.
        LsDataRaw after = tx.execute(s -> rawRepository.findById(seed.rawSn()).orElseThrow());
        assertThat(after.getDeIdntfYn()).isEqualTo("Y");
        assertThat(after.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(holdReleased(seed.rawSn())).isTrue();

        // (3) 같은 영상의 승인이 이제 통과한다 — 이것이 이 작업의 수용기준이다.
        tx.executeWithoutResult(s -> reviewService.approve(seed.rawSn(), reviewer()));
        assertThat(workStatusOf(seed.rawSn())).isEqualTo(LsRawDataStatus.STTS_APPROVED);
    }

    // ---------------------------------------------------------------- 거짓 성공

    @Test
    @DisplayName("산출물이_실재하지_않는_거짓완료에서는_보류가_유지되고_승인이_계속_412_다")
    void holdKeptOnFalseCompletion() {
        Seed seed = seed(true);
        // 외부가 완료라고 응답했지만 그 자리에 산출물이 없다(원본 없이 위탁받고 완료로 답하는 경우).
        String missing = tmp.resolve("never-created.mp4").toString();

        assertThatThrownBy(() -> kpstDeidentTxService.finishDownloadAndComplete(
                seed.rawSn(), seed.procLogSn(), 202L, missing))
                .isInstanceOf(CustomException.class);

        assertThat(holdReleased(seed.rawSn())).isFalse();
        assertThat(approveExpectingRejection(seed.rawSn())).isEqualTo(ErrorCode.PRECONDITION_FAILED);
        assertThat(workStatusOf(seed.rawSn())).isEqualTo(LsRawDataStatus.STTS_IN_REVIEW);
    }

    @Test
    @DisplayName("비식별_산출물이_위장된_완료에서도_보류가_유지된다_확장자만_맞고_내용이_아니다")
    void holdKeptWhenArtifactIsNotAVideo() {
        Seed seed = seed(true);
        Path fake = tmp.resolve("fake.mp4");
        try {
            java.nio.file.Files.writeString(fake, "not a video at all — but the name says mp4");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        assertThatThrownBy(() -> kpstDeidentTxService.finishDownloadAndComplete(
                seed.rawSn(), seed.procLogSn(), 202L, fake.toString()))
                .isInstanceOf(CustomException.class);

        assertThat(holdReleased(seed.rawSn())).isFalse();
        assertThat(approveExpectingRejection(seed.rawSn())).isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    // ---------------------------------------------------------------- 기존 영상 무영향

    @Test
    @DisplayName("이_경로와_무관한_기존_영상은_비식별_완료처리로_아무것도_달라지지_않는다")
    void existingVideosUnaffected() {
        Seed seed = seed(false);
        assertThat(holdReleased(seed.rawSn())).isTrue(); // 기본값 — 애초에 보류가 없다

        String deidFile = TestVideoFixtures.writeTinyMp4(tmp.resolve("deid-plain.mp4")).toString();
        kpstDeidentTxService.finishDownloadAndComplete(seed.rawSn(), seed.procLogSn(), 202L, deidFile);

        // 종전과 동일한 결과 — 비식별 완료 전이는 그대로이고 보류 축은 손대지 않는다.
        LsDataRaw after = tx.execute(s -> rawRepository.findById(seed.rawSn()).orElseThrow());
        assertThat(after.getDeIdntfYn()).isEqualTo("Y");
        assertThat(after.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(holdReleased(seed.rawSn())).isTrue();

        tx.executeWithoutResult(s -> reviewService.approve(seed.rawSn(), reviewer()));
        assertThat(workStatusOf(seed.rawSn())).isEqualTo(LsRawDataStatus.STTS_APPROVED);
    }

    private record Seed(Long rawSn, Long procLogSn) {
    }
}
