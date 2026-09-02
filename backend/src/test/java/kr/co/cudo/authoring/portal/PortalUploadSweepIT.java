package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.scheduler.PortalUploadSweepJob;
import kr.co.cudo.authoring.portal.upload.PortalTusSessionRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 스윕 잡의 벌크 갱신·삭제가 <b>실 트랜잭션</b>에서 예외 없이 동작함을 실 DB 로 고정한다.
 *
 * <p>기존 회귀 테스트는 모두 mock 이라, 스윕 잡이 같은 빈의 트랜잭션 메서드를 self-invocation 하던
 * 시절 벌크 쿼리가 활성 트랜잭션 없이 실행되는 런타임 위험이 한 번도 실행되지 않았다.
 *
 * <h3>흡수(ADR-058) 뒤 여기서 함께 지키는 것 둘</h3>
 * <ol>
 *   <li><b>상태 행이 없는 자산도 방치 판정에 든다</b> — 부재는 업로드됨이고, 그 축에서 출발하는
 *       전이라 삽입 겸 조건부 갱신이어야 한다. 단순 UPDATE 로 되돌리면 이 시험이 깨진다.</li>
 *   <li><b>포털 세션과 관제 세션이 서로의 스윕에 걸리지 않는다</b> — 두 채널이 같은 세션 원장을
 *       쓰므로, 관제 정리 잡이 포털 세션을 집으면 행만 사라지고 임시 파일이 고아로 남는다.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
// 잡 빈은 자기 토글이 소유한다(PortalUploadSweepSchedulingConfig 와 같은 키). 테스트 기본은 꺼져
// 있으므로 여기서만 켠다 — 발화는 테스트 yml 의 initial-delay 24h 가 막고, 이 IT 는 잡 메서드를
// 직접 호출해 검증한다.
@TestPropertySource(properties = "portal.upload.sweep.enabled=true")
class PortalUploadSweepIT {

    @Autowired private PortalTusSessionRepository tusRepository;
    @Autowired private LsTusUploadRepository controlTusRepository;
    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private PortalUploadSweepJob sweepJob;

    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;

    PortalUploadSweepIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager,
            @Qualifier("controlDataSource") DataSource controlDataSource) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
        this.jdbc = new JdbcTemplate(controlDataSource);
    }

    @Test
    @DisplayName("스윕_run_실트랜잭션에서_벌크_정리_동작")
    void sweepRunClearsInRealTransaction() {
        // given — 만료된 진행 중 포털 세션 + 방치된 자산(상태 갱신이 멈춘 지 오래) 커밋 저장
        UUID sessionId = UUID.randomUUID();
        String owner = "portal-sweep-" + System.nanoTime();
        LocalDateTime past = LocalDateTime.now().minusHours(1);

        savePortalSession(sessionId, owner, past);
        long stuckUldSn = saveStuckUpload(owner, past);

        // 사전 조건 확인 — 스윕 전 두 행 모두 존재
        assertThat(sessionExists(sessionId)).isTrue();
        assertThat(uldStatus(stuckUldSn)).isEqualTo(PortalUploadLedger.STATUS_UPLOADED);

        // when — 스윕 오케스트레이션 실행(벌크 갱신·삭제는 별 tx 빈에 위임되어 실 트랜잭션에서 실행)
        assertThatCode(sweepJob::run).doesNotThrowAnyException();

        // then — 만료 세션 행 삭제 + 방치 자산 실패 전이(정리 미발생 = 트랜잭션 미적용 회귀)
        assertThat(sessionExists(sessionId)).isFalse();
        assertThat(uldStatus(stuckUldSn)).isEqualTo(PortalUploadLedger.STATUS_FAILED);
    }

    @Test
    @DisplayName("★상태_행이_아예_없는_자산도_방치_전이가_된다 — 부재는_업로드됨이다")
    void assetWithoutStatusRowIsStillSwept() {
        String owner = "portal-sweep-nostatus-" + System.nanoTime();
        long uldSn = saveStuckUpload(owner, LocalDateTime.now().minusHours(1));
        // 상태 행을 통째로 지워 「아직 기록되지 않은」 상태를 만든다.
        jdbc.update("DELETE FROM ls_data_meta WHERE raw_sn = ? AND meta_key = ?",
                uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS);

        sweepJob.failStuckUploads();

        // 단순 UPDATE 로 되돌리면 대상 행이 없어 0행이 되고, 이 자산은 영영 방치로 남는다.
        assertThat(uldStatus(uldSn)).isEqualTo(PortalUploadLedger.STATUS_FAILED);
    }

    @Test
    @DisplayName("★포털_스윕은_관제_세션을_집지_않는다 — 클립_식별자_보유가_채널_판별자다")
    void portalSweepLeavesControlSessionsAlone() {
        UUID controlId = UUID.randomUUID();
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        saveControlSession(controlId, "internal-" + System.nanoTime(), past);

        sweepJob.cleanupExpiredSessions();

        assertThat(sessionExists(controlId))
                .as("관제 세션은 관제 정리 잡이 인입 행 종결까지 함께 처리해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("★관제_정리_잡은_포털_세션을_집지_않는다 — 집으면_행만_지우고_파일이_고아로_남는다")
    void controlCleanupLeavesPortalSessionsAlone() {
        UUID portalId = UUID.randomUUID();
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        savePortalSession(portalId, "portal-" + System.nanoTime(), past);

        int removed = txTemplate.execute(s ->
                controlTusRepository.deleteExpiredById(portalId, LocalDateTime.now()));

        assertThat(removed).as("관제 삭제문에 클립 식별자 보유 조건이 남아 있어야 0행이다").isZero();
        assertThat(sessionExists(portalId)).isTrue();
        assertThat(controlTusRepository.findExpired(
                LocalDateTime.now(), org.springframework.data.domain.PageRequest.of(0, 200)))
                .as("후보 조회에도 포털 세션이 섞이면 안 된다")
                .noneMatch(u -> portalId.equals(u.getUploadId()));
    }

    // ==================================================== 픽스처 헬퍼

    private void savePortalSession(UUID sessionId, String owner, LocalDateTime expiresAt) {
        txTemplate.executeWithoutResult(s -> tusRepository.save(LsTusUpload.createPortalSession(
                sessionId, owner, 100L, "/store/" + sessionId + ".mp4", "clip.mp4")));
        jdbc.update("UPDATE ls_tus_upload SET expry_dt = ? WHERE uld_id = ?",
                Timestamp.valueOf(expiresAt), sessionId);
    }

    /** 관제 세션 — 클립 식별자를 갖는다(요청 검증이 그 값을 강제한다). */
    private void saveControlSession(UUID sessionId, String owner, LocalDateTime expiresAt) {
        txTemplate.executeWithoutResult(s -> controlTusRepository.save(LsTusUpload.create(
                sessionId, owner, 100L, "/store/" + sessionId + ".mp4", "clip.mp4",
                "CLIP-" + System.nanoTime(), "cctv-1", "lgv", LocalDateTime.now())));
        jdbc.update("UPDATE ls_tus_upload SET expry_dt = ? WHERE uld_id = ?",
                Timestamp.valueOf(expiresAt), sessionId);
    }

    /** 방치 판정 대상 자산 — 상태 변경 시각을 과거로 밀어 둔다. */
    private long saveStuckUpload(String owner, LocalDateTime lastTouchedAt) {
        Long uldSn = txTemplate.execute(s -> assetRepository.insertUploaded(
                owner, "/p/v.mp4", "v.mp4", "video/mp4", 1024L));
        jdbc.update("UPDATE ls_data_raw SET mdfcn_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(lastTouchedAt), uldSn);
        jdbc.update("UPDATE ls_data_meta SET reg_dt = ?, mdfcn_dt = ?"
                        + " WHERE raw_sn = ? AND meta_key = ?",
                Timestamp.valueOf(lastTouchedAt), Timestamp.valueOf(lastTouchedAt),
                uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS);
        return uldSn;
    }

    private boolean sessionExists(UUID sessionId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ls_tus_upload WHERE uld_id = ?", Integer.class, sessionId);
        return n != null && n > 0;
    }

    private String uldStatus(long uldSn) {
        return txTemplate.execute(s -> assetRepository.findPortalAsset(uldSn)
                .map(PortalUploadAsset::uldSttsCd).orElse(null));
    }
}
