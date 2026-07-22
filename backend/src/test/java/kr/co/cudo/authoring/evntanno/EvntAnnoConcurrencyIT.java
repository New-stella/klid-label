package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.evntanno.dto.EventAnnotationPayload;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * event_annotation 동시성 통합 테스트 — <b>실 DB(PostgreSQL Testcontainer)</b>.
 *
 * <ul>
 *   <li><b>① 검수 상태전이 동시성(CWE-362)</b>: 두 REVIEWER 가 같은 검토를 동시 승인하면
 *       낙관적 잠금(@Version)으로 정확히 1건만 성공하고, 다른 1건은 OptimisticLock→409(CONFLICT)로
 *       거부된다(last-writer-wins 이중 성공 방지). {@code LsRawDataStatus} 동시성 선례와 동일 패턴.</li>
 *   <li><b>③ upsert 최초삽입 TOCTOU(CWE-362)</b>: anno 부재 상태에서 두 요청이 동시에 첫 저장하면
 *       {@code UK_LS_EVNT_ANNO_RAW} 위반이 500 으로 노출되지 않고, 한쪽은 create·다른 쪽은 update
 *       폴백으로 깨끗이 수렴한다(최종 anno 1건·review 1건).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class EvntAnnoConcurrencyIT {

    @Autowired private EvntAnnoService evntAnnoService;
    @Autowired private EvntAnnoReviewService reviewService;
    @Autowired private LsEvntAnnoRepository annoRepository;
    @Autowired private LsEvntAnnoReviewRepository reviewRepository;
    @Autowired private VideoRepository rawRepository;

    private final TransactionTemplate txTemplate;

    EvntAnnoConcurrencyIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private TokenClaims reviewer(String sub) {
        return new TokenClaims(sub, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private Long newRawSn() {
        return txTemplate.execute(s -> rawRepository.save(LsDataRaw.createFromIngest(
                "clip-" + System.nanoTime(), "cctv-1", "INTRUSION", "11110",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30)).getRawSn());
    }

    @Test
    @DisplayName("동시_두_REVIEWER_승인시_정확히_1건_성공_나머지_409_CONFLICT")
    void concurrentApprove_onlyOneSucceeds_otherConflict() throws Exception {
        // given — anno + PENDING 검토 row 커밋 저장
        Long rawSn = newRawSn();
        Long evntAnnoSn = txTemplate.execute(s -> {
            LsEvntAnno anno = annoRepository.saveAndFlush(
                    LsEvntAnno.create(rawSn, "{\"event_class\":\"정차\"}", "system"));
            reviewRepository.saveAndFlush(LsEvntAnnoReview.createAuto(
                    anno.getEvntAnnoSn(), LsEvntAnnoReview.META_TYPE_VLM,
                    LsEvntAnnoReview.STTS_PENDING, "system"));
            return anno.getEvntAnnoSn();
        });

        // when — 2스레드 동시 승인
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        try {
            for (String sub : List.of("11", "22")) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        reviewService.approve(rawSn, reviewer(sub));
                        success.incrementAndGet();
                    } catch (CustomException e) {
                        if (e.getErrorCode() == ErrorCode.CONFLICT) {
                            conflict.incrementAndGet();
                        } else {
                            unexpected.compareAndSet(null, e);
                        }
                    } catch (Throwable t) {
                        unexpected.compareAndSet(null, t);
                    }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // then — 정확히 1건 성공 / 1건 409, 최종 상태 APPROVED 단일
        assertThat(unexpected.get()).isNull();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        List<LsEvntAnnoReview> found = txTemplate.execute(s -> reviewRepository.findByEvntAnnoSn(evntAnnoSn));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRvwSttsCd()).isEqualTo(LsEvntAnnoReview.STTS_APPROVED);
    }

    @Test
    @DisplayName("동시_최초저장_경합시_500없이_anno1건_review1건으로_수렴")
    void concurrentFirstUpsert_noServerError_convergesToSingleRow() throws Exception {
        // given — anno 부재 rawSn
        Long rawSn = newRawSn();
        EventAnnotationPayload payload = new EventAnnotationPayload("정차", "무슨 이벤트?", null, null, null);

        // when — 2스레드 동시 첫 저장
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            for (String sub : List.of("11", "22")) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        evntAnnoService.upsert(rawSn, payload, reviewer(sub));
                    } catch (Throwable t) {
                        err.compareAndSet(null, t);
                    }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // then — 예외(특히 500) 없이, anno 정확히 1건 + 검토 row 정확히 1건
        assertThat(err.get()).isNull();
        LsEvntAnno anno = txTemplate.execute(s -> annoRepository.findByRawSn(rawSn).orElse(null));
        assertThat(anno).isNotNull();
        List<LsEvntAnnoReview> reviews = txTemplate.execute(
                s -> reviewRepository.findByEvntAnnoSn(anno.getEvntAnnoSn()));
        assertThat(reviews).hasSize(1);
    }
}
