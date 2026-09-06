package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationResponse;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationUpdateRequest;
import kr.co.cudo.authoring.portal.dto.PortalMetaResponse;
import kr.co.cudo.authoring.portal.dto.PortalMetaScope;
import kr.co.cudo.authoring.portal.dto.PortalMetaUpdateRequest;
import kr.co.cudo.authoring.portal.service.PortalColumnMetaField;
import kr.co.cudo.authoring.portal.service.PortalWorkEventAnnotationService;
import kr.co.cudo.authoring.portal.service.PortalWorkMetaService;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포털 메타·이벤트 어노테이션 오버레이를 <b>실 DB</b>로 고정한다.
 *
 * <p>단위 시험은 리포지토리를 mock 하므로 <b>적재 키가 실제로 무엇을 덮어쓰는지</b>도, 판별자를 담은
 * SQL 이 <b>무엇을 거르는지</b>도 확인하지 못한다. 이 기능의 두 위험(단방향 불변식 · 프레임 참조가
 * 비는 행의 유일성)은 그 지점에만 드러난다.
 *
 * @design API-234, API-235, API-236, API-237, ERD-018
 */
@SpringBootTest
@ActiveProfiles("local")
class PortalWorkOverlayIT {

    @Autowired private PortalWorkMetaService metaService;
    @Autowired private PortalWorkEventAnnotationService annotationService;
    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private PortalUploadFrameRepository frameRepository;
    @Autowired private VideoRepository videoRepository;

    private final TransactionTemplate txTemplate;
    private final JdbcTemplate jdbc;

    PortalWorkOverlayIT(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager,
                        @Qualifier("controlDataSource") DataSource dataSource) {
        this.txTemplate = new TransactionTemplate(txManager);
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ==================================================== 픽스처

    private String newUser() {
        return "portal-work-" + System.nanoTime();
    }

    /** 검수 승인(APPROVED) 데이터마트 영상 + 프레임 1건. */
    private long[] newDatamartFrame() {
        long rawSn = txTemplate.execute(s -> videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-" + System.nanoTime(), "cctv-1", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30)).getRawSn());
        // 상태 기계를 거치지 않고 직접 세운다 — 여기서 필요한 것은 「노출 조건이 참인 영상」이다.
        jdbc.update("insert into ls_raw_data_status (raw_data_id, data_stts_cd) values (?, 'APPROVED')", rawSn);
        long srcSn = txTemplate.execute(s ->
                frameRepository.save(LsDataSrc.create(rawSn, 0L, "/frames/0.png", null)).getSrcSn());
        return new long[]{rawSn, srcSn};
    }

    /** 포털 사용자 본인 업로드 자산 + 프레임 1건. */
    private long[] newUploadFrame(String owner) {
        long rawSn = txTemplate.execute(s ->
                assetRepository.insertUploaded(owner, "/portal/v.mp4", "v.mp4", "video/mp4", 100L));
        long srcSn = txTemplate.execute(s ->
                frameRepository.save(LsDataSrc.create(rawSn, 0L, "/portal/frames/0.png", null)).getSrcSn());
        return new long[]{rawSn, srcSn};
    }

    private int overlayMetaCount(String owner, long rawSn) {
        return jdbc.queryForObject(
                "select count(*) from ls_portal_user_meta where portal_user_no = ? and src_raw_sn = ?",
                Integer.class, owner, rawSn);
    }

    private int ledgerMetaCount(long rawSn, String metaKey) {
        return jdbc.queryForObject(
                "select count(*) from ls_data_meta where raw_sn = ? and meta_key = ?",
                Integer.class, rawSn, metaKey);
    }

    private static PortalMetaUpdateRequest req(PortalMetaUpdateRequest.Item... items) {
        return new PortalMetaUpdateRequest(List.of(items));
    }

    /**
     * 컬럼 축(촬영환경·프레임 설명·개인정보 판정)을 걷어낸 <b>키/값 축만</b>.
     *
     * <p>컬럼 축은 원장 행이 있는 한 항상 목록에 담기므로, 키/값 축의 적재 키를 보는 단언은 그 축만
     * 떼어 본다 — 두 축을 섞으면 무엇이 깨졌는지 알 수 없다.
     */
    private static List<PortalMetaResponse.Item> keyValueItems(PortalMetaResponse response) {
        return response.items().stream()
                .filter(i -> !PortalColumnMetaField.isColumnKey(i.metaKey()))
                .toList();
    }

    // ==================================================== 적재 키 (프레임 참조가 비는 행)

    /**
     * ★★ 이 시험이 막는 것 — 프레임이 비는 행에서 유일성이 성립하지 않는 상태.
     *
     * <p>적재 키를 단일 유일 인덱스로 만들면 PostgreSQL 이 NULL 을 서로 다른 값으로 보아 영상 축
     * 행끼리 <b>충돌하지 않는다</b>. 그러면 같은 키를 다시 저장할 때 덮어쓰지 않고 행이 계속 쌓이고,
     * 오류가 없어 어떤 단위 시험에도 걸리지 않는다.
     */
    @Test
    @DisplayName("★영상_축_메타를_같은_키로_다시_저장하면_행이_쌓이지_않고_덮어쓴다")
    void videoScopedSaveOverwritesInsteadOfAccumulating() {
        String user = newUser();
        long[] target = newDatamartFrame();

        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "처음", PortalMetaScope.VIDEO)));
        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "다시", PortalMetaScope.VIDEO)));

        assertThat(overlayMetaCount(user, target[0])).as("같은 적재 키는 한 행이다").isEqualTo(1);
        assertThat(keyValueItems(metaService.load(target[1], user)))
                .singleElement()
                .extracting(PortalMetaResponse.Item::metaVl)
                .isEqualTo("다시");
    }

    @Test
    @DisplayName("프레임_축_메타도_같은_키로_다시_저장하면_덮어쓴다")
    void frameScopedSaveOverwrites() {
        String user = newUser();
        long[] target = newDatamartFrame();

        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "처음", PortalMetaScope.FRAME)));
        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "다시", PortalMetaScope.FRAME)));

        assertThat(overlayMetaCount(user, target[0])).isEqualTo(1);
    }

    @Test
    @DisplayName("같은_키라도_영상_축과_프레임_축은_별개_행으로_공존한다")
    void bothAxesCoexistAsSeparateRows() {
        String user = newUser();
        long[] target = newDatamartFrame();

        metaService.save(target[1], user, req(
                new PortalMetaUpdateRequest.Item("note", "영상", PortalMetaScope.VIDEO),
                new PortalMetaUpdateRequest.Item("note", "프레임", PortalMetaScope.FRAME)));

        assertThat(overlayMetaCount(user, target[0])).isEqualTo(2);
        assertThat(keyValueItems(metaService.load(target[1], user))).hasSize(2);
    }

    /** ★ 영상 축 원소가 프레임에 매달리면 <b>없는 프레임을 가리키는 행</b>이 된다. */
    @Test
    @DisplayName("★영상_축_원소는_프레임_참조가_비어_적재된다")
    void videoScopedRowHasNullFrameReference() {
        String user = newUser();
        long[] target = newDatamartFrame();

        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "값", PortalMetaScope.VIDEO)));

        Integer nulls = jdbc.queryForObject(
                "select count(*) from ls_portal_user_meta where portal_user_no = ? and src_data_src_sn is null",
                Integer.class, user);
        assertThat(nulls).isEqualTo(1);
    }

    // ==================================================== 단방향 — 원본 불변

    /**
     * ★★ 데이터마트 자산의 저장이 원본 원장에 닿으면 <b>확정 학습데이터가 조용히 바뀐다</b>.
     * 단방향 불변식이 깨지는 지점이며, 실 DB 로만 확인된다.
     */
    @Test
    @DisplayName("★데이터마트_자산_저장은_원본_메타_원장을_건드리지_않는다")
    void datamartSaveNeverTouchesLedger() {
        String user = newUser();
        long[] target = newDatamartFrame();

        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "값", PortalMetaScope.VIDEO)));

        assertThat(ledgerMetaCount(target[0], "note")).as("원본 원장에 행이 생기면 안 된다").isZero();
        assertThat(overlayMetaCount(user, target[0])).isEqualTo(1);
    }

    @Test
    @DisplayName("★데이터마트_자산_어노테이션_저장은_원본_어노테이션_원장을_건드리지_않는다")
    void datamartAnnotationSaveNeverTouchesLedger() {
        String user = newUser();
        long[] target = newDatamartFrame();

        annotationService.save(target[0], user,
                new PortalEventAnnotationUpdateRequest(java.util.Map.of("caption", "내 서술")));

        Integer ledger = jdbc.queryForObject(
                "select count(*) from ls_evnt_anno where raw_sn = ?", Integer.class, target[0]);
        Integer overlay = jdbc.queryForObject(
                "select count(*) from ls_portal_user_evnt_anno where portal_user_no = ? and src_raw_sn = ?",
                Integer.class, user, target[0]);
        assertThat(ledger).isZero();
        assertThat(overlay).isEqualTo(1);
    }

    // ==================================================== 자산 출처 갈림

    /**
     * ★★ 전부 오버레이에 넣어도 컴파일되고 대부분의 시험이 통과한다. 그러면 본인 업로드 자산의
     * 메타가 원장에 남지 않아 <b>원장을 읽는 이후 경로가 그것을 못 본다</b>.
     */
    @Test
    @DisplayName("★본인_업로드_자산의_메타는_그_자산의_원장에_들어가고_오버레이는_비어_있다")
    void uploadSaveGoesToLedgerNotOverlay() {
        String user = newUser();
        long[] target = newUploadFrame(user);

        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "내 값", PortalMetaScope.VIDEO)));

        assertThat(ledgerMetaCount(target[0], "note")).as("그 자산의 원장에 그대로 앉는다").isEqualTo(1);
        assertThat(overlayMetaCount(user, target[0])).as("오버레이를 거치지 않는다").isZero();
    }

    @Test
    @DisplayName("★본인_업로드_자산의_어노테이션은_그_자산의_원장에_들어간다")
    void uploadAnnotationGoesToLedger() {
        String user = newUser();
        long[] target = newUploadFrame(user);

        annotationService.save(target[0], user,
                new PortalEventAnnotationUpdateRequest(java.util.Map.of("caption", "내 서술")));

        Integer ledger = jdbc.queryForObject(
                "select count(*) from ls_evnt_anno where raw_sn = ?", Integer.class, target[0]);
        assertThat(ledger).isEqualTo(1);
        PortalEventAnnotationResponse loaded = annotationService.load(target[0], user);
        assertThat(loaded.annotation()).containsEntry("caption", "내 서술");
        assertThat(loaded.overridden()).isFalse();
    }

    @Test
    @DisplayName("업로드_자산_어노테이션을_다시_저장하면_영상당_한_벌이라_덮어쓴다")
    void uploadAnnotationIsSingleRowPerVideo() {
        String user = newUser();
        long[] target = newUploadFrame(user);

        annotationService.save(target[0], user, new PortalEventAnnotationUpdateRequest(java.util.Map.of("v", "1")));
        annotationService.save(target[0], user, new PortalEventAnnotationUpdateRequest(java.util.Map.of("v", "2")));

        Integer rows = jdbc.queryForObject(
                "select count(*) from ls_evnt_anno where raw_sn = ?", Integer.class, target[0]);
        assertThat(rows).isEqualTo(1);
        assertThat(annotationService.load(target[0], user).annotation()).containsEntry("v", "2");
    }

    // ==================================================== IDOR · 판별자

    @Test
    @DisplayName("★남의_업로드_자산에는_저장도_조회도_되지_않는다")
    void otherUsersUploadIsDenied() {
        String alice = newUser();
        String bob = newUser();
        long[] target = newUploadFrame(alice);

        assertThatThrownBy(() -> metaService.load(target[1], bob))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> metaService.save(target[1], bob,
                req(new PortalMetaUpdateRequest.Item("note", "탈취", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        assertThat(ledgerMetaCount(target[0], "note")).isZero();
    }

    /**
     * ★★ 판별자를 <b>실행문 자체에</b> 걸어 둔다는 사실을 고정한다. 후보 조회에만 조건을 걸고
     * 실행문을 비워 두면, 호출부의 출처 판정이 잘못됐을 때 <b>관제 영상의 메타가 덮인다</b>.
     * 그래서 잡·서비스가 아니라 <b>실행문을 직접</b> 부른다.
     */
    @Test
    @DisplayName("★소유자_스코프_실행문은_관제_영상에_0행이다_출처_판정을_우회해도_막힌다")
    void ownerScopedStatementCannotTouchControlVideo() {
        String user = newUser();
        long[] control = newDatamartFrame();

        int metaRows = txTemplate.execute(s ->
                assetRepository.upsertOwnedMeta(control[0], user, "note", "탈취"));
        int annoRows = txTemplate.execute(s ->
                assetRepository.upsertOwnedEventAnnotation(control[0], user, "{\"a\":1}"));

        assertThat(metaRows).isZero();
        assertThat(annoRows).isZero();
        assertThat(ledgerMetaCount(control[0], "note")).isZero();
    }

    /** 소유자만 빠뜨려도 막혀야 한다 — 나머지 조건이 대신 막아 주지 못하는 픽스처다. */
    @Test
    @DisplayName("★출처는_맞는데_소유자가_다르면_실행문이_0행이다")
    void ownerPredicateAloneBlocksWrongUser() {
        String alice = newUser();
        String bob = newUser();
        long[] target = newUploadFrame(alice);

        int rows = txTemplate.execute(s ->
                assetRepository.upsertOwnedMeta(target[0], bob, "note", "탈취"));

        assertThat(rows).isZero();
        assertThat(ledgerMetaCount(target[0], "note")).isZero();
    }

    // ==================================================== 컬럼 축 (촬영환경·프레임 설명·개인정보 판정)

    /** 컬럼 축 원본 값 한 칸을 원장에서 직접 읽는다 — 서비스를 거치지 않아야 「바이트 그대로」를 본다. */
    private String rawColumn(long rawSn, String column) {
        return jdbc.queryForObject("select " + column + " from ls_data_raw where raw_sn = ?",
                String.class, rawSn);
    }

    private String srcColumn(long srcSn, String column) {
        return jdbc.queryForObject("select " + column + " from ls_data_src where src_sn = ?",
                String.class, srcSn);
    }

    /**
     * ★★★ 이 기능의 최상위 불변 — 데이터마트 자산의 <b>원장 컬럼이 바이트 그대로</b>여야 한다.
     *
     * <p>같은 컬럼을 고치는 내부 저장 창구를 재사용하는 것이 「중복 구현을 피하자」는 가장 자연스러운
     * 판단이고, 그 순간 원본이 바뀌면서 재검토 표시·관제 통지·동결본 재동결이 함께 일어난다.
     *
     * <h3>⚠ 이 시험에서 <b>실제로 무는 것과 물지 못하는 것</b> (변이로 확인함)</h3>
     * <p>저장처를 오버레이 → 원장으로 뒤집는 변이를 넣었을 때 <b>바이트 단언은 통과했고</b> 마지막
     * 오버레이 개수 단언만 깨졌다. 뒤집힌 쓰기가 <b>소유자 스코프 실행문</b>을 타는데 관제 영상에는
     * 소유자가 없어 0행이었기 때문이다. 즉 이 시험에서 바이트 단언을 지탱한 것은 저장처 갈림이 아니라
     * <b>판별자</b>이며, 저장처 갈림은 개수 단언과 단위 시험이 붙잡는다.
     * <p>바이트 단언을 남기는 이유는 <b>판별자를 타지 않는 경로</b>(원장 저장 창구 재사용·엔티티 직접
     * 변경)를 겨누기 때문이다. 판별자 자체는 아래 두 시험이 <b>하나씩</b> 떼어 확인한다.
     */
    @Test
    @DisplayName("★데이터마트_자산의_컬럼_축_저장은_원장_컬럼을_바이트_그대로_둔다")
    void datamartColumnSaveLeavesLedgerColumnsUntouched() {
        String user = newUser();
        long[] target = newDatamartFrame();
        String weatherBefore = rawColumn(target[0], "wthr_nm");
        String privacyBefore = rawColumn(target[0], "prvc_incl_yn");
        String descBefore = srcColumn(target[1], "frm_expln");

        metaService.save(target[1], user, req(
                new PortalMetaUpdateRequest.Item(PortalColumnMetaField.Keys.ENV_WEATHER, "비",
                        PortalMetaScope.VIDEO),
                new PortalMetaUpdateRequest.Item(PortalColumnMetaField.Keys.PRIVACY_PRIVACY_INCLUDED, "Y",
                        PortalMetaScope.VIDEO),
                new PortalMetaUpdateRequest.Item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, "내 설명",
                        PortalMetaScope.FRAME)));

        assertThat(rawColumn(target[0], "wthr_nm")).isEqualTo(weatherBefore);
        assertThat(rawColumn(target[0], "prvc_incl_yn")).isEqualTo(privacyBefore);
        assertThat(srcColumn(target[1], "frm_expln")).isEqualTo(descBefore);
        assertThat(overlayMetaCount(user, target[0]))
                .as("세 칸이 전부 오버레이에만 쌓인다").isEqualTo(3);
    }

    /**
     * ★ 개인정보 세 키는 두 축에 <b>같은 이름</b>으로 있다. 축을 함께 보지 않으면 한 행이 다른 행을
     * 덮어 영상 판정과 프레임 판정 가운데 하나가 사라진다.
     */
    @Test
    @DisplayName("★같은_개인정보_키의_영상_축과_프레임_축이_별개_행으로_공존한다")
    void privacyKeyCoexistsOnBothAxesAsSeparateRows() {
        String user = newUser();
        long[] target = newDatamartFrame();
        String key = PortalColumnMetaField.Keys.PRIVACY_PRIVACY_INCLUDED;
        // 적재 기본값은 두 축 모두 'N' 이다. 프레임 축만 'Y' 로 바꿔 두 축의 <원본이 서로 다르게>
        //   만든 뒤, 각 축에 <자기 원본과 다른> 값을 보낸다 — 그래야 승격 방어에 걸리지 않고 둘 다
        //   오버레이가 되며, 저장값도 서로 달라 축이 섞였는지 드러난다.
        jdbc.update("update ls_data_src set prvc_incl_yn = 'Y' where src_sn = ?", target[1]);

        metaService.save(target[1], user, req(
                new PortalMetaUpdateRequest.Item(key, "Y", PortalMetaScope.VIDEO),
                new PortalMetaUpdateRequest.Item(key, "N", PortalMetaScope.FRAME)));

        assertThat(overlayMetaCount(user, target[0])).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select meta_vl from ls_portal_user_meta
                 where portal_user_no = ? and src_raw_sn = ? and src_data_src_sn is null and meta_key = ?
                """, String.class, user, target[0], key)).isEqualTo("Y");
        assertThat(jdbc.queryForObject("""
                select meta_vl from ls_portal_user_meta
                 where portal_user_no = ? and src_data_src_sn = ? and meta_key = ?
                """, String.class, user, target[1], key)).isEqualTo("N");
    }

    /**
     * ★★ 승격 방어 — 화면이 자동 계산값을 그대로 되돌려 보내도 오버레이 행이 생기지 않고, 이미
     * 있으면 지워져 <b>원본으로 되돌아간다</b>.
     */
    @Test
    @DisplayName("★자동_계산값을_되돌려_보내면_오버레이가_생기지_않고_있던_것도_지워진다")
    void echoingDerivedValueLeavesNoOverlayRow() {
        String user = newUser();
        long[] target = newDatamartFrame();
        // ⚠ 개인정보 세 축은 <적재 시점에 실제 값이 INSERT> 되어 저장값(사람이 고른 값)으로 읽힌다.
        //   자동 계산값이 남는 축은 촬영일시에서 파생하는 시간대·계절이라 그 축으로 확인한다.
        String key = PortalColumnMetaField.Keys.ENV_TIME_OF_DAY;

        PortalMetaResponse before = metaService.load(target[1], user);
        PortalMetaResponse.Item origin = before.items().stream()
                .filter(i -> i.scope() == PortalMetaScope.VIDEO && i.metaKey().equals(key))
                .findFirst().orElseThrow();
        assertThat(origin.source().name()).as("이 축은 자동으로 계산된 값이다").isEqualTo("DERIVED");

        // ① 다른 값을 저장하면 오버레이가 생긴다.
        String other = "DAY".equals(origin.metaVl()) ? "NGT" : "DAY";
        metaService.save(target[1], user,
                req(new PortalMetaUpdateRequest.Item(key, other, PortalMetaScope.VIDEO)));
        assertThat(overlayMetaCount(user, target[0])).isEqualTo(1);

        // ② 원본의 현재 유효값(= 자동 계산값)을 되돌려 보내면 그 행이 사라진다.
        metaService.save(target[1], user,
                req(new PortalMetaUpdateRequest.Item(key, origin.metaVl(), PortalMetaScope.VIDEO)));

        assertThat(overlayMetaCount(user, target[0]))
                .as("자동 계산값이 사람의 판정으로 승격되지 않는다").isZero();
    }

    /**
     * ★★ 본인 업로드 자산의 컬럼 축은 오버레이가 아니라 <b>그 자산의 원장 컬럼</b>에 앉는다.
     * 오버레이에 넣으면 원장을 읽는 이후 경로(내려받기·산출 조립)가 그것을 못 본다.
     *
     * <p>저장 직후 응답이 새 값인지도 함께 본다 — 손으로 쓴 UPDATE 는 1차 캐시를 갱신하지 않아
     * 비우지 않으면 <b>옛 값</b>이 응답으로 나간다(오류가 없어 다른 시험에 걸리지 않는다).
     */
    @Test
    @DisplayName("★본인_업로드_자산의_컬럼_축은_그_자산의_원장_컬럼에_들어가고_응답도_새_값이다")
    void uploadColumnSaveLandsOnOwnLedgerColumns() {
        String user = newUser();
        long[] target = newUploadFrame(user);

        PortalMetaResponse saved = metaService.save(target[1], user, req(
                new PortalMetaUpdateRequest.Item(PortalColumnMetaField.Keys.ENV_WEATHER, "눈",
                        PortalMetaScope.VIDEO),
                new PortalMetaUpdateRequest.Item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, "내 프레임 설명",
                        PortalMetaScope.FRAME)));

        assertThat(rawColumn(target[0], "wthr_nm")).isEqualTo("눈");
        assertThat(srcColumn(target[1], "frm_expln")).isEqualTo("내 프레임 설명");
        assertThat(overlayMetaCount(user, target[0])).as("오버레이를 거치지 않는다").isZero();
        assertThat(saved.items())
                .filteredOn(i -> i.metaKey().equals(PortalColumnMetaField.Keys.ENV_WEATHER))
                .singleElement()
                .extracting(PortalMetaResponse.Item::metaVl)
                .as("1차 캐시를 비우지 않으면 옛 값이 응답으로 나간다")
                .isEqualTo("눈");
    }

    /**
     * ★★★ 업로드 자산의 컬럼 축은 <b>자동 계산값과 같은 값이어도 원장에 실제로 앉는다</b>.
     *
     * <p>승격 방어(원본의 현재 유효값과 같으면 쌓지 않는다)는 <b>오버레이의 「행 없음 = 원본을 그대로
     * 쓴다」</b> 성질 위에서만 성립한다. 업로드 자산의 저장 대상은 오버레이가 아니라 <b>원장 그 자체</b>라
     * 그 성질이 없다 — 「행 없음」은 「수동값이 없다」는 다른 뜻이다. 이 시험은 그 방어가 업로드 자산까지
     * 넘어와 <b>쓰기가 통째로 사라지는</b> 상태를 막는다.
     *
     * <p>화면과 산출물이 같은 파생을 다시 적용해 값이 우연히 일치하므로 <b>오차가 눈에 드러나지
     * 않는다</b>. 드러나는 것은 ①사용자의 명시 판정이 기록되지 않고 ②<b>원장 컬럼을 직접 읽는 이후
     * 경로</b>(내려받기·산출 조립)가 빈 값을 보며 ③파생 기본값이 바뀌는 날 그 자산의 값이 조용히
     * 따라 바뀐다는 것이다.
     *
     * <p>⚠ <b>컬럼을 비워 두는 것이 이 시험의 전제</b>다. 적재 시점에 개인정보 세 축이 채워지므로
     * 「비어 있는 컬럼」 상태를 직접 만든다 — 그 상태는 실재한다(원장이 {@code null} 을 <b>미입력</b>으로
     * 규정한다). 비어 있어야 유효값이 <b>자동 계산값</b>이 되고, 그래야 승격 방어가 실제로 걸린다.
     *
     * <p>⚠ 파생 기본값을 하드코딩하지 않고 <b>소유자 상수</b>를 그대로 참조한다 — 기본값이 바뀌어도
     * 이 시험이 계속 같은 경로를 겨눈다.
     */
    @Test
    @DisplayName("★업로드_자산은_자동_계산값과_같은_값이어도_원장_컬럼에_실제로_앉는다")
    void uploadColumnSaveLandsEvenWhenValueEqualsDerived() {
        String user = newUser();
        long[] target = newUploadFrame(user);
        jdbc.update("update ls_data_raw set anony_incl_yn = null where raw_sn = ?", target[0]);
        jdbc.update("update ls_data_src set anony_incl_yn = null where src_sn = ?", target[1]);
        String key = PortalColumnMetaField.Keys.PRIVACY_ANONYMITY;
        String derived = ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY;

        PortalMetaResponse before = metaService.load(target[1], user);
        assertThat(before.items()).filteredOn(i -> i.metaKey().equals(key))
                .allSatisfy(i -> assertThat(i.source().name())
                        .as("비어 있으면 유효값이 자동 계산값이다").isEqualTo("DERIVED"))
                .hasSize(2);

        metaService.save(target[1], user, req(
                new PortalMetaUpdateRequest.Item(key, derived, PortalMetaScope.VIDEO),
                new PortalMetaUpdateRequest.Item(key, derived, PortalMetaScope.FRAME)));

        assertThat(rawColumn(target[0], "anony_incl_yn"))
                .as("사용자의 명시 판정이 그 자산의 원장에 기록된다").isEqualTo(derived);
        assertThat(srcColumn(target[1], "anony_incl_yn")).isEqualTo(derived);
        assertThat(overlayMetaCount(user, target[0])).as("오버레이를 거치지 않는다").isZero();
    }

    /**
     * ★★ 컬럼 축 갱신문도 <b>판별자를 실행문 자체에</b> 건다. 여기서는 출처 판별자만 겨눈다 —
     * 소유자를 채운 관제 영상이라 <b>나머지 조건이 대신 막아 주지 못한다</b>.
     */
    @Test
    @DisplayName("★출처_판별자만_빠져도_컬럼_갱신문이_관제_영상에_닿는다_그것을_막는다")
    void columnStatementSourceTypePredicateAloneBlocksControlVideo() {
        String user = newUser();
        long[] control = newDatamartFrame();
        // 소유자를 채운 관제 영상 — 소유자 조건은 통과시키고 출처 조건만 겨눈다.
        jdbc.update("update ls_data_raw set portal_user_no = ? where raw_sn = ?", user, control[0]);
        String weatherBefore = rawColumn(control[0], "wthr_nm");
        String descBefore = srcColumn(control[1], "frm_expln");

        int videoRows = txTemplate.execute(s -> assetRepository.updateOwnedVideoColumn(
                control[0], user, PortalColumnMetaField.ENV_WEATHER.name(), "비"));
        int frameRows = txTemplate.execute(s -> assetRepository.updateOwnedFrameColumn(
                control[1], user, PortalColumnMetaField.FRAME_DESCRIPTION.name(), "탈취"));

        assertThat(videoRows).isZero();
        assertThat(frameRows).isZero();
        assertThat(rawColumn(control[0], "wthr_nm")).isEqualTo(weatherBefore);
        assertThat(srcColumn(control[1], "frm_expln")).isEqualTo(descBefore);
    }

    /** ★ 소유자 조건만 겨눈다 — 출처는 맞는 포털 자산이라 그 조건이 대신 막아 주지 못한다. */
    @Test
    @DisplayName("★소유자_판별자만_빠져도_컬럼_갱신문이_남의_자산에_닿는다_그것을_막는다")
    void columnStatementOwnerPredicateAloneBlocksWrongUser() {
        String alice = newUser();
        String bob = newUser();
        long[] target = newUploadFrame(alice);

        int videoRows = txTemplate.execute(s -> assetRepository.updateOwnedVideoColumn(
                target[0], bob, PortalColumnMetaField.ENV_WEATHER.name(), "비"));
        int frameRows = txTemplate.execute(s -> assetRepository.updateOwnedFrameColumn(
                target[1], bob, PortalColumnMetaField.FRAME_DESCRIPTION.name(), "탈취"));

        assertThat(videoRows).isZero();
        assertThat(frameRows).isZero();
        assertThat(rawColumn(target[0], "wthr_nm")).isNull();
        assertThat(srcColumn(target[1], "frm_expln")).isNull();
    }

    /**
     * ★ 갱신문은 <b>지목한 칸 하나만</b> 고친다. 칸 고르기가 어긋나면 다른 칸이 함께 바뀌는데
     * 오류가 없어 아무도 알아채지 못한다.
     */
    @Test
    @DisplayName("★컬럼_갱신문은_지목한_칸_하나만_고친다")
    void columnStatementTouchesOnlyTheNamedColumn() {
        String user = newUser();
        long[] target = newUploadFrame(user);
        txTemplate.execute(s -> assetRepository.updateOwnedVideoColumn(
                target[0], user, PortalColumnMetaField.ENV_SEASON.name(), "WINTER"));

        txTemplate.execute(s -> assetRepository.updateOwnedVideoColumn(
                target[0], user, PortalColumnMetaField.ENV_WEATHER.name(), "안개"));

        assertThat(rawColumn(target[0], "wthr_nm")).isEqualTo("안개");
        assertThat(rawColumn(target[0], "sesn_cd"))
                .as("앞서 고른 칸이 뒤 갱신에 지워지면 안 된다").isEqualTo("WINTER");
        assertThat(rawColumn(target[0], "day_ngt_cd")).isNull();
    }

    // ==================================================== 삭제 전파

    @Test
    @DisplayName("원천_영상이_지워지면_오버레이도_함께_지워진다")
    void overlayCascadesOnVideoDelete() {
        String user = newUser();
        long[] target = newDatamartFrame();
        metaService.save(target[1], user, req(new PortalMetaUpdateRequest.Item("note", "값", PortalMetaScope.VIDEO)));
        annotationService.save(target[0], user, new PortalEventAnnotationUpdateRequest(java.util.Map.of("a", "b")));

        jdbc.update("delete from ls_data_src where raw_sn = ?", target[0]);
        jdbc.update("delete from ls_raw_data_status where raw_data_id = ?", target[0]);
        jdbc.update("delete from ls_data_raw where raw_sn = ?", target[0]);

        assertThat(overlayMetaCount(user, target[0])).isZero();
        Integer anno = jdbc.queryForObject(
                "select count(*) from ls_portal_user_evnt_anno where src_raw_sn = ?", Integer.class, target[0]);
        assertThat(anno).isZero();
    }
}
