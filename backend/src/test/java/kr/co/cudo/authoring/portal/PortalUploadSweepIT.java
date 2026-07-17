package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.entity.LsPortalTusUpload;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.repository.LsPortalTusUploadRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.scheduler.PortalUploadSweepJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * N1 검증 — 스윕 잡의 벌크 UPDATE/DELETE 가 <b>실 트랜잭션</b>에서 예외 없이 동작함을 실 DB
 * (PostgreSQL Testcontainer)에서 고정한다.
 *
 * <p>기존 회귀 테스트는 모두 mock 이라, {@code PortalUploadSweepJob} 이 같은 빈의
 * {@code @Transactional} 메서드를 self-invocation 하던 시절 {@code @Modifying} 벌크 쿼리가 활성
 * 트랜잭션 없이 실행되는({@code TransactionRequiredException}) 런타임 위험이 한 번도 실행되지
 * 않았다. 스윕을 {@link kr.co.cudo.authoring.portal.service.PortalUploadSweepTxService} 별 빈으로
 * 분리한 뒤, 본 IT 는 {@code job.run()} 경로가 실 트랜잭션에서 만료 세션 삭제 + 고착 자산 FAILED
 * 전이를 예외 없이 수행함을 검증한다. self-invocation 으로 회귀하면 트랜잭션 미적용으로 정리가
 * 일어나지 않아 즉시 실패한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalUploadSweepIT {

    @Autowired private LsPortalTusUploadRepository tusRepository;
    @Autowired private LsPortalUldRepository uldRepository;
    @Autowired private PortalUploadSweepJob sweepJob;

    private final TransactionTemplate txTemplate;

    PortalUploadSweepIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @Test
    @DisplayName("스윕_run_실트랜잭션에서_벌크_정리_동작")
    void sweepRunClearsInRealTransaction() throws Exception {
        // given — 만료된 진행 중 TUS 세션(EXPRY_DT 과거) + 고착 UPLOADED 자산(MDFCN_DT 과거) 커밋 저장
        UUID sessionId = UUID.randomUUID();
        String owner = "portal-sweep-" + System.nanoTime();
        LocalDateTime past = LocalDateTime.now().minusHours(1);

        txTemplate.executeWithoutResult(s -> {
            LsPortalTusUpload session = LsPortalTusUpload.create(
                    sessionId, owner, 100L, "/store/" + sessionId + ".mp4", "clip.mp4");
            setField(session, "expiresAt", past); // 만료 처리
            tusRepository.save(session);
        });
        long stuckUldSn = txTemplate.execute(s -> {
            LsPortalUld uld = LsPortalUld.createVideo(owner, "v.mp4", "/p/v.mp4", 1024L, "video/mp4");
            setField(uld, "mdfcnDt", past); // 고착(N분 이상 미갱신) 처리
            return uldRepository.save(uld).getUldSn();
        });

        // 사전 조건 확인 — 스윕 전 두 행 모두 존재
        assertThat(sessionExists(sessionId)).isTrue();
        assertThat(uldStatus(stuckUldSn)).isEqualTo(LsPortalUld.STTS_UPLOADED);

        // when — 스윕 오케스트레이션 실행(벌크 DELETE/UPDATE 는 별 tx 빈에 위임되어 실 트랜잭션에서 실행)
        assertThatCode(sweepJob::run).doesNotThrowAnyException();

        // then — 실 트랜잭션이 적용되어 만료 세션 행 삭제 + 고착 자산 FAILED 전이(정리 미발생 = tx 미적용 회귀)
        assertThat(sessionExists(sessionId)).isFalse();
        assertThat(uldStatus(stuckUldSn)).isEqualTo(LsPortalUld.STTS_FAILED);
    }

    private boolean sessionExists(UUID sessionId) {
        Boolean present = txTemplate.execute(s -> tusRepository.findById(sessionId).isPresent());
        return Boolean.TRUE.equals(present);
    }

    private String uldStatus(long uldSn) {
        return txTemplate.execute(s -> uldRepository.findById(uldSn).orElseThrow().getUldSttsCd());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
