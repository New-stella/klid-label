package kr.co.cudo.authoring.portal.repository;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 포털 업로드 자산이 <b>공용 원장에 앉는 형상</b>과 <b>원자 전이</b>를 실 DB 로 고정한다(ADR-058 흡수).
 *
 * <h3>여기서만 잡히는 것</h3>
 * <p>흡수로 상태가 컬럼에서 키·값으로 옮겨 갔고, 그 결과 <b>「행이 없음」이 하나의 값</b>이 됐다.
 * 그 규칙은 SQL 문장의 <b>종류</b>를 정하기 때문에(삽입 겸 갱신 vs 단순 갱신) mock 으로는 검증할 수
 * 없다. 되돌리면 조용히 깨지는 부류라 실 DB 에서 세운다.
 *
 * <h3>값 규약도 함께 고정한다</h3>
 * <p>클립 식별자 합성·개인정보 유형·출처 판별자는 ERD 가 확정한 값이며, 어긋나면 관제 조회 통로나
 * 비식별 판정이 포털 자산을 잘못 읽는다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalUploadAssetRepositoryIT {

    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private PortalUploadFrameRepository frmeRepository;
    @Autowired private PortalUploadLabelRepository lblRepository;
    @Autowired private VideoRepository videoRepository;

    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;

    PortalUploadAssetRepositoryIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager,
            @Qualifier("controlDataSource") DataSource controlDataSource) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
        this.jdbc = new JdbcTemplate(controlDataSource);
    }

    // ==================================================== 적재 형상 (값 규약)

    @Test
    @DisplayName("★자산은_영상_원장에_앉고_값_규약_셋이_채워진다")
    void assetLandsOnVideoLedgerWithValueConventions() {
        String user = "user-" + System.nanoTime();
        Long uldSn = newUpload(user, "/p/clip.mp4", "clip.mp4", "video/mp4", 2_000L);

        LsDataRaw raw = txTemplate.execute(s -> videoRepository.findById(uldSn).orElseThrow());

        assertThat(raw.getSrcType())
                .as("출처 판별자 — 새 축이 아니라 기존 값역에 값 하나를 더한 것이다")
                .isEqualTo(LsDataRaw.SRC_TYPE_PORTAL_ULD);
        assertThat(raw.getPortalUserNo()).isEqualTo(user);
        assertThat(raw.getVmsClipId())
                .as("클립 식별자는 자기 원장 행의 기본키로 유일화한다(시각 기반이면 동시 적재가 충돌한다)")
                .isEqualTo(LsDataRaw.PORTAL_ULD_CLIP_ID_PREFIX + uldSn);
        assertThat(raw.getPrvcTypeCd())
                .as("★비식별 불필요(ANONY)가 아니라 미상이다 — 그 판정을 한 적이 없다")
                .isEqualTo(LsDataRaw.PRVC_TYPE_UNKNOWN);
        assertThat(raw.getRawFilePathNm()).isEqualTo("/p/clip.mp4");
    }

    @Test
    @DisplayName("자산의_표시_메타는_메타_원장에_앉고_읽기_모델이_다시_모은다")
    void displayMetaLandsOnMetaLedger() {
        String user = "user-" + System.nanoTime();
        Long uldSn = newUpload(user, "/p/a.jpg", "a.jpg", "image/jpeg", 100L);

        PortalUploadAsset asset = assetRepository.findByOwner(uldSn, user).orElseThrow();

        assertThat(asset.orgnlFileNm()).isEqualTo("a.jpg");
        assertThat(asset.mimeTypeNm()).isEqualTo("image/jpeg");
        assertThat(asset.fileSz()).isEqualTo(100L);
        assertThat(asset.uldTypeCd())
                .as("자산 종류는 보관하지 않고 매체 유형에서 판정한다")
                .isEqualTo(PortalUploadLedger.TYPE_IMAGE);
        assertThat(asset.frmeCnt())
                .as("프레임 수도 보관하지 않고 프레임 원장 행을 센다")
                .isZero();
    }

    // ==================================================== 상태 부재 = 업로드됨

    @Test
    @DisplayName("★상태_행이_없으면_업로드됨으로_읽힌다 — 오류가_아니라_정상_자산이다")
    void absentStatusReadsAsUploaded() {
        String user = "user-" + System.nanoTime();
        Long uldSn = newUpload(user, "/p/v.mp4", "v.mp4", "video/mp4", 1L);
        deleteStatusRow(uldSn);

        PortalUploadAsset asset = assetRepository.findByOwner(uldSn, user).orElseThrow();

        assertThat(asset.uldSttsCd()).isEqualTo(PortalUploadLedger.STATUS_UPLOADED);
    }

    @Test
    @DisplayName("★상태_행이_없어도_후처리_중_전이가_성립한다 — 삽입_겸_갱신이라서")
    void transitionToProcessingWorksWithoutStatusRow() {
        String user = "user-" + System.nanoTime();
        Long uldSn = newUpload(user, "/p/v.mp4", "v.mp4", "video/mp4", 1L);
        deleteStatusRow(uldSn);

        int moved = txTemplate.execute(s -> assetRepository.transitionToProcessing(uldSn));

        assertThat(moved).as("단순 UPDATE 로 되돌리면 여기서 0행이 되고 추출이 영영 시작되지 않는다").isEqualTo(1);
        assertThat(statusOf(uldSn)).isEqualTo(PortalUploadLedger.STATUS_PROCESSING);
    }

    @Test
    @DisplayName("★상태_행이_없는_자산은_완료로_점프하지_않는다 — 완료_전이는_단순_UPDATE_여야_한다")
    void readyTransitionDoesNotJumpFromAbsentStatus() {
        String user = "user-" + System.nanoTime();
        Long uldSn = newUpload(user, "/p/v.mp4", "v.mp4", "video/mp4", 1L);
        deleteStatusRow(uldSn);

        int moved = txTemplate.execute(s -> assetRepository.transitionToReady(uldSn));

        assertThat(moved).isZero();
        assertThat(statusOf(uldSn))
                .as("행이 생기지도 않아야 한다 — 삽입 겸 갱신으로 바꾸면 후처리를 건너뛴다")
                .isNull();
    }

    @Test
    @DisplayName("후처리_중이_아닌_자산은_완료로_전이되지_않는다 — 스윕이_마감한_자산의_부활_차단")
    void readyTransitionBlockedWhenAlreadyFailed() {
        String user = "user-" + System.nanoTime();
        Long uldSn = newUpload(user, "/p/v.mp4", "v.mp4", "video/mp4", 1L);
        txTemplate.executeWithoutResult(s -> assetRepository.upsertMeta(
                uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS, PortalUploadLedger.STATUS_FAILED));

        int moved = txTemplate.execute(s -> assetRepository.transitionToReady(uldSn));
        assertThat(moved).isZero();
        assertThat(statusOf(uldSn)).isEqualTo(PortalUploadLedger.STATUS_FAILED);
    }

    @Test
    @DisplayName("★관제_영상에는_어떤_전이도_적용되지_않는다 — 출처_판별자가_실행문에_박혀_있다")
    void transitionsNeverTouchControlVideos() {
        long controlRawSn = txTemplate.execute(s -> videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + System.nanoTime(), "cctv-1", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30)).getRawSn());

        int toProcessing = txTemplate.execute(s -> assetRepository.transitionToProcessing(controlRawSn));
        int toFailed = txTemplate.execute(s -> assetRepository.failStuck(controlRawSn));
        assertThat(toProcessing).isZero();
        assertThat(toFailed).isZero();
        assertThat(statusOf(controlRawSn))
                .as("관제 행에는 포털 네임스페이스 키가 애초에 생기면 안 된다")
                .isNull();
    }

    // ==================================================== 소유자 스코프

    @Test
    @DisplayName("소유자_스코프_조회는_타사용자_자산을_돌려주지_않는다")
    void ownerScopeExcludesOtherUser() {
        String owner = "owner-" + System.nanoTime();
        String other = "other-" + System.nanoTime();
        Long uldSn = newUpload(owner, "/p/a.jpg", "a.jpg", "image/jpeg", 1L);

        assertThat(assetRepository.findByOwner(uldSn, owner)).isPresent();
        assertThat(assetRepository.findByOwner(uldSn, other)).isEmpty();
        assertThat(assetRepository.findPageByOwner(other, null, PageRequest.of(0, 20)).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("자산_종류_필터는_보관값이_아니라_매체_유형에서_판정한_식으로_거른다")
    void typeFilterUsesDerivedExpression() {
        String user = "list-" + System.nanoTime();
        newUpload(user, "/p/a.jpg", "a.jpg", "image/jpeg", 1L);
        newUpload(user, "/p/c.mp4", "c.mp4", "video/mp4", 1L);

        assertThat(assetRepository.findPageByOwner(
                user, PortalUploadLedger.TYPE_IMAGE, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
        assertThat(assetRepository.findPageByOwner(
                user, PortalUploadLedger.TYPE_VIDEO, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
    }

    // ==================================================== 연쇄 삭제

    @Test
    @DisplayName("★사용자_삭제는_외래키가_닿지_않는_라벨까지_함께_지운다 — 조용한_고아_방지")
    void ownedDeleteAlsoRemovesLabelsWithoutParentFk() {
        String user = "cascade-" + System.nanoTime();
        Long uldSn = newUpload(user, "/p/c.mp4", "c.mp4", "video/mp4", 1L);
        Long srcSn = txTemplate.execute(s -> frmeRepository
                .save(LsDataSrc.create(uldSn, 0L, "/p/f0.jpg", null)).getSrcSn());
        txTemplate.executeWithoutResult(s -> lblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_POLYGON, null, "person", "[[1,2],[3,4]]", user)));

        int removed = txTemplate.execute(s -> assetRepository.deleteOwned(uldSn, user));

        assertThat(removed).isEqualTo(1);
        assertThat(assetRepository.findPortalAsset(uldSn)).isEmpty();
        // 프레임은 외래키 연쇄, 라벨은 명시적 삭제 — 후자를 빠뜨리면 오류 없이 고아가 남는다.
        assertThat(frmeRepository.findAllByRawSnOrderByFrameNoAsc(uldSn)).isEmpty();
        assertThat(countLabels(srcSn)).isZero();
    }

    @Test
    @DisplayName("★타사용자는_남의_자산을_지울_수_없다 — 소유자_조건이_실행문에_있다")
    void ownedDeleteRejectsOtherUser() {
        String owner = "owner-" + System.nanoTime();
        Long uldSn = newUpload(owner, "/p/c.mp4", "c.mp4", "video/mp4", 1L);

        int removed = txTemplate.execute(s -> assetRepository.deleteOwned(uldSn, "intruder"));

        assertThat(removed).isZero();
        assertThat(assetRepository.findPortalAsset(uldSn)).isPresent();
    }

    // ==================================================== 라벨 마지막 저장일 집계

    @Test
    @DisplayName("자산별_라벨_마지막_저장일이_실DB에서_돌아온다 — 라벨이_없으면_키_자체가_없다")
    void lastLabelSavedAtAggregate() {
        String user = "agg-" + System.nanoTime();
        Long withLabel = newUpload(user, "/p/a.mp4", "a.mp4", "video/mp4", 1L);
        Long without = newUpload(user, "/p/b.mp4", "b.mp4", "video/mp4", 1L);
        Long srcSn = txTemplate.execute(s -> frmeRepository
                .save(LsDataSrc.create(withLabel, 0L, "/p/f0.jpg", null)).getSrcSn());
        txTemplate.executeWithoutResult(s -> lblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "car", "[[1,1],[2,2]]", user)));

        Map<Long, LocalDateTime> map =
                assetRepository.findLastLabelSavedAt(user, List.of(withLabel, without));

        assertThat(map).containsKey(withLabel);
        assertThat(map).doesNotContainKey(without);
        assertThat(map.get(withLabel)).isNotNull();
    }

    // ==================================================== 헬퍼

    private Long newUpload(String user, String path, String fileName, String mime, long size) {
        return txTemplate.execute(s ->
                assetRepository.insertUploaded(user, path, fileName, mime, size));
    }

    private void deleteStatusRow(Long uldSn) {
        jdbc.update("DELETE FROM ls_data_meta WHERE raw_sn = ? AND meta_key = ?",
                uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS);
    }

    private String statusOf(Long uldSn) {
        List<String> rows = jdbc.queryForList(
                "SELECT meta_vl FROM ls_data_meta WHERE raw_sn = ? AND meta_key = ?",
                String.class, uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int countLabels(Long srcSn) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM ls_data_lbl WHERE src_sn = ?", Integer.class, srcSn);
        return n == null ? 0 : n;
    }
}
