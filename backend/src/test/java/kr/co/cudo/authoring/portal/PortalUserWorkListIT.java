package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.portal.dto.PortalUserWorkResponse;
import kr.co.cudo.authoring.portal.dto.PortalWorkAssetSource;
import kr.co.cudo.authoring.portal.repository.LsPortalUserEvntAnnoRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUserMetaRepository;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.portal.service.PortalUserWorkService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「내 작업」 목록을 <b>실 DB</b>로 고정한다. @design API-225, DFEAT-055, SCREEN-028, UC-024, TEST-005
 *
 * <p>단위 시험은 리포지토리를 mock 하므로 <b>합집합 질의가 무엇을 싣고 무엇을 거르는지</b>를 한 건도
 * 확인하지 못한다. 이 기능의 위험이 전부 그 질의에 있다.
 * <ul>
 *   <li><b>모집단</b> — 라벨 없이 메타만 고친 영상이 빠지면 사용자가 자기 작업물이 사라지는 것을
 *       볼 수조차 없다. 반대로 저작물 없는 데이터마트 영상이 실리면 남의 화면(카탈로그)을 대신 그린다.</li>
 *   <li><b>격리</b> — 소유자 조건이 축마다 따로 걸린다. 한 축만 풀려도 남의 자산이 샌다.</li>
 *   <li><b>페이지 경계</b> — 두 축을 메모리에서 합치면 여기서만 깨진다.</li>
 * </ul>
 *
 * <p>보존일수는 시드({@code V11})가 넣은 값(데이터마트 7 / READY 7 / FAILED 1)을 그대로 쓴다 —
 * 설정을 바꾸면 캐시(TTL 60s)가 다른 컨텍스트로 새므로 <b>픽스처 시각</b>만 조정한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalUserWorkListIT {

    @Autowired private PortalUserWorkService service;
    @Autowired private PortalRetentionPolicy retentionPolicy;
    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private PortalUploadFrameRepository frameRepository;
    @Autowired private PortalUploadLabelRepository labelRepository;
    @Autowired private LsPortalUserMetaRepository userMetaRepository;
    @Autowired private LsPortalUserEvntAnnoRepository userAnnoRepository;
    @Autowired private VideoRepository videoRepository;

    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;

    PortalUserWorkListIT(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager,
                         @Qualifier("controlDataSource") DataSource dataSource) {
        this.txTemplate = new TransactionTemplate(txManager);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ==================================================== 픽스처

    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    private String newUser() {
        return "work-list-" + System.nanoTime();
    }

    private static LocalDateTime daysAgo(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    private static LocalDateTime hoursAgo(int hours) {
        return LocalDateTime.now().minusHours(hours);
    }

    /**
     * 데이터마트 자산 — 관제 인입 축 영상(포털 소유자 없음) + <b>노출 조건 충족</b>.
     *
     * <p>노출 조건을 세우는 것은 이제 픽스처의 필수 요소다 — 진입 대상 프레임이 「들어갈 수 있을 때만」
     * 실리므로, 상태 행을 만들지 않으면 모든 데이터마트 행의 진입 대상이 비어 버린다.
     */
    private long newDatamartVideo() {
        long rawSn = newDatamartVideoNotExposed();
        jdbc.update("insert into ls_raw_data_status (raw_data_id, data_stts_cd) values (?, 'APPROVED')",
                rawSn);
        return rawSn;
    }

    /**
     * 데이터마트 자산인데 <b>노출 조건을 잃은</b> 영상 — <b>상태 행 자체가 없는</b> 갈래.
     *
     * <p>⚠ 「노출되지 않는다」에는 갈래가 둘이다(행 부재 · 행은 있으나 승인 아님). 이 픽스처만 쓰면
     * <b>승인 판정 리터럴이 한 번도 실행되지 않아</b> 그 판정을 무력화해도 목록 시험이 죽지 않는다 —
     * 그래서 {@link #newDatamartVideoWithStatus} 를 함께 둔다.
     */
    private long newDatamartVideoNotExposed() {
        return txTemplate.execute(s -> videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + System.nanoTime(), "cctv-1", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30)).getRawSn());
    }

    /**
     * 데이터마트 자산 + <b>지정한 작업 상태 행</b> — 「행은 있으나 승인이 아님」 갈래를 만든다.
     *
     * <p>이 갈래라야 승인 판정 리터럴이 실제로 <b>실행되고 거짓이 된다</b>. 상태 기계를 거치지 않고
     * 직접 세우는 것은 의도다 — 여기서 필요한 것은 전이 규칙이 아니라 「그 값이 무엇인가」다.
     */
    private long newDatamartVideoWithStatus(String dataSttsCd) {
        long rawSn = newDatamartVideoNotExposed();
        jdbc.update("insert into ls_raw_data_status (raw_data_id, data_stts_cd) values (?, ?)",
                rawSn, dataSttsCd);
        return rawSn;
    }

    private long newFrame(long rawSn, long frmNo) {
        return txTemplate.execute(s -> frameRepository
                .save(LsDataSrc.create(rawSn, frmNo, "/frames/" + frmNo + ".png", null)).getSrcSn());
    }

    /** 포털 업로드 자산 1건. 등록일·상태를 시나리오 값으로 고정한다. */
    private long newUpload(String owner, String status, LocalDateTime regDt, LocalDateTime sttsChgDt) {
        Long uldSn = txTemplate.execute(s ->
                assetRepository.insertUploaded(owner, "/portal/v.mp4", "내영상.mp4", "video/mp4", 4L));
        jdbc.update("UPDATE ls_data_raw SET reg_dt = ?, mdfcn_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(regDt), Timestamp.valueOf(sttsChgDt), uldSn);
        jdbc.update("UPDATE ls_data_meta SET meta_vl = ?, reg_dt = ?, mdfcn_dt = ?"
                        + " WHERE raw_sn = ? AND meta_key = ?",
                status, Timestamp.valueOf(sttsChgDt), Timestamp.valueOf(sttsChgDt),
                uldSn, PortalUploadLedger.KEY_UPLOAD_STATUS);
        return uldSn;
    }

    /** 업로드 자산 프레임에 붙는 본인 라벨 — 저장 시각을 고정한다. */
    private void saveUploadLabel(String owner, long srcSn, LocalDateTime regDt) {
        Long lblSn = txTemplate.execute(s -> labelRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, "car", "[[1,1],[2,2]]", owner)).getLblSn());
        jdbc.update("UPDATE ls_data_lbl SET reg_dt = ? WHERE lbl_sn = ?",
                Timestamp.valueOf(regDt), lblSn);
    }

    /** 업로드 자산의 <b>포털 메타 편집</b>(키/값 축) — 저장 창구를 그대로 쓰고 시각만 고정한다. */
    private void saveUploadMeta(String owner, long rawSn, String metaKey, LocalDateTime savedAt) {
        int applied = txTemplate.execute(s ->
                assetRepository.upsertOwnedMeta(rawSn, owner, metaKey, "v"));
        assertThat(applied).as("본인 자산이어야 적재된다").isEqualTo(1);
        jdbc.update("UPDATE ls_data_meta SET mdfcn_dt = ? WHERE raw_sn = ? AND meta_key = ?",
                Timestamp.valueOf(savedAt), rawSn, metaKey);
    }

    /** 업로드 자산의 <b>영상 축 컬럼 편집</b>(촬영환경) — 자기 시각이 없어 원장 행 시각을 빌린다. */
    private void saveUploadVideoColumn(String owner, long rawSn, LocalDateTime savedAt) {
        int applied = txTemplate.execute(s ->
                assetRepository.updateOwnedVideoColumn(rawSn, owner, "ENV_WEATHER", "맑음"));
        assertThat(applied).as("본인 자산이어야 반영된다").isEqualTo(1);
        jdbc.update("UPDATE ls_data_raw SET mdfcn_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(savedAt), rawSn);
    }

    /** 업로드 자산의 <b>프레임 축 컬럼 편집</b>(프레임 설명) — 프레임 행의 변경 시각이 기록된다. */
    private void saveUploadFrameColumn(String owner, long srcSn, LocalDateTime savedAt) {
        int applied = txTemplate.execute(s ->
                assetRepository.updateOwnedFrameColumn(srcSn, owner, "FRAME_DESCRIPTION", "설명"));
        assertThat(applied).as("본인 자산이어야 반영된다").isEqualTo(1);
        jdbc.update("UPDATE ls_data_src SET upd_dt = ? WHERE src_sn = ?",
                Timestamp.valueOf(savedAt), srcSn);
    }

    /** 업로드 자산의 <b>이벤트 어노테이션</b> — 본인 자산이라 오버레이가 아니라 자기 원장에 앉는다. */
    private void saveUploadAnnotation(String owner, long rawSn, LocalDateTime savedAt) {
        int applied = txTemplate.execute(s ->
                assetRepository.upsertOwnedEventAnnotation(rawSn, owner, "{\"a\":1}"));
        assertThat(applied).as("본인 자산이어야 적재된다").isEqualTo(1);
        jdbc.update("UPDATE ls_evnt_anno SET mdfcn_dt = ? WHERE raw_sn = ?",
                Timestamp.valueOf(savedAt), rawSn);
    }

    /** 데이터마트 축 저장 라벨(오버레이) — 최초/마지막 저장 시각을 함께 고정한다. */
    private void saveDatamartLabel(String owner, long rawSn, long srcSn, LocalDateTime savedAt) {
        jdbc.update("INSERT INTO ls_portal_user_label"
                        + " (portal_user_no, src_raw_sn, src_data_src_sn, lbl_type_cd, lbl_nm, point_cn,"
                        + "  reg_dt, mdfcn_dt) VALUES (?, ?, ?, 'BBOX', 'car', '[[1,1],[2,2]]', ?, ?)",
                owner, rawSn, srcSn, Timestamp.valueOf(savedAt), Timestamp.valueOf(savedAt));
    }

    /** 메타 오버레이(영상 축) 1칸 — 최초/마지막 저장 시각을 함께 고정한다. */
    private void saveDatamartMeta(String owner, long rawSn, String metaKey, LocalDateTime savedAt) {
        txTemplate.executeWithoutResult(s ->
                userMetaRepository.upsertVideoScoped(owner, rawSn, metaKey, "v"));
        jdbc.update("UPDATE ls_portal_user_meta SET reg_dt = ?, mdfcn_dt = ?"
                        + " WHERE portal_user_no = ? AND src_raw_sn = ? AND meta_key = ?",
                Timestamp.valueOf(savedAt), Timestamp.valueOf(savedAt), owner, rawSn, metaKey);
    }

    /** 이벤트 어노테이션 오버레이 한 벌 — (사용자, 영상)당 1건. */
    private void saveDatamartAnnotation(String owner, long rawSn, LocalDateTime savedAt) {
        txTemplate.executeWithoutResult(s ->
                userAnnoRepository.upsertAnnotation(owner, rawSn, "{\"a\":1}"));
        jdbc.update("UPDATE ls_portal_user_evnt_anno SET reg_dt = ?, mdfcn_dt = ?"
                        + " WHERE portal_user_no = ? AND src_raw_sn = ?",
                Timestamp.valueOf(savedAt), Timestamp.valueOf(savedAt), owner, rawSn);
    }

    private List<PortalUserWorkResponse> list(String owner) {
        return service.listUserWorks(owner, FIRST_PAGE).getContent();
    }

    private static Map<Long, PortalUserWorkResponse> byRawSn(List<PortalUserWorkResponse> rows) {
        return rows.stream().collect(java.util.stream.Collectors
                .toMap(PortalUserWorkResponse::rawSn, r -> r));
    }

    private void assumeSeededRetention() {
        assertThat(retentionPolicy.datamartRetentionDays().orElse(-1))
                .as("V11 시드 portal.datamart.retention-days").isEqualTo(7);
        assertThat(retentionPolicy.uploadRetentionDays().orElse(-1))
                .as("V11 시드 portal.upload.retention-days").isEqualTo(7);
    }

    // ==================================================== 모집단 — 이 기능의 존재 이유

    /**
     * ★★ 이 시험이 이 창구가 신설된 이유다.
     *
     * <p>저작 여부를 <b>저장 라벨만으로</b> 산정하면 라벨 없이 메타만 고친 영상이 목록에서 빠지는데,
     * 보존 규칙의 <b>삭제 대상은 된다</b>. 사용자가 자기 작업물이 사라지는 것을 <b>볼 수조차 없다</b>.
     */
    @Test
    @DisplayName("★★라벨_0건이고_메타_오버레이만_있는_데이터마트_영상이_목록에_실린다")
    void datamartVideoWithOnlyMetaOverlayIsListed() {
        String user = newUser();
        long rawSn = newDatamartVideo();
        newFrame(rawSn, 0L);
        saveDatamartMeta(user, rawSn, "weather", daysAgo(1));

        List<PortalUserWorkResponse> rows = list(user);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.rawSn()).isEqualTo(rawSn);
            assertThat(r.assetSource()).isEqualTo(PortalWorkAssetSource.DATAMART);
            assertThat(r.labelCount()).as("라벨은 0건인 것이 정상이다").isZero();
        });
    }

    /** 축이 셋이라 하나씩 확인한다 — 어노테이션만 고친 영상도 같은 축이다. */
    @Test
    @DisplayName("★라벨_0건이고_이벤트_어노테이션만_있는_데이터마트_영상도_실린다")
    void datamartVideoWithOnlyAnnotationIsListed() {
        String user = newUser();
        long rawSn = newDatamartVideo();
        newFrame(rawSn, 0L);
        saveDatamartAnnotation(user, rawSn, daysAgo(1));

        assertThat(list(user)).singleElement()
                .extracting(PortalUserWorkResponse::rawSn).isEqualTo(rawSn);
    }

    /**
     * ★★ 카탈로그를 대신 그리지 않는다 — 저작물이 하나도 없는 데이터마트 영상이 실리면 그것이 곧
     * 포털(Host) 소유인 목록을 저작도구가 그리는 것이다.
     */
    @Test
    @DisplayName("★★저작물이_하나도_없는_데이터마트_영상은_실리지_않는다_카탈로그가_아니다")
    void datamartVideoWithoutAnyWorkIsNotListed() {
        String user = newUser();
        long rawSn = newDatamartVideo();
        newFrame(rawSn, 0L);

        assertThat(list(user)).as("저작물이 없으면 행이 되지 않는다").isEmpty();
        assertThat(videoRepository.findById(rawSn)).as("영상 자체는 그대로 있다").isPresent();
    }

    /**
     * ★ 업로드 자산은 <b>본인 자산</b>이라 저작 여부와 무관하게 전부 실린다. 마킹 전이라 프레임이
     * 0건이어도 마찬가지다 — <b>진입 가능 여부는 목록 등재와 다른 축</b>이다.
     */
    @Test
    @DisplayName("★저작물도_프레임도_0건인_업로드_자산이_실리고_진입_프레임만_빈다")
    void uploadWithoutWorkOrFramesIsStillListed() {
        String user = newUser();
        long uldSn = newUpload(user, PortalUploadLedger.STATUS_UPLOADED, daysAgo(1), daysAgo(1));

        assertThat(list(user)).singleElement().satisfies(r -> {
            assertThat(r.rawSn()).isEqualTo(uldSn);
            assertThat(r.assetSource()).isEqualTo(PortalWorkAssetSource.PORTAL_UPLOAD);
            assertThat(r.labelCount()).isZero();
            assertThat(r.lastSavedAt()).isNull();
            assertThat(r.entrySrcSn()).as("프레임이 없으면 들어갈 자리가 없다").isNull();
            assertThat(r.videoName()).isEqualTo("내영상.mp4");
        });
    }

    // ============================ 업로드축 저작물 — 라벨만 세면 내려받기가 막힌다 (과소 판정)

    /**
     * ★★ 이 회차가 고친 결함 — 데이터마트축 결함의 <b>거울상</b>이다.
     *
     * <p>업로드축의 마지막 저장 시각을 <b>저장 라벨만</b> 세면, 라벨 없이 메타만 고친 자산이
     * 「저작 이력 없음」으로 읽혀 <b>내려받기가 통째로 막힌다</b>(화면이 이 값으로 가부를 판정한다).
     */
    @Test
    @DisplayName("★★라벨_0건이고_포털_메타만_고친_업로드_자산도_저장_이력이_있다")
    void uploadWithOnlyMetaEditHasSaveHistory() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(10), daysAgo(10));
        newFrame(up, 0L);
        saveUploadMeta(user, up, "note", daysAgo(1));

        PortalUserWorkResponse row = list(user).get(0);

        assertThat(row.lastSavedAt()).as("★라벨만 세면 여기가 null 이라 내려받기가 막힌다").isNotNull();
        assertThat(row.labelCount()).as("라벨 건수는 0 인 것이 정상이다").isZero();
    }

    /** 영상 축 컬럼 편집(촬영환경)도 저작 편집이다 — 자기 시각이 없어 빠뜨리기 쉬운 축이다. */
    @Test
    @DisplayName("★촬영환경만_고친_업로드_자산도_저장_이력이_있다")
    void uploadWithOnlyVideoColumnEditHasSaveHistory() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(10), daysAgo(10));
        newFrame(up, 0L);
        saveUploadVideoColumn(user, up, daysAgo(1));

        assertThat(list(user).get(0).lastSavedAt()).isNotNull();
    }

    /** 프레임 설명·프레임 축 개인정보 판정도 같은 축이다. */
    @Test
    @DisplayName("★프레임_설명만_고친_업로드_자산도_저장_이력이_있다")
    void uploadWithOnlyFrameColumnEditHasSaveHistory() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(10), daysAgo(10));
        long frame = newFrame(up, 0L);
        saveUploadFrameColumn(user, frame, daysAgo(1));

        assertThat(list(user).get(0).lastSavedAt()).isNotNull();
    }

    /** 이벤트 어노테이션도 저작 편집이다(축이 넷이라 하나씩 확인한다). */
    @Test
    @DisplayName("★이벤트_어노테이션만_저장한_업로드_자산도_저장_이력이_있다")
    void uploadWithOnlyAnnotationHasSaveHistory() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(10), daysAgo(10));
        newFrame(up, 0L);
        saveUploadAnnotation(user, up, daysAgo(1));

        assertThat(list(user).get(0).lastSavedAt()).isNotNull();
    }

    /**
     * ★★★ 반대 방향의 붕괴를 막는다 — 같은 원장에 <b>사람이 남기지 않은 것</b>이 함께 앉아 있다.
     *
     * <p>이 픽스처는 업로드 직후 자동으로 생기는 것을 <b>전부</b> 세운다 — 처리 상태 메타,
     * 원본 파일명·매체 유형·파일 크기 기술메타, 영상 길이 확정(원장 행 변경 시각을 민다),
     * 프레임 생성(개인정보 3필드가 <b>값으로</b> 채워진다). 이 중 하나라도 세면 여기가 뒤집히고,
     * 그러면 「업로드 자산은 저작물이 없어도 실린다」가 무의미해지며 만료·내려받기 판정도 틀어진다.
     */
    @Test
    @DisplayName("★★★업로드_직후_아무것도_하지_않은_자산은_저장_이력이_없다_자동_적재값을_세면_뒤집힌다")
    void freshUploadHasNoSaveHistoryDespiteAutomaticWrites() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(3), daysAgo(3));
        newFrame(up, 0L);
        newFrame(up, 1L);
        // 영상 길이 확정 — 원장 행의 변경 시각을 <자동으로> 민다.
        txTemplate.executeWithoutResult(s -> assetRepository.applyVideoDuration(up, 12.5d));

        PortalUserWorkResponse row = list(user).get(0);

        assertThat(row.lastSavedAt())
                .as("★자동 적재값(기술메타·처리 상태·길이 확정·프레임 기본값)은 저작물이 아니다")
                .isNull();
        assertThat(row.labelCount()).isZero();
    }

    // ==================================================== 격리 — 축마다 따로 걸린다

    /**
     * ★ 업로드 축의 소유자 조건. 픽스처는 <b>같은 출처 판별자</b>를 가진 남의 자산이라, 소유자 조건이
     * 빠지면 나머지 조건이 대신 막아 주지 <b>못한다</b>.
     */
    @Test
    @DisplayName("★타인의_업로드_자산은_한_건도_섞이지_않는다")
    void otherUsersUploadNeverLeaks() {
        String alice = newUser();
        String bob = newUser();
        long bobUpload = newUpload(bob, PortalUploadLedger.STATUS_READY, daysAgo(1), daysAgo(1));

        assertThat(list(alice)).as("남의 자산은 행이 되지 않는다").isEmpty();
        assertThat(byRawSn(list(bob))).containsKey(bobUpload);
    }

    /**
     * ★ 데이터마트 축의 소유자 조건. 픽스처는 <b>같은 영상</b>에 남이 남긴 저작물이라, 소유자 조건이
     * 빠지면 그 영상이 통째로 남의 목록에 뜬다.
     */
    @Test
    @DisplayName("★타인이_남긴_데이터마트_저작물은_내_목록에_섞이지_않는다")
    void otherUsersDatamartWorkNeverLeaks() {
        String alice = newUser();
        String bob = newUser();
        long rawSn = newDatamartVideo();
        long srcSn = newFrame(rawSn, 0L);
        saveDatamartLabel(bob, rawSn, srcSn, daysAgo(1));

        assertThat(list(alice)).as("같은 영상이라도 남의 저작물은 내 행이 아니다").isEmpty();
        assertThat(list(bob)).singleElement()
                .extracting(PortalUserWorkResponse::rawSn).isEqualTo(rawSn);
    }

    /**
     * ★ 두 축이 겹치면 같은 영상이 <b>두 행</b>이 된다. 오버레이가 업로드 자산을 가리키는 일은
     * 정상 경로에서 생기지 않지만, 그 불변식이 깨졌을 때 목록이 조용히 중복을 내지 않게 배제로 막는다.
     */
    @Test
    @DisplayName("★업로드_자산을_가리키는_오버레이가_있어도_그_자산은_한_행으로만_실린다")
    void uploadAssetIsNeverDuplicatedByDatamartAxis() {
        String user = newUser();
        long uldSn = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(1), daysAgo(1));
        saveDatamartMeta(user, uldSn, "weather", daysAgo(1));

        List<PortalUserWorkResponse> rows = list(user);

        assertThat(rows).singleElement()
                .extracting(PortalUserWorkResponse::assetSource)
                .isEqualTo(PortalWorkAssetSource.PORTAL_UPLOAD);
    }

    // ==================================================== 정렬 · 페이징

    /**
     * ★★ 두 축을 축별로 N 쪽씩 받아 메모리에서 합치면 <b>2쪽의 첫 행이 1쪽의 마지막 행보다 최신</b>이
     * 되어 정렬이 페이지 경계에서 무너진다. 두 출처를 번갈아 배치해 그 지점을 겨눈다.
     */
    @Test
    @DisplayName("★★두_출처가_섞인_상태로_페이지_경계에서_정렬이_유지된다")
    void sortHoldsAcrossPageBoundaryWithBothAxes() {
        String user = newUser();
        // 최신 → 오래된 순으로 업로드·데이터마트를 번갈아 놓는다.
        long up1 = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(20), daysAgo(20));
        long f1 = newFrame(up1, 0L);
        saveUploadLabel(user, f1, daysAgo(1));

        long dm1 = newDatamartVideo();
        saveDatamartMeta(user, dm1, "weather", daysAgo(2));

        long up2 = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(21), daysAgo(21));
        long f2 = newFrame(up2, 0L);
        saveUploadLabel(user, f2, daysAgo(3));

        long dm2 = newDatamartVideo();
        saveDatamartMeta(user, dm2, "weather", daysAgo(4));

        // ★ 아직 아무 저작물도 없는 업로드 — 정렬 키를 등록일로 대신하므로 <목록 한가운데>에 앉는다.
        //   이 자리가 요점이다. 「저장이 없는 행은 뒤로」로 두면 방금 올린 자산이 마지막 쪽으로
        //   가라앉아 「업로드 자산은 전부 싣는다」가 사실상 무력해진다.
        long up3 = newUpload(user, PortalUploadLedger.STATUS_UPLOADED, hoursAgo(60), hoursAgo(60));

        List<Long> paged = new java.util.ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Page<PortalUserWorkResponse> p = service.listUserWorks(user, PageRequest.of(page, 2));
            assertThat(p.getTotalElements()).as("총 건수는 같은 합집합에서 센다").isEqualTo(5);
            p.getContent().forEach(r -> paged.add(r.rawSn()));
        }

        assertThat(paged)
                .as("★쪽을 이어 붙여도 마지막 저장 시각 내림차순이 유지되고 중복·누락이 없다")
                .containsExactly(up1, dm1, up3, up2, dm2);
    }

    @Test
    @DisplayName("총_건수는_두_축의_합이며_목록과_같은_합집합에서_센다")
    void totalElementsCountsTheSameUnion() {
        String user = newUser();
        newUpload(user, PortalUploadLedger.STATUS_UPLOADED, daysAgo(1), daysAgo(1));
        long dm = newDatamartVideo();
        saveDatamartAnnotation(user, dm, daysAgo(1));
        long noWork = newDatamartVideo();          // 저작물 없음 — 세지 않는다
        newFrame(noWork, 0L);

        Page<PortalUserWorkResponse> page = service.listUserWorks(user, PageRequest.of(0, 1));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
    }

    // ==================================================== 만료 — 한 목록에 두 규칙

    /**
     * ★★ 같은 목록의 두 행이 <b>서로 다른 규칙</b>으로 계산된 만료를 보여주는 것이 정상이다.
     * 데이터마트는 저작 <b>최초</b> 저장 기산, 업로드 READY 는 등록일과 라벨 마지막 저장일 중
     * <b>늦은 쪽</b> 기산이다. 한 규칙으로 통일하면 둘 중 하나가 반드시 틀린다.
     */
    @Test
    @DisplayName("★★한_목록에_데이터마트_규칙과_업로드_규칙으로_계산된_만료가_섞여_실린다")
    void twoRetentionRulesCoexistInOneList() {
        assumeSeededRetention();
        String user = newUser();

        long dm = newDatamartVideo();
        long dmFrame = newFrame(dm, 0L);
        saveDatamartLabel(user, dm, dmFrame, daysAgo(30));   // 최초 저장 30일 전
        saveDatamartMeta(user, dm, "weather", daysAgo(2));   // 마지막 저장은 최근

        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(20), daysAgo(20));
        long upFrame = newFrame(up, 0L);
        saveUploadLabel(user, upFrame, daysAgo(1));          // 마지막 작업 1일 전

        Map<Long, PortalUserWorkResponse> rows = byRawSn(list(user));

        assertThat(rows.get(dm).expiresOn())
                .as("데이터마트 — 최초 저장(30일 전) + 7일")
                .isEqualTo(daysAgo(30).plusDays(7).toLocalDate());
        assertThat(rows.get(up).expiresOn())
                .as("업로드 READY — 늦은 쪽(라벨 1일 전) + 7일")
                .isEqualTo(daysAgo(1).plusDays(7).toLocalDate());
    }

    /**
     * ★ 데이터마트 행의 만료를 <b>마지막 저장</b>에서 계산하면 저장할 때마다 만료가 뒤로 밀려 자동
     * 삭제가 영영 오지 않는다(2026-09-04 에 고친 결함). 최초와 마지막을 크게 벌려 축을 가른다.
     */
    @Test
    @DisplayName("★저장을_반복해도_데이터마트_행의_만료가_뒤로_밀리지_않는다")
    void repeatedDatamartSavesDoNotPushExpiry() {
        assumeSeededRetention();
        String user = newUser();
        long dm = newDatamartVideo();
        long frame = newFrame(dm, 0L);
        saveDatamartLabel(user, dm, frame, daysAgo(30));

        var before = list(user).get(0).expiresOn();
        saveDatamartLabel(user, dm, frame, LocalDateTime.now());   // 재작업으로 방금 다시 저장
        var after = list(user).get(0).expiresOn();

        assertThat(after).isEqualTo(before).isEqualTo(daysAgo(30).plusDays(7).toLocalDate());
    }

    /**
     * ★ 만료 기산점은 세 저작물을 <b>통틀어</b> 가장 이른 저장이다 — 라벨만 보면 메타를 먼저 고친
     * 사용자의 기산점이 실제보다 늦어져 고지가 실제 삭제일보다 뒤로 간다.
     */
    @Test
    @DisplayName("★기산점은_라벨이_아니라_세_저작물_중_가장_이른_저장이다")
    void expiryBaselineIsEarliestAcrossAllThreeWorks() {
        assumeSeededRetention();
        String user = newUser();
        long dm = newDatamartVideo();
        long frame = newFrame(dm, 0L);
        saveDatamartMeta(user, dm, "weather", daysAgo(30));     // 메타를 먼저 고쳤다
        saveDatamartLabel(user, dm, frame, daysAgo(2));         // 라벨은 나중

        assertThat(list(user).get(0).expiresOn())
                .as("라벨만 보면 5일 뒤가 되어 실제 삭제일보다 한참 뒤를 고지한다")
                .isEqualTo(daysAgo(30).plusDays(7).toLocalDate());
    }

    // ==================================================== 두 축은 갈린다 (구 동치성 시험의 재정의)

    /**
     * ★ 라벨만 있는 자산에서는 두 축이 <b>우연히 같다</b> — 그래서 이 조합만 보면 둘이 한 값이라고
     * 착각하게 된다. 아래 시험이 그 착각을 깬다.
     *
     * <p>⚠ 이 시험은 구 「동치다」 시험의 자리를 잇지만 <b>뜻이 바뀌었다</b> — 동치를 요구하는 것이
     * 아니라 <b>라벨만 있을 때의 일치</b>를 적어 두는 것이다. 두 축이 이제 다른 것을 세므로
     * 「동치다」로 되돌리면 아래 시험과 정면으로 부딪힌다.
     */
    @Test
    @DisplayName("라벨만_있는_자산에서는_표시축과_보존_입력축이_같은_값이다")
    void bothAxesAgreeWhenOnlyLabelsExist() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(10), daysAgo(10));
        long frame = newFrame(up, 0L);
        saveUploadLabel(user, frame, daysAgo(5));
        saveUploadLabel(user, frame, daysAgo(2));

        LocalDateTime fromList = list(user).get(0).lastSavedAt();
        LocalDateTime fromRetention = assetRepository.findLastLabelSavedAt(user, List.of(up)).get(up);

        assertThat(fromList).isNotNull().isEqualTo(fromRetention);
    }

    /**
     * ★★ <b>두 축은 다른 값이 될 수 있고 그것이 정상이다.</b>
     *
     * <p>표시·정렬·내려받기 판정축은 저작 편집 셋을 세고, 보존 입력축은 <b>라벨 마지막 저장일</b>만
     * 본다. 라벨 없이 메타만 고친 자산에서 갈린다 — 여기서 두 값을 「일관성」을 이유로 합치면,
     * 확정되지 않은 사양(업로드 축 기산의 범위)을 넓혀 <b>비가역 삭제 시점을 미루게 된다</b>.
     */
    @Test
    @DisplayName("★★라벨_없이_메타만_고치면_표시축과_보존_입력축이_갈린다_합치지_말_것")
    void axesDivergeWhenOnlyMetaEdited() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(10), daysAgo(10));
        newFrame(up, 0L);
        saveUploadMeta(user, up, "note", daysAgo(1));

        LocalDateTime fromList = list(user).get(0).lastSavedAt();

        assertThat(fromList).as("표시축은 메타 편집을 센다").isNotNull();
        assertThat(assetRepository.findLastLabelSavedAt(user, List.of(up)))
                .as("보존 입력축은 라벨만 보므로 키 자체가 없다")
                .doesNotContainKey(up);
    }

    // ==================================================== 이어서 작업 진입점

    @Test
    @DisplayName("★진입_대상_프레임은_마지막으로_저장한_라벨의_프레임이다")
    void entryFrameIsTheLastSavedLabelFrame() {
        String user = newUser();
        long up = newUpload(user, PortalUploadLedger.STATUS_READY, daysAgo(10), daysAgo(10));
        long first = newFrame(up, 0L);
        long second = newFrame(up, 1L);
        saveUploadLabel(user, first, daysAgo(5));
        saveUploadLabel(user, second, daysAgo(1));

        assertThat(list(user).get(0).entrySrcSn()).isEqualTo(second);
    }

    /**
     * ★ 저장 라벨 없이 메타·어노테이션만 고친 행은 「마지막 저장 프레임」이 성립하지 않는다 —
     * 그 경우 <b>첫 프레임</b>을 연다. 값이 비면 화면이 어디로 들어갈지 모른다.
     *
     * <p>그래서 이 필드는 <b>진입 대상</b>이지 저장 이력이 아니다 — 그 판정은 {@code lastSavedAt} 몫이다.
     */
    /**
     * ★★ 과다 노출 축 — 들어갈 수 없는 행에 프레임이 실리면 화면이 <b>활성 링크를 그리고</b>
     * 눌러야 거부된다. 응답이 미리 막을 입력을 줘야 한다.
     *
     * <p>⚠ 이 좁힘은 서버 진입 가드를 <b>대신하지 않는다</b> — 주소를 직접 치는 경로는 그 가드가 막는다.
     */
    @Test
    @DisplayName("★★노출_조건을_잃은_데이터마트_행은_프레임이_있어도_진입_대상이_빈다")
    void notExposedDatamartRowHasNoEntryFrame() {
        String user = newUser();
        long dm = newDatamartVideoNotExposed();
        newFrame(dm, 0L);
        saveDatamartMeta(user, dm, "weather", daysAgo(1));

        assertThat(list(user)).singleElement().satisfies(r -> {
            assertThat(r.entrySrcSn()).as("★들어갈 수 없으면 비운다").isNull();
            assertThat(r.rawSn()).as("★그래도 행은 목록에 남는다").isEqualTo(dm);
        });
    }

    /** ★ 진입 불가 행을 목록에서 빼면 <b>삭제 예고가 함께 사라진다</b> — 만료는 그대로 실린다. */
    @Test
    @DisplayName("★진입_불가_행도_만료_고지를_그대로_싣는다")
    void notExposedRowStillCarriesExpiry() {
        assumeSeededRetention();
        String user = newUser();
        long dm = newDatamartVideoNotExposed();
        newFrame(dm, 0L);
        saveDatamartMeta(user, dm, "weather", daysAgo(30));

        assertThat(list(user).get(0).expiresOn())
                .isEqualTo(daysAgo(30).plusDays(7).toLocalDate());
    }

    /**
     * ★★ 「행은 있으나 승인이 아님」 갈래 — <b>승인 판정 리터럴이 실제로 실행되는</b> 유일한 픽스처다.
     *
     * <p>상태 행이 아예 없는 갈래만 세우면 그 리터럴을 무력화해도 목록 시험이 죽지 않는다(단위 시험은
     * 판정기를 모의하므로 덮어 주지 못한다). 그 공백을 닫는 것이 이 시험의 존재 이유다.
     */
    @Test
    @DisplayName("★★상태_행은_있으나_승인이_아닌_데이터마트_행도_진입_대상이_빈다")
    void nonApprovedStatusRowAlsoBlocksEntry() {
        String user = newUser();
        long dm = newDatamartVideoWithStatus("PENDING");
        newFrame(dm, 0L);
        saveDatamartMeta(user, dm, "weather", daysAgo(1));

        assertThat(list(user)).singleElement().satisfies(r -> {
            assertThat(r.entrySrcSn()).as("★승인이 아니면 들어갈 수 없다").isNull();
            assertThat(r.rawSn()).as("★그래도 행은 목록에 남는다").isEqualTo(dm);
        });
    }

    /**
     * ★★ 한 페이지에 <b>노출·비노출이 섞여</b> 있을 때 <b>행마다</b> 갈리는지 본다.
     *
     * <p>일괄 판정이 「하나라도 노출이면 전부 노출」처럼 뭉개지거나 요청과 다른 식별자로 답하면
     * 여기서만 드러난다 — 단건 시험은 페이지에 한 행뿐이라 그 뭉갬을 통과시킨다.
     */
    @Test
    @DisplayName("★★한_페이지에_노출_행과_비노출_행이_섞이면_진입_대상이_행마다_갈린다")
    void entryTargetIsDecidedPerRowWithinOnePage() {
        String user = newUser();
        long exposed = newDatamartVideo();                       // 승인
        long exposedFrame = newFrame(exposed, 0L);
        saveDatamartMeta(user, exposed, "weather", daysAgo(1));

        long rejected = newDatamartVideoWithStatus("REJECTED");   // 행은 있으나 승인 아님
        newFrame(rejected, 0L);
        saveDatamartMeta(user, rejected, "weather", daysAgo(2));

        long absent = newDatamartVideoNotExposed();               // 상태 행 자체가 없음
        newFrame(absent, 0L);
        saveDatamartMeta(user, absent, "weather", daysAgo(3));

        Map<Long, PortalUserWorkResponse> rows = byRawSn(list(user));

        assertThat(rows).as("세 행 모두 목록에는 남는다").containsOnlyKeys(exposed, rejected, absent);
        assertThat(rows.get(exposed).entrySrcSn())
                .as("★노출 행만 진입 대상을 갖는다").isEqualTo(exposedFrame);
        assertThat(rows.get(rejected).entrySrcSn()).as("승인이 아닌 행").isNull();
        assertThat(rows.get(absent).entrySrcSn()).as("상태 행이 없는 행").isNull();
    }

    @Test
    @DisplayName("★저장_라벨이_없으면_첫_프레임을_진입_대상으로_내린다")
    void entryFrameFallsBackToFirstFrame() {
        String user = newUser();
        long dm = newDatamartVideo();
        long first = newFrame(dm, 0L);
        newFrame(dm, 1L);
        saveDatamartMeta(user, dm, "weather", daysAgo(1));

        assertThat(list(user).get(0).entrySrcSn()).isEqualTo(first);
    }
}
