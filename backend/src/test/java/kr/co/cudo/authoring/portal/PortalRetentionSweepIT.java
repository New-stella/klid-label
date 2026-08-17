package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.entity.LsPortalUldLbl;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldLblRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.scheduler.PortalRetentionSweepJob;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보존기간 만료 자동 삭제를 <b>실 DB·실 파일</b>로 검증한다. @design DFEAT-055, AC-032, AC-036, AC-037
 *
 * <p>단위 테스트는 리포지토리를 mock 하므로 <b>삭제 SQL 의 조건이 실제로 무엇을 거르는지</b> 확인하지
 * 못한다. 이 배치는 사용자 데이터를 비가역으로 지우므로 그 조건이야말로 유일한 방어선이라 여기서
 * 실행해 고정한다. 특히 두 가지다 — {@code PROCESSING} 이 후보에 <b>절대</b> 들어오지 않는가(AC-036),
 * 그리고 후보를 뽑은 뒤 조건이 풀린 자산·그룹을 <b>삭제문 자체가</b> 다시 걸러내는가.
 *
 * <p>보존일수는 시드({@code V11})가 넣은 값(데이터마트 7 / READY 7 / FAILED 1)을 그대로 쓴다 —
 * 테스트에서 설정을 바꾸면 캐시(TTL 60s)가 다른 컨텍스트로 새므로 <b>픽스처 시각</b>만 조정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalRetentionSweepIT {

    /**
     * 포털 저장 루트 — 실 파일 삭제를 검증하려면 경로 판정기의 base 안이어야 한다.
     *
     * <p>{@code @TempDir} 정적 필드를 쓰지 않는 것은 의도다 — 그 확장과 {@code @DynamicPropertySource}
     * 평가 순서가 보장되지 않아 컨텍스트가 아직 비어 있는 값을 읽을 수 있다. 정적 초기화는 클래스
     * 로드 시점이라 항상 앞선다.
     */
    private static final Path STORAGE_ROOT = createTempStorageRoot();

    @DynamicPropertySource
    static void storagePath(DynamicPropertyRegistry registry) {
        registry.add("portal.upload.storage-path", STORAGE_ROOT::toString);
    }

    @Autowired private PortalRetentionSweepJob job;
    @Autowired private PortalRetentionPolicy retentionPolicy;
    @Autowired private LsPortalUserLabelRepository userLabelRepository;
    @Autowired private LsPortalUldRepository uldRepository;
    @Autowired private LsPortalUldFrmeRepository frmeRepository;
    @Autowired private LsPortalUldLblRepository lblRepository;
    @Autowired private VideoRepository videoRepository;

    private final TransactionTemplate txTemplate;

    PortalRetentionSweepIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    // ==================================================== 축 A — 데이터마트 라벨 (AC-032)

    @Test
    @DisplayName("데이터마트_보존기간이_지난_그룹만_삭제되고_기간_내_그룹은_남는다")
    void datamartExpiredGroupDeletedAndFreshGroupKept() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        long expiredRawSn = newVideoRawSn();
        long freshRawSn = newVideoRawSn();
        txTemplate.executeWithoutResult(s -> {
            saveUserLabel(user, expiredRawSn, daysAgo(30));
            saveUserLabel(user, freshRawSn, daysAgo(1));
        });

        job.sweepDatamartLabels();

        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, expiredRawSn))
                .as("마지막 저장일이 보존기간을 넘긴 그룹은 삭제된다")
                .isEmpty();
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, freshRawSn))
                .as("보존기간 안의 그룹은 그대로 남는다")
                .hasSize(1);
    }

    @Test
    @DisplayName("재작업으로_라벨을_다시_저장하면_만료가_밀려_예전_라벨까지_함께_보존된다")
    void reworkPushesExpiryAndKeepsWholeGroup() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        long rawSn = newVideoRawSn();
        txTemplate.executeWithoutResult(s -> {
            saveUserLabel(user, rawSn, daysAgo(30));   // 오래된 작업분
            saveUserLabel(user, rawSn, daysAgo(1));    // 재작업으로 다시 저장
        });

        job.sweepDatamartLabels();

        // 기준점은 MAX(REG_DT) 라 그룹 전체가 살아남는다(AC-032 and_examples[1]).
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .hasSize(2);
    }

    @Test
    @DisplayName("후보를_뽑은_뒤_라벨이_새로_저장되면_삭제문_자체가_0행으로_걸러낸다")
    void datamartDeleteStatementRechecksExpiry() {
        String user = "user-" + System.nanoTime();
        long rawSn = newVideoRawSn();
        LocalDateTime cutoff = daysAgo(7);
        txTemplate.executeWithoutResult(s -> {
            saveUserLabel(user, rawSn, daysAgo(30));
            // 스캔~삭제 사이에 사용자가 재작업으로 저장한 상황.
            saveUserLabel(user, rawSn, daysAgo(1));
        });

        int removed = txTemplate.execute(s ->
                userLabelRepository.deleteExpiredLabelGroup(user, rawSn, cutoff));

        assertThat(removed).as("삭제문에 만료 조건이 남아 있어야 0행이다").isZero();
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .hasSize(2);
    }

    // ==================================================== 축 B — 업로드 자산 (AC-036 / AC-037)

    @Test
    @DisplayName("처리중_자산은_등록일로부터_보존기간이_지나도_DB행과_파일이_모두_남는다")
    void processingAssetIsNeverSwept() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("processing-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, LsPortalUld.STTS_PROCESSING, daysAgo(30), daysAgo(30), file);

        job.sweepExpiredUploads();

        assertThat(uldRepository.findById(uldSn))
                .as("PROCESSING 자산을 지우면 프레임 추출 러너와 경쟁해 파일-DB 불일치가 난다")
                .isPresent();
        assertThat(file).exists();
    }

    @Test
    @DisplayName("같은_시각_등록이어도_FAILED는_1일로_삭제되고_READY는_7일이라_남는다")
    void failedAndReadyAxesAreJudgedIndependently() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path readyFile = writeFile("ready-" + System.nanoTime() + ".mp4");
        Path failedFile = writeFile("failed-" + System.nanoTime() + ".mp4");
        // 두 자산 모두 3일 전 — FAILED 축(1일)만 만료다.
        Long readySn = saveUpload(user, LsPortalUld.STTS_READY, daysAgo(3), daysAgo(3), readyFile);
        Long failedSn = saveUpload(user, LsPortalUld.STTS_FAILED, daysAgo(3), daysAgo(3), failedFile);
        Long failedFrmeSn = txTemplate.execute(s -> frmeRepository.save(
                LsPortalUldFrme.create(failedSn, 0, failedFile.toString())).getUldFrmeSn());
        txTemplate.executeWithoutResult(s -> lblRepository.save(LsPortalUldLbl.create(
                user, failedSn, failedFrmeSn, LsPortalUldLbl.TYPE_BBOX, "car", "[[1,1],[2,2]]")));

        job.sweepExpiredUploads();

        assertThat(uldRepository.findById(readySn))
                .as("READY 축은 7일이라 3일 경과로는 삭제되지 않는다").isPresent();
        assertThat(readyFile).exists();

        assertThat(uldRepository.findById(failedSn))
                .as("FAILED 축은 1일이라 3일 경과면 삭제된다").isEmpty();
        assertThat(failedFile).doesNotExist();
        // FRME/LBL 은 DB FK ON DELETE CASCADE 로 함께 정리된다(수기 삭제 순서표 불필요).
        assertThat(frmeRepository.findAllByUldSnOrderByFrmeNo(failedSn)).isEmpty();
        assertThat(lblRepository.findAllByUldSnAndPortalUserNo(failedSn, user)).isEmpty();
    }

    @Test
    @DisplayName("READY_자산은_라벨을_다시_저장하면_기준점이_밀려_삭제되지_않는다")
    void readyAssetExpiryIsPushedByRecentLabel() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("worked-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, LsPortalUld.STTS_READY, daysAgo(30), daysAgo(30), file);
        Long frmeSn = txTemplate.execute(s -> frmeRepository.save(
                LsPortalUldFrme.create(uldSn, 0, file.toString())).getUldFrmeSn());
        txTemplate.executeWithoutResult(s -> saveUploadLabel(user, uldSn, frmeSn, daysAgo(1)));

        job.sweepExpiredUploads();

        assertThat(uldRepository.findById(uldSn))
                .as("기준점은 등록일과 라벨 최종 저장일 중 늦은 쪽이다").isPresent();
        assertThat(file).exists();
    }

    @Test
    @DisplayName("후보를_뽑은_뒤_상태나_작업이_바뀌면_업로드_삭제문_자체가_0행으로_걸러낸다")
    void uploadDeleteStatementRechecksStatusAndExpiry() {
        String user = "user-" + System.nanoTime();
        LocalDateTime cutoff = daysAgo(7);

        // ① PROCESSING — 후보 조회를 우회해 삭제문을 직접 불러도 지워지지 않아야 한다.
        Long processingSn = saveUpload(user, LsPortalUld.STTS_PROCESSING,
                daysAgo(30), daysAgo(30), null);
        int processingRemoved = txTemplate.execute(s ->
                uldRepository.deleteExpiredReady(processingSn, cutoff));

        // ② READY 인데 커트라인 이후 라벨이 생긴 자산.
        Long reworkedSn = saveUpload(user, LsPortalUld.STTS_READY, daysAgo(30), daysAgo(30), null);
        // FILE_PATH_NM 은 NOT NULL 이라 값이 필요하다. 이 테스트는 삭제문을 직접 부르므로
        // 파일 삭제 경로를 타지 않는다(실파일을 만들 이유가 없다).
        Long frmeSn = txTemplate.execute(s -> frmeRepository.save(LsPortalUldFrme.create(
                reworkedSn, 0, STORAGE_ROOT.resolve("never-swept.jpg").toString())).getUldFrmeSn());
        txTemplate.executeWithoutResult(s -> saveUploadLabel(user, reworkedSn, frmeSn, daysAgo(1)));
        int reworkedRemoved = txTemplate.execute(s ->
                uldRepository.deleteExpiredReady(reworkedSn, cutoff));

        // ③ FAILED 인데 최근에 상태가 갱신된 자산.
        Long touchedSn = saveUpload(user, LsPortalUld.STTS_FAILED, daysAgo(30), daysAgo(1), null);
        int touchedRemoved = txTemplate.execute(s ->
                uldRepository.deleteExpiredFailed(touchedSn, daysAgo(1).minusHours(1)));

        assertThat(processingRemoved).as("상태 리터럴이 삭제문에 남아 있어야 0행이다").isZero();
        assertThat(reworkedRemoved).as("만료 조건이 삭제문에 남아 있어야 0행이다").isZero();
        assertThat(touchedRemoved).as("기준점 조건이 삭제문에 남아 있어야 0행이다").isZero();
        assertThat(uldRepository.findById(processingSn)).isPresent();
        assertThat(uldRepository.findById(reworkedSn)).isPresent();
        assertThat(uldRepository.findById(touchedSn)).isPresent();
    }

    @Test
    @DisplayName("같은_후보에_배치를_두_번_돌려도_두_번째는_0행이고_오류가_없다")
    void secondSweepIsIdempotent() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("dup-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, LsPortalUld.STTS_FAILED, daysAgo(30), daysAgo(30), file);
        long rawSn = newVideoRawSn();
        txTemplate.executeWithoutResult(s -> saveUserLabel(user, rawSn, daysAgo(30)));

        job.sweepDatamartLabels();
        job.sweepExpiredUploads();
        // 2노드 Active-Active 모사 — 같은 후보를 다른 노드가 한 번 더 처리한다.
        job.sweepDatamartLabels();
        job.sweepExpiredUploads();

        assertThat(uldRepository.findById(uldSn)).isEmpty();
        assertThat(file).doesNotExist();
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .isEmpty();
    }

    // ==================================================== 픽스처 헬퍼

    /** 시드된 보존일수(7/7/1)를 전제로 하는 테스트임을 명시 — 어긋나면 실패 원인이 바로 드러난다. */
    private void assumeSeededRetention() {
        assertThat(retentionPolicy.datamartRetentionDays().orElse(-1))
                .as("V11 시드 portal.datamart.retention-days").isEqualTo(7);
        assertThat(retentionPolicy.uploadRetentionDays().orElse(-1))
                .as("V11 시드 portal.upload.retention-days").isEqualTo(7);
        assertThat(retentionPolicy.uploadFailedRetentionDays().orElse(-1))
                .as("V11 시드 portal.upload.failed-retention-days").isEqualTo(1);
    }

    private static LocalDateTime daysAgo(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    private Long saveUpload(String user, String status, LocalDateTime regDt,
                            LocalDateTime mdfcnDt, Path file) {
        return txTemplate.execute(s -> {
            LsPortalUld uld = LsPortalUld.createVideo(
                    user, "v.mp4", file == null ? null : file.toString(), 4L, "video/mp4");
            // 팩토리가 상태·시각을 now 로 박으므로 INSERT 전에 시나리오 값으로 고정한다
            // (@PrePersist 는 null 일 때만 채우고, 최초 INSERT 라 @PreUpdate 도 돌지 않는다).
            setField(uld, "uldSttsCd", status);
            setField(uld, "regDt", regDt);
            setField(uld, "mdfcnDt", mdfcnDt);
            return uldRepository.save(uld).getUldSn();
        });
    }

    private void saveUserLabel(String portalUserNo, long rawSn, LocalDateTime regDt) {
        LsPortalUserLabel label = LsPortalUserLabel.create(
                portalUserNo, rawSn, rawSn, "BBOX", "car", "[[1,1],[2,2]]");
        setField(label, "regDt", regDt);
        userLabelRepository.save(label);
    }

    private void saveUploadLabel(String user, Long uldSn, Long frmeSn, LocalDateTime regDt) {
        LsPortalUldLbl label = LsPortalUldLbl.create(
                user, uldSn, frmeSn, LsPortalUldLbl.TYPE_BBOX, "car", "[[1,1],[2,2]]");
        setField(label, "regDt", regDt);
        lblRepository.save(label);
    }

    /** 라벨이 참조할 실 영상 1건 적재 후 그 PK 반환(FK 충족용 최소 픽스처). */
    private long newVideoRawSn() {
        return txTemplate.execute(s -> videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + System.nanoTime(), "cctv-1", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30)).getRawSn());
    }

    private static Path writeFile(String name) {
        try {
            return Files.write(STORAGE_ROOT.resolve(name), new byte[]{1, 2, 3, 4});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createTempStorageRoot() {
        try {
            return Files.createTempDirectory("portal-retention-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
