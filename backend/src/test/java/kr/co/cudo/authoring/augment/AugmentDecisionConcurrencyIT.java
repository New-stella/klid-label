package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.augment.service.AugmentReviewService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * 증강 <b>활용 결정</b>(accept/reject) 동시성 방어 통합 테스트 (실 DB, PostgreSQL Testcontainer).
 *
 * <h2>막는 결함 (2026-07-31 DEV_FIX MEDIUM — FIX-C)</h2>
 * <p>결정 기록은 {@code findLatestByDataAugSn} → (없으면) {@code save} 의 <b>read-then-act</b> 이고,
 * {@code LS_DATA_AUG_RVW} 에는 {@code DATA_AUG_SN} 유니크가 <b>없다</b>(V25 는 비유니크 인덱스뿐).
 * 직렬화가 없으면 동시 accept + reject 가 각각 <b>새 검수 행</b>을 INSERT 하고 {@code ensurePending}
 * 도 각자 통과해 두 결정이 모두 커밋된다. 그 뒤:
 * <ul>
 *   <li>등재 게이트({@code DerivativeWorkEligibility})는 {@code EXISTS(RVW_STTS_CD='ACCEPTED')} 라
 *       파생을 <b>등재</b>로 보고,</li>
 *   <li>결과 화면은 최신 1행만 읽어 <b>거부됨</b>으로 보인다.</li>
 * </ul>
 * "사람은 거부했는데 파생이 작업목록에 올라간다" 가 성립하고, 재결정은 409 로 막혀 화면에서 되돌릴
 * 수단도 없다. 게이트 도입 이후 영향도가 표시 흔들림 → 실제 등재로 올라갔다.
 *
 * <h2>결정 창을 여는 방법 (결정론적 재현)</h2>
 * <p>{@code accept} 는 <b>같은 트랜잭션 안</b>에서 검수 행을 만든 뒤 외부 통보
 * ({@link ExternalAugmentClient#syncDecision})를 호출한다. 그 호출을 {@link SpyBean} 으로 붙잡아
 * 커밋 <b>이전</b> 에 멈추면, 그 사이 들어온 reject 가:
 * <ul>
 *   <li>행 잠금이 있으면 → {@code findByDataAugSnForUpdate} 에서 <b>대기</b> → accept 커밋 후
 *       ACCEPTED 행을 관측 → 409 (검수 행 1건, GREEN)</li>
 *   <li>행 잠금이 없으면(수정 되돌림) → 대기 없이 진행 → 아직 커밋되지 않은 accept 의 행을 못 보고
 *       <b>두 번째 행</b>을 INSERT → 두 결정이 공존 (검수 행 2건, RED)</li>
 * </ul>
 * 즉 {@code loadOrThrow} 의 잠금을 {@code findById} 로 되돌리면 본 테스트는 실패한다.
 *
 * <p>공유 Testcontainers PG 를 쓰므로 시드는 {@code AUGDEC-} 고유 접두로 만들고 단언은 시드한
 * {@code dataAugSn} 으로만 좁힌다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AugmentDecisionConcurrencyIT {

    @Autowired private AugmentReviewService service;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugRvwRepository reviewRepository;

    /** accept 트랜잭션을 <b>커밋 직전</b>(검수 행 생성 이후)에 멈추는 훅. */
    @SpyBean private ExternalAugmentClient externalAugmentClient;

    private final TokenClaims reviewer =
            new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(3600));

    /** 부모 영상 + 대표 프레임 + <b>생성 성공(ACCEPTED)</b> 외부 위탁 증강 행 시드. */
    private LsDataAug seed(String suffix) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGDEC-" + suffix, "CCTV-AUGDEC-" + suffix, "EVT", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/videos/AUGDEC-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        LsDataSrc frame = srcRepository.saveAndFlush(LsDataSrc.create(
                parent.getRawSn(), 0, "/nas/frames/raw/AUGDEC-" + suffix + "/f0.jpg", null));
        LsDataAug aug = augRepository.save(LsDataAug.createPending(
                frame.getSrcSn(), LsDataAug.AUG_WINTER, new BigDecimal("90.00"), "system"));
        aug.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        return augRepository.saveAndFlush(aug);
    }

    @Test
    @DisplayName("같은_증강에_동시_채택과_반려가_들어와도_검수행은_1건이고_결정도_1건이다")
    void concurrentAcceptAndReject_leaveExactlyOneDecision() throws Exception {
        LsDataAug aug = seed("RACE");
        Long augSn = aug.getDataAugSn();

        CountDownLatch acceptInWindow = new CountDownLatch(1);
        CountDownLatch releaseAccept = new CountDownLatch(1);
        doAnswer(inv -> {
            acceptInWindow.countDown();                 // 검수 행 생성 완료 · 커밋 이전
            releaseAccept.await(15, TimeUnit.SECONDS);  // reject 가 잠금 지점에 닿을 때까지 홀드
            return inv.callRealMethod();
        }).when(externalAugmentClient)
                .syncDecision(eq(augSn), eq(LsDataAug.STTS_ACCEPTED), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> acceptErr = new AtomicReference<>();
        AtomicReference<Throwable> rejectErr = new AtomicReference<>();
        try {
            Future<?> acceptThread = pool.submit(() -> {
                try {
                    service.accept(augSn, reviewer);
                } catch (Throwable t) {
                    acceptErr.set(t);
                    acceptInWindow.countDown(); // 조기 실패 시 메인 대기 해제
                }
                return null;
            });

            assertThat(acceptInWindow.await(15, TimeUnit.SECONDS))
                    .as("accept 가 결정 창(검수 행 생성 후 · 커밋 전)에 진입해야 한다").isTrue();

            CountDownLatch rejectStarted = new CountDownLatch(1);
            Future<?> rejectThread = pool.submit(() -> {
                rejectStarted.countDown();
                try {
                    service.reject(augSn, "겨울 질감이 부자연스럽다", reviewer);
                } catch (Throwable t) {
                    rejectErr.set(t);
                }
                return null;
            });
            assertThat(rejectStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // reject 가 잠금 지점까지 진행할 시간을 준 뒤 accept 를 커밋시킨다.
            //   잠금이 있으면 reject 는 여기서 대기 중이고, 없으면 이미 두 번째 행을 INSERT 했다.
            Thread.sleep(700);
            releaseAccept.countDown();

            acceptThread.get(30, TimeUnit.SECONDS);
            rejectThread.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(acceptErr.get()).as("먼저 진입한 결정은 성공해야 한다").isNull();
        assertThat(rejectErr.get())
                .as("이미 결정된 증강의 재결정은 거부돼야 한다(직렬화 성립)")
                .isNotNull();

        List<LsDataAugRvw> rows = reviewRepository.findByDataAugSnIn(List.of(augSn));
        assertThat(rows)
                .as("잠금이 없으면 두 결정이 각자 새 행을 INSERT 해 2건이 된다(RED)")
                .hasSize(1);
        assertThat(rows.get(0).getRvwSttsCd()).isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
        // 게이트가 보는 사실과 화면이 보는 사실이 같다 — 상반된 두 결정이 공존하지 않는다.
        assertThat(reviewRepository.findLatestByDataAugSn(augSn).orElseThrow().getRvwSttsCd())
                .isEqualTo(LsDataAugRvw.STTS_ACCEPTED);
    }
}
