package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.dto.PortalUploadLabelRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldLblRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.service.PortalUploadLabelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HIGH #1 동시 PUT 경합 — <b>실 DB(PostgreSQL Testcontainer)</b>에서 프레임 행 비관적 락이 병렬
 * 전체교체를 직렬화함을 고정한다. 동일 프레임에 서로 다른 라벨 집합을 2스레드가 동시에 PUT 하면,
 * delete→saveAll 이 프레임 락으로 순차화되어 최종 라벨이 <b>정확히 한 요청의 집합</b>과 일치해야 한다
 * (혼합/중복/유실 없음). 락이 사라지면 두 요청의 delete/insert 가 교차해 3건(혼합)이나 중복이 남는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalUploadLabelConcurrencyIT {

    @Autowired private PortalUploadLabelService service;
    @Autowired private LsPortalUldRepository uldRepository;
    @Autowired private LsPortalUldFrmeRepository frmeRepository;
    @Autowired private LsPortalUldLblRepository lblRepository;

    private final TransactionTemplate txTemplate;

    PortalUploadLabelConcurrencyIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @Test
    @DisplayName("동시_PUT_경합시_최종상태는_단일_요청_집합")
    void concurrentPutConvergesToSingleRequestSet() throws Exception {
        String owner = "portal-lbl-conc-" + System.nanoTime();

        // given — READY 이미지 자산 + 프레임 1건 커밋 저장
        long uldSn = txTemplate.execute(s -> {
            LsPortalUld uld = LsPortalUld.createImage(owner, "a.png", "a.png", 100L, "image/png");
            uld.markReady(null, null, 1);
            return uldRepository.save(uld).getUldSn();
        });
        long frmeSn = txTemplate.execute(s -> {
            LsPortalUldFrme frame = LsPortalUldFrme.create(uldSn, 0, "f0.png");
            return frmeRepository.save(frame).getUldFrmeSn();
        });

        // 서로 다른 라벨 집합 — A={car,person}(2건), B={dog}(1건). 최종은 정확히 한쪽이어야 한다.
        List<PortalUploadLabelRequest> setA = List.of(
                bbox("car"), bbox("person"));
        List<PortalUploadLabelRequest> setB = List.of(
                bbox("dog"));

        // when — 2스레드 동시 PUT
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            pool.submit(putTask(frmeSn, owner, setA, ready, go, err));
            pool.submit(putTask(frmeSn, owner, setB, ready, go, err));
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(err.get()).isNull();

        // then — 최종 라벨명 집합이 정확히 A 또는 B (혼합/중복/유실 없음)
        List<LsPortalUldLbl> finalLabels = txTemplate.execute(
                s -> lblRepository.findAllByUldFrmeSnAndPortalUserNo(frmeSn, owner));
        List<String> names = finalLabels.stream().map(LsPortalUldLbl::getLblNm).sorted().collect(Collectors.toList());
        Set<String> distinct = Set.copyOf(names);
        // 중복 없음(집합 크기 == 리스트 크기)
        assertThat(names).hasSize(distinct.size());
        assertThat(distinct)
                .isIn(Set.of("car", "person"), Set.of("dog"));

        // cleanup
        txTemplate.executeWithoutResult(s -> uldRepository.deleteById(uldSn));
    }

    private Runnable putTask(long frmeSn, String owner, List<PortalUploadLabelRequest> body,
                             CountDownLatch ready, CountDownLatch go, AtomicReference<Throwable> err) {
        return () -> {
            try {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                service.replaceLabels(frmeSn, owner, body);
            } catch (Throwable t) {
                err.compareAndSet(null, t);
            }
        };
    }

    private PortalUploadLabelRequest bbox(String label) {
        return new PortalUploadLabelRequest("BBOX", label, List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)));
    }
}
