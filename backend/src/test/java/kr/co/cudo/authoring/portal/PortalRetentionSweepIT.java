package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.scheduler.PortalRetentionSweepJob;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository.RetentionAxis;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLabelRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보존기간 만료 자동 삭제를 <b>실 DB·실 파일</b>로 검증한다. @design DFEAT-055, AC-032, AC-036, AC-037
 *
 * <p>단위 테스트는 리포지토리를 mock 하므로 <b>삭제 SQL 의 조건이 실제로 무엇을 거르는지</b> 확인하지
 * 못한다. 이 배치는 사용자 데이터를 비가역으로 지우므로 그 조건이야말로 유일한 방어선이라 여기서
 * 실행해 고정한다.
 *
 * <h3>★★ 흡수(ADR-058) 뒤 이 시험의 무게가 달라졌다</h3>
 * <p>흡수 전에는 <b>전용 표가 울타리</b>였다 — 조건을 빠뜨려도 지워지는 것은 포털 자산뿐이었다.
 * 이제 자산이 관제 영상과 <b>같은 원장</b>에 앉으므로, 삭제 대상은 <b>출처 판별자 + 소유자 보유 +
 * 보존기간 경과</b> 셋을 동시에 충족해야 하고 <b>하나만 빠지면 관제 영상을 지운다</b>. 그래서 이
 * 파일에 「관제 영상은 어떤 경로로도 지워지지 않는다」를 명시적으로 세운다.
 *
 * <p>보존일수는 시드({@code V11})가 넣은 값(데이터마트 7 / READY 7 / FAILED 1)을 그대로 쓴다 —
 * 테스트에서 설정을 바꾸면 캐시(TTL 60s)가 다른 컨텍스트로 새므로 <b>픽스처 시각</b>만 조정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
// 잡 빈은 자기 토글이 소유한다(PortalRetentionSweepSchedulingConfig 와 같은 키). 테스트 기본은 꺼져
// 있으므로 여기서만 켠다 — 발화는 테스트 yml 의 initial-delay 24h 가 막고, 이 IT 는 잡 메서드를
// 직접 호출해 삭제 조건을 검증한다.
@TestPropertySource(properties = "portal.retention.sweep.enabled=true")
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
    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private PortalUploadFrameRepository frmeRepository;
    @Autowired private PortalUploadLabelRepository lblRepository;
    @Autowired private VideoRepository videoRepository;

    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;

    PortalRetentionSweepIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager,
            @Qualifier("controlDataSource") DataSource controlDataSource) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
        this.jdbc = new JdbcTemplate(controlDataSource);
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
                .as("최초 저장일이 보존기간을 넘긴 그룹은 삭제된다")
                .isEmpty();
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, freshRawSn))
                .as("보존기간 안의 그룹은 그대로 남는다")
                .hasSize(1);
    }

    /**
     * ★ 이번 결함의 <b>핵심 재현 조건</b>이다 — 최초 저장은 오래됐고 마지막 저장은 최근인 그룹.
     *
     * <p>구 {@code MAX} 축에서는 이 그룹이 후보에서 빠져 <b>사용자가 저장을 이어 가는 한 만료가 영영
     * 오지 않았다</b>(= 데이터마트 채널의 자동 삭제가 사실상 실행되지 않았다). 기산점을 최초 저장으로
     * 고정하면 <b>작업 중이라도</b> 삭제 대상이 된다(DFEAT-055 — 포털 확정 회신 2026-09-03).
     */
    @Test
    @DisplayName("★재작업으로_다시_저장해도_만료가_밀리지_않아_그룹_전체가_삭제된다_최초저장_기산")
    void reworkDoesNotPushExpiryAndWholeGroupIsDeleted() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        long rawSn = newVideoRawSn();
        txTemplate.executeWithoutResult(s -> {
            saveUserLabel(user, rawSn, daysAgo(30));   // 최초 저장 — 보존기간을 넘겼다
            saveUserLabel(user, rawSn, daysAgo(1));    // 재작업으로 방금 다시 저장
        });

        job.sweepDatamartLabels();

        // 기준점은 MIN(REG_DT) 라 재저장이 만료를 밀지 못한다. MAX 로 되돌리면 이 단언이 깨진다.
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .as("★최초 저장이 보존기간을 넘긴 그룹은 작업 중이라도 삭제 대상이다")
                .isEmpty();
    }

    @Test
    @DisplayName("보존기간_안에_최초저장한_그룹은_삭제문을_직접_불러도_0행이다_후보조회_우회해도_막힌다")
    void datamartDeleteStatementRechecksExpiry() {
        String user = "user-" + System.nanoTime();
        long rawSn = newVideoRawSn();
        LocalDateTime cutoff = daysAgo(7);
        txTemplate.executeWithoutResult(s -> {
            // 최초 저장이 커트라인 이후 — 아직 만료가 아니다.
            saveUserLabel(user, rawSn, daysAgo(3));
            saveUserLabel(user, rawSn, daysAgo(1));
        });

        int removed = txTemplate.execute(s ->
                userLabelRepository.deleteExpiredLabelGroup(user, rawSn, cutoff));

        assertThat(removed).as("삭제문 자체에 만료 조건이 걸려 있어야 0행이다").isZero();
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .hasSize(2);
    }

    /**
     * ★ 삭제 <b>실행문 자체</b>의 판정 축을 고정한다 — 후보 조회만 {@code MIN} 으로 고치고 삭제문을
     * 구 조건({@code not exists(regDt >= cutoff)})으로 두면 후보에는 들어오는데 실제로는 0행이라
     * <b>매 회차 후보로 잡히기만 하고 영영 지워지지 않는다</b>. 두 곳이 갈리는 것을 여기서 막는다.
     */
    @Test
    @DisplayName("★최초저장이_오래된_그룹은_최근에_다시_저장했어도_삭제문이_행을_지운다_삭제문_판정축")
    void datamartDeleteStatementUsesFirstSavedAxis() {
        String user = "user-" + System.nanoTime();
        long rawSn = newVideoRawSn();
        LocalDateTime cutoff = daysAgo(7);
        txTemplate.executeWithoutResult(s -> {
            saveUserLabel(user, rawSn, daysAgo(30));   // 최초 저장 — 커트라인 이전
            saveUserLabel(user, rawSn, daysAgo(1));    // 재작업 — 커트라인 이후
        });

        int removed = txTemplate.execute(s ->
                userLabelRepository.deleteExpiredLabelGroup(user, rawSn, cutoff));

        assertThat(removed).as("구 MAX 축 조건으로 되돌리면 0행이 되어 영영 지워지지 않는다").isEqualTo(2);
        assertThat(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(user, rawSn))
                .isEmpty();
    }

    @Test
    @DisplayName("이미_지워진_그룹에_삭제문을_다시_불러도_0행이다_2노드_멱등")
    void datamartDeleteIsIdempotentAcrossNodes() {
        String user = "user-" + System.nanoTime();
        long rawSn = newVideoRawSn();
        LocalDateTime cutoff = daysAgo(7);
        txTemplate.executeWithoutResult(s -> saveUserLabel(user, rawSn, daysAgo(30)));

        int first = txTemplate.execute(s ->
                userLabelRepository.deleteExpiredLabelGroup(user, rawSn, cutoff));
        int second = txTemplate.execute(s ->
                userLabelRepository.deleteExpiredLabelGroup(user, rawSn, cutoff));

        assertThat(first).isPositive();
        assertThat(second).as("남은 행이 없으면 조건이 거짓이 되어 두 번째 노드는 0행이다").isZero();
    }

    // ==================================================== 축 B — 업로드 자산 (AC-036 / AC-037)

    /**
     * 잡 <b>전체 경로</b>(후보 스캔 → 파일 삭제 → DB 삭제)에서 관제 영상이 살아남는지 본다.
     *
     * <h3>⚠ 이 시험이 지키지 <b>못하는</b> 것 — 잘못된 안심을 주지 않기 위해 적는다</h3>
     * <p>구 주석은 <i>"판별자 셋 중 하나만 빠져도 이 단언이 깨진다"</i> 라고 적었으나 <b>거짓이다.</b>
     * 술어에서 판별자를 <b>셋 다</b> 무력화해도 이 시험은 green 이다 — 관제 영상의 파일 경로가 포털
     * 저장 루트 밖이라 {@link kr.co.cudo.authoring.portal.service.PortalStoragePathGuard} 가 거부해
     * <b>DB 삭제까지 도달하지 않기</b> 때문이다. 즉 여기서 관제 영상을 살린 것은 <b>술어가 아니라
     * 경로 가드</b>다(그 가드도 정당한 방어선이라 이 시험 자체는 유효하다).
     *
     * <p>★ <b>술어 자체의 회귀 가드는 아래 두 시험이 진다</b> — 「출처 판별자만…」·「소유자 보유만…」.
     * 그 둘은 경로 가드를 거치지 않는 삭제문 <b>직접 호출</b>이라 술어가 유일한 방어선이 된다.
     */
    @Test
    @DisplayName("관제_영상은_아무리_오래돼도_포털_보존기간_배치_전체_경로에서_지워지지_않는다")
    void controlVideosAreNeverSweptByPortalRetention() {
        assumeSeededRetention();
        // given — 포털 소유자도 없고 출처도 다른 관제 인입 영상. 등록일은 아주 오래됐다.
        long controlRawSn = newVideoRawSn();
        jdbc.update("UPDATE ls_data_raw SET reg_dt = ?, mdfcn_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(daysAgo(400)), Timestamp.valueOf(daysAgo(400)), controlRawSn);

        job.sweepExpiredUploads();

        assertThat(videoRepository.findById(controlRawSn))
                .as("잡을 통째로 돌려도 관제 영상은 남는다(술어 + 경로 가드의 합)")
                .isPresent();
    }

    // ------------------- 판별자 두 겹 — 마킹 대기 축은 상태 조건이 관제 행을 걸러 주지 못한다

    /**
     * ★ <b>{@code src_type} 하나만</b>이 관제 영상을 막는 상황을 만들어 그 조건을 고정한다.
     *
     * <p>픽스처는 <b>나머지 조건이 대신 막아 주지 못하도록</b> 짰다 — 소유자를 채우고
     * ({@code portal_user_no} non-null), 상태 행은 두지 않으며(부재 = 업로드됨이라 마킹 대기 축의
     * 상태 조건을 <b>통과</b>한다), 등록일도 커트라인보다 이르다. 그래서 이 축에서 관제 영상을 막는
     * 것은 <b>출처 판별자 하나</b>뿐이고, 그것이 빠지면 곧바로 지워진다.
     *
     * <p>삭제문을 <b>직접</b> 부르는 것도 의도다 — 잡 경로로 부르면 저장 루트 밖 경로를 경로 가드가
     * 먼저 거부해 <b>술어가 검증되지 않는다</b>(위 「잡 전체 경로」 시험의 한계).
     */
    @Test
    @DisplayName("★출처_판별자만_관제_영상을_막는_상황에서도_마킹대기_삭제문이_0행이다")
    void markingPendingAxisIsBlockedBySrcTypeAlone() {
        long rawSn = newVideoRawSn();
        // 소유자를 채워 「소유자 보유」 조건이 대신 막지 못하게 한다. 상태 행은 없다(= 업로드됨).
        jdbc.update("UPDATE ls_data_raw SET portal_user_no = ?, reg_dt = ? WHERE raw_sn = ?",
                "impostor-" + System.nanoTime(), Timestamp.valueOf(daysAgo(400)), rawSn);

        int removed = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.UPLOADED, rawSn, daysAgo(7)));

        assertThat(removed)
                .as("★출처 판별자가 빠지면 관제 영상이 지워진다 — 이 축에서는 상태 조건이 못 막는다")
                .isZero();
        assertThat(videoRepository.findById(rawSn)).isPresent();
    }

    /**
     * ★ <b>{@code portal_user_no} 하나만</b>이 남는 상황을 만들어 그 조건을 고정한다.
     *
     * <p>출처는 포털 자산 그대로 두고 소유자만 비운다 — 흡수(ADR-058) 뒤 두 채널이 같은 원장에
     * 앉으므로 「출처는 맞는데 소유자가 없는 행」이 실재할 수 있고, 그때 소유자 조건이 유일한
     * 방어선이 된다. 상태·등록일은 모두 삭제 쪽으로 기울여 둔다.
     */
    @Test
    @DisplayName("★소유자_보유_조건만_남는_상황에서도_마킹대기_삭제문이_0행이다")
    void markingPendingAxisIsBlockedByOwnerAlone() {
        String user = "user-" + System.nanoTime();
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_UPLOADED,
                daysAgo(30), daysAgo(30), null);
        jdbc.update("UPDATE ls_data_raw SET portal_user_no = NULL WHERE raw_sn = ?", uldSn);

        int removed = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.UPLOADED, uldSn, daysAgo(7)));

        assertThat(removed)
                .as("★소유자 보유 조건이 빠지면 소유자 없는 행이 지워진다 — 남은 겹이 없다")
                .isZero();
        assertThat(videoRepository.findById(uldSn)).isPresent();
    }

    @Test
    @DisplayName("★관제_영상_식별자를_삭제문에_직접_넣어도_0행이다 — 후보_조회를_우회해도_막힌다")
    void controlVideoCannotBeDeletedEvenByDirectCall() {
        long controlRawSn = newVideoRawSn();
        jdbc.update("UPDATE ls_data_raw SET reg_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(daysAgo(400)), controlRawSn);

        int removedReady = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.READY, controlRawSn, daysAgo(7)));
        int removedFailed = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.FAILED, controlRawSn, daysAgo(1)));

        assertThat(removedReady).isZero();
        assertThat(removedFailed).isZero();
        assertThat(videoRepository.findById(controlRawSn)).isPresent();
    }

    @Test
    @DisplayName("후처리중_자산은_등록일로부터_보존기간이_지나도_DB행과_파일이_모두_남는다")
    void processingAssetIsNeverSwept() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("processing-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_PROCESSING,
                daysAgo(30), daysAgo(30), file);

        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(uldSn))
                .as("후처리 중 자산을 지우면 프레임 추출 러너와 경쟁해 파일-DB 불일치가 난다")
                .isPresent();
        assertThat(file).exists();
    }

    /**
     * ★ 2026-09-05 확정 이후 이 시험의 결론이 뒤집혔다 — 「부재 = 업로드됨」이라는 <b>읽기 규약은
     * 그대로</b>인데, 그 상태가 이제 삭제 축이라 <b>등록일 기산으로 지워진다</b>(AC-1070).
     *
     * <p>이 단언이 지키는 것은 {@code STATUS_EXPR} 의 {@code COALESCE} 기본값이다 — 그것이 사라지면
     * 상태 행이 없는 자산은 <b>어느 축의 상태 조건에도 맞지 않아</b> 후보에서 빠지고, 그대로
     * 파일째 영구히 남는다(이번에 닫은 바로 그 공백이 조용히 다시 열린다).
     */
    @Test
    @DisplayName("★상태_행이_없는_자산도_마킹대기로_읽혀_등록일_기산으로_삭제된다_COALESCE_기본값")
    void assetWithoutStatusRowIsSweptAsMarkingPending() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("nostatus-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(30), daysAgo(30), file);
        // 상태 행을 통째로 지워 「아직 기록되지 않은」 상태를 만든다.
        jdbc.update("DELETE FROM ls_data_meta WHERE raw_sn = ? AND meta_key = ?",
                uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS);

        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(uldSn))
                .as("★부재를 업로드됨으로 읽지 못하면 이 자산은 어느 축에도 걸리지 않아 영구히 남는다")
                .isEmpty();
        assertThat(file).doesNotExist();
    }

    // ---------------------------------------- 마킹 대기 축 (AC-1070, 2026-09-05 확정)

    /**
     * ★ 이번에 닫은 공백의 재현 시험이다 — 마킹하지 않고 올려만 둔 자산.
     *
     * <p>그전에는 후보 상태가 준비 완료·처리 실패 둘뿐이었고 <b>마킹 대기는 어느 쪽으로도 스스로
     * 전이하지 않아</b>(방치 전이의 출발 상태는 처리 중 하나다) 사람이 마킹하지 않으면 파일째 영구히
     * 남았다. ⚠ 이 축을 「2026-09-02 에 닫은 방치 판정을 되살린 것」으로 읽지 말 것 — 분 단위로
     * <b>실패 마감</b>하던 그 경로가 아니라 일 단위 <b>정상 만료</b>다.
     *
     * <p>마킹 대기 자산에는 <b>프레임이 없어 원본 파일 1건만</b> 지운다 — 「파일 먼저, DB 나중」
     * 순서는 프레임 목록이 비어도 그대로 성립한다.
     */
    @Test
    @DisplayName("★마킹대기_자산은_등록일_기산_보존기간이_지나면_원본파일과_DB행이_함께_삭제된다")
    void markingPendingAssetIsSweptByRegDt() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("pending-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_UPLOADED,
                daysAgo(30), daysAgo(30), file);

        // 사전 확인 — 마킹을 거치지 않았으므로 프레임이 없다(지울 파일은 원본 1건뿐).
        assertThat(frmeRepository.findAllByRawSnOrderByFrameNoAsc(uldSn)).isEmpty();
        assertThat(assetRepository.findFilePaths(uldSn))
                .as("프레임 목록이 비어도 원본 경로 1건은 수집돼야 파일이 지워진다")
                .containsExactly(file.toString());

        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(uldSn))
                .as("★이 축이 빠지면 마킹하지 않은 자산이 파일째 영구히 남는다")
                .isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("마킹대기_자산도_보존기간_안이면_남는다 — 등록일이_기산점이다")
    void markingPendingAssetInsideRetentionIsKept() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("pending-fresh-" + System.nanoTime() + ".mp4");
        // 등록 3일 전 — 공유 보존일수 7일 안이다.
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_UPLOADED,
                daysAgo(3), daysAgo(3), file);

        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(uldSn)).isPresent();
        assertThat(file).exists();
    }

    /**
     * ★ 기산점이 <b>등록일</b>이라는 사실을 실 SQL 로 고정한다 — 상태 전이 시각(실패 축의 기산점)으로
     * 바꾸면 이 시험이 깨진다. 등록은 오래됐지만 상태 행은 방금 갱신된 자산을 쓴다.
     */
    @Test
    @DisplayName("★마킹대기_기산점은_등록일이라_상태행이_방금_갱신됐어도_삭제된다")
    void markingPendingUsesRegDtNotStatusChangedAt() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("pending-touched-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_UPLOADED,
                daysAgo(30), daysAgo(1), file);

        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(uldSn))
                .as("★기산점을 상태 변경 시각으로 바꾸면 이 자산이 남아 이 단언이 깨진다")
                .isEmpty();
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("★마킹대기_삭제문에도_상태_조건이_걸려_후처리중_자산은_0행이다")
    void markingPendingDeleteStatementRechecksStatus() {
        String user = "user-" + System.nanoTime();
        // 후처리 중인데 마킹 대기 축 삭제문을 직접 부른다 — 후보 조회를 우회한 경로.
        Long processingSn = saveUpload(user, PortalUploadLedger.STATUS_PROCESSING,
                daysAgo(30), daysAgo(30), null);

        int removed = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.UPLOADED, processingSn, daysAgo(7)));

        assertThat(removed)
                .as("★상태 조건을 빼면 처리 중 자산까지 지워져 프레임 추출과 경쟁한다")
                .isZero();
        assertThat(assetRepository.findPortalAsset(processingSn)).isPresent();
    }

    @Test
    @DisplayName("★관제_영상은_마킹대기_축_삭제문으로도_지워지지_않는다 — 판별자_셋이_함께_걸린다")
    void controlVideoCannotBeDeletedByMarkingPendingAxis() {
        long controlRawSn = newVideoRawSn();
        jdbc.update("UPDATE ls_data_raw SET reg_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(daysAgo(400)), controlRawSn);

        // 관제 영상에는 상태 행이 없어 「부재 = 업로드됨」으로 읽힌다 — 새 축이 그 상태를 노리므로
        // 출처 판별자·소유자 조건이 빠지면 여기서 관제 영상이 사라진다.
        int removed = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.UPLOADED, controlRawSn, daysAgo(7)));

        assertThat(removed).isZero();
        assertThat(videoRepository.findById(controlRawSn))
                .as("★새 축이 base(출처+소유자+상태)를 우회하면 관제 영상을 지운다")
                .isPresent();
    }

    @Test
    @DisplayName("같은_시각_등록이어도_실패축은_1일로_삭제되고_완료축은_7일이라_남는다")
    void failedAndReadyAxesAreJudgedIndependently() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path readyFile = writeFile("ready-" + System.nanoTime() + ".mp4");
        Path failedFile = writeFile("failed-" + System.nanoTime() + ".mp4");
        // 두 자산 모두 3일 전 — 실패 축(1일)만 만료다.
        Long readySn = saveUpload(user, PortalUploadLedger.STATUS_READY,
                daysAgo(3), daysAgo(3), readyFile);
        Long failedSn = saveUpload(user, PortalUploadLedger.STATUS_FAILED,
                daysAgo(3), daysAgo(3), failedFile);
        Long failedFrmeSn = saveFrame(failedSn, failedFile);
        saveUploadLabel(user, failedFrmeSn, daysAgo(3));

        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(readySn))
                .as("완료 축은 7일이라 3일 경과로는 삭제되지 않는다").isPresent();
        assertThat(readyFile).exists();

        assertThat(assetRepository.findPortalAsset(failedSn))
                .as("실패 축은 1일이라 3일 경과면 삭제된다").isEmpty();
        assertThat(failedFile).doesNotExist();
        // ★ 프레임은 외래키 연쇄로, 라벨은 <명시적 삭제>로 정리된다 — 라벨에는 부모 외래키가 없어
        //   지우지 않으면 오류 없이 조용히 고아가 남는다.
        assertThat(frmeRepository.findAllByRawSnOrderByFrameNoAsc(failedSn)).isEmpty();
        assertThat(lblRepository.findAllByAssetAndOwner(
                failedSn, user, PortalUploadLedger.SRC_TYPE)).isEmpty();
        assertThat(countLabelsBySrc(failedFrmeSn))
                .as("★라벨 원장에 고아가 남으면 안 된다 — 부모 외래키가 없어 연쇄로 지워지지 않는다")
                .isZero();
    }

    @Test
    @DisplayName("완료_자산은_라벨을_다시_저장하면_기준점이_밀려_삭제되지_않는다")
    void readyAssetExpiryIsPushedByRecentLabel() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("worked-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(30), daysAgo(30), file);
        Long frmeSn = saveFrame(uldSn, file);
        saveUploadLabel(user, frmeSn, daysAgo(1));

        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(uldSn))
                .as("기준점은 등록일과 라벨 최종 저장일 중 늦은 쪽이다").isPresent();
        assertThat(file).exists();
    }

    @Test
    @DisplayName("후보를_뽑은_뒤_상태나_작업이_바뀌면_업로드_삭제문_자체가_0행으로_걸러낸다")
    void uploadDeleteStatementRechecksStatusAndExpiry() {
        String user = "user-" + System.nanoTime();
        LocalDateTime cutoff = daysAgo(7);

        // ① 후처리 중 — 후보 조회를 우회해 삭제문을 직접 불러도 지워지지 않아야 한다.
        Long processingSn = saveUpload(user, PortalUploadLedger.STATUS_PROCESSING,
                daysAgo(30), daysAgo(30), null);
        int processingRemoved = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.READY, processingSn, cutoff));

        // ② 완료인데 커트라인 이후 라벨이 생긴 자산.
        Long reworkedSn = saveUpload(user, PortalUploadLedger.STATUS_READY,
                daysAgo(30), daysAgo(30), null);
        Long frmeSn = saveFrame(reworkedSn, STORAGE_ROOT.resolve("never-swept.jpg"));
        saveUploadLabel(user, frmeSn, daysAgo(1));
        int reworkedRemoved = txTemplate.execute(s ->
                assetRepository.deleteExpired(RetentionAxis.READY, reworkedSn, cutoff));

        // ③ 실패인데 최근에 상태가 갱신된 자산.
        Long touchedSn = saveUpload(user, PortalUploadLedger.STATUS_FAILED,
                daysAgo(30), daysAgo(1), null);
        int touchedRemoved = txTemplate.execute(s -> assetRepository.deleteExpired(
                RetentionAxis.FAILED, touchedSn, daysAgo(1).minusHours(1)));

        assertThat(processingRemoved).as("상태 조건이 삭제문에 남아 있어야 0행이다").isZero();
        assertThat(reworkedRemoved).as("만료 조건이 삭제문에 남아 있어야 0행이다").isZero();
        assertThat(touchedRemoved).as("기준점 조건이 삭제문에 남아 있어야 0행이다").isZero();
        assertThat(assetRepository.findPortalAsset(processingSn)).isPresent();
        assertThat(assetRepository.findPortalAsset(reworkedSn)).isPresent();
        assertThat(assetRepository.findPortalAsset(touchedSn)).isPresent();
    }

    @Test
    @DisplayName("같은_후보에_배치를_두_번_돌려도_두_번째는_0행이고_오류가_없다")
    void secondSweepIsIdempotent() {
        assumeSeededRetention();
        String user = "user-" + System.nanoTime();
        Path file = writeFile("dup-" + System.nanoTime() + ".mp4");
        Long uldSn = saveUpload(user, PortalUploadLedger.STATUS_FAILED, daysAgo(30), daysAgo(30), file);
        long rawSn = newVideoRawSn();
        txTemplate.executeWithoutResult(s -> saveUserLabel(user, rawSn, daysAgo(30)));

        job.sweepDatamartLabels();
        job.sweepExpiredUploads();
        // 2노드 Active-Active 모사 — 같은 후보를 다른 노드가 한 번 더 처리한다.
        job.sweepDatamartLabels();
        job.sweepExpiredUploads();

        assertThat(assetRepository.findPortalAsset(uldSn)).isEmpty();
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

    /**
     * 포털 자산 1건 적재 후 시나리오 시각·상태로 고정한다.
     *
     * <p>상태는 이제 컬럼이 아니라 메타 원장 행이라 <b>그 행의 값과 시각</b>을 직접 세운다 —
     * 실패 축의 기준점이 곧 그 행의 변경 시각이기 때문이다.
     */
    private Long saveUpload(String user, String status, LocalDateTime regDt,
                            LocalDateTime sttsChgDt, Path file) {
        // 파일 경로는 원장에서 NOT NULL 이라 값이 필요하다(파일 삭제 경로를 타지 않는 시나리오는
        // 실파일을 만들지 않고 경로만 준다).
        String path = (file == null ? STORAGE_ROOT.resolve("no-file.mp4") : file).toString();
        Long uldSn = txTemplate.execute(s ->
                assetRepository.insertUploaded(user, path, "v.mp4", "video/mp4", 4L));
        jdbc.update("UPDATE ls_data_raw SET reg_dt = ?, mdfcn_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(regDt), Timestamp.valueOf(sttsChgDt), uldSn);
        jdbc.update("UPDATE ls_data_meta SET meta_vl = ?, reg_dt = ?, mdfcn_dt = ?"
                        + " WHERE raw_sn = ? AND meta_key = ?",
                status, Timestamp.valueOf(sttsChgDt), Timestamp.valueOf(sttsChgDt),
                uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS);
        return uldSn;
    }

    private Long saveFrame(Long uldSn, Path file) {
        return txTemplate.execute(s ->
                frmeRepository.save(LsDataSrc.create(uldSn, 0L, file.toString(), null)).getSrcSn());
    }

    private void saveUserLabel(String portalUserNo, long rawSn, LocalDateTime regDt) {
        LsPortalUserLabel label = LsPortalUserLabel.create(
                portalUserNo, rawSn, rawSn, "BBOX", "car", "[[1,1],[2,2]]");
        setField(label, "regDt", regDt);
        userLabelRepository.save(label);
    }

    private void saveUploadLabel(String user, Long srcSn, LocalDateTime regDt) {
        Long lblSn = txTemplate.execute(s -> lblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "car", "[[1,1],[2,2]]", user)).getLblSn());
        jdbc.update("UPDATE ls_data_lbl SET reg_dt = ? WHERE lbl_sn = ?",
                Timestamp.valueOf(regDt), lblSn);
    }

    private int countLabelsBySrc(Long srcSn) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_lbl WHERE src_sn = ?", Integer.class, srcSn);
        return n == null ? 0 : n;
    }

    /** 라벨이 참조할 실 영상 1건 적재 후 그 PK 반환 — <b>관제 인입 축</b>(포털 소유자 없음). */
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
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
