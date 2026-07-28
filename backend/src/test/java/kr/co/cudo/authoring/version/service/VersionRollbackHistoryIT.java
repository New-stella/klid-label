package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 롤백 이력(D-ISSUE-21) · 멱등 롤백 no-op(D-ISSUE-24) · 승인 스냅샷 AI 메타/정렬 결정성 통합 테스트.
 *
 * <p>Testcontainers PostgreSQL 위에서 실행한다 — 버전 행 재활성/이력 적재/명시 PK 보존은 실 DB 동작이라
 * mock 단위테스트로 갈음할 수 없다({@code VersionRollbackRestoreIT} 와 동일 픽스처 스타일).
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VersionRollbackHistoryIT {

    @Autowired private VersionService versionService;
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private LsDataLblHstryRepository labelHistoryRepository;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsDataLblAiInfoRepository aiInfoRepository;
    @Autowired private LsTaskAssignmentRepository assignmentRepository;
    @Autowired private WorkLockService workLockService;
    @Autowired private ObjectMapper objectMapper;

    private Long rawSn;
    private Long srcSn;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        LsDataRaw raw = rawRepository.save(LsDataRaw.createFromIngest(
                "CLIP-RBH-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4", LocalDateTime.now(), 30));
        rawSn = raw.getRawSn();
        srcSn = srcRepository.save(LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now()))
                .getSrcSn();
        assignmentRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));
        workLockService.releaseRaw(rawSn, "test", "TEST_SETUP");
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    /**
     * 이 테스트가 적재한 롤백 이력을 정리한다 — SRC_SN 은 IDENTITY 채번이라 다른 테스트가 하드코딩한
     * 프레임 번호와 겹칠 수 있어(예: LsDataLblHstryRepositoryTest 의 SRC_SN=50) 잔여 행이 오염을 만든다.
     */
    @AfterEach
    void tearDown() {
        labelHistoryRepository.deleteAll(labelHistoryRepository.findBySrcSnOrderByRegDtDesc(srcSn));
    }

    // ---------- 픽스처 ----------

    private LsDataLbl seedLabel(String label, String pointsJson) {
        return labelRepository.save(
                LsDataLbl.createManual(srcSn, LsDataLbl.TYPE_BBOX, null, label, pointsJson, 100L));
    }

    private LsLabelVersion activeVersion() {
        return labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn).stream()
                .filter(v -> LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn()))
                .findFirst().orElseThrow();
    }

    private LsLabelVersion seedVersion(String hash, String payload, int versionNo, boolean active) {
        LsLabelVersion v = LsLabelVersion.create(rawSn, srcSn, hash, payload, versionNo,
                LsLabelVersion.SAVE_REASON_APPROVED, "100");
        if (!active) {
            v.deactivate();
        }
        return labelVersionRepository.save(v);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- D-21: 롤백 이력 ----------

    @Test
    @DisplayName("롤백_수행시_누가_언제_어느버전으로_되돌렸는지_이력에_기록된다")
    void rollbackRecordsActorTimeAndTargetVersion() throws Exception {
        // given — v1(person) 승인 → 라벨 교체(car) → v2 승인.
        Long id1 = seedLabel("person", "[[10.0,10.0],[50.0,50.0]]").getLblSn();
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = activeVersion().getVersionHash();

        labelRepository.deleteAllByIdInBatch(List.of(id1));
        seedLabel("car", "[[1.0,1.0],[2.0,2.0]]");
        versionService.commitApproved(rawSn, reviewer);
        assertThat(labelHistoryRepository.findBySrcSnOrderByRegDtDesc(srcSn)).isEmpty();

        LocalDateTime before = LocalDateTime.now().minusSeconds(5);

        // when — REVIEWER(sub=1)가 v1 로 롤백.
        versionService.rollback(v1Hash, srcSn, reviewer);

        // then — 기존 이력 축(LS_DATA_LBL_HSTRY)에 롤백 행위가 1건 기록된다.
        List<LsDataLblHstry> events = labelHistoryRepository.findBySrcSnOrderByRegDtDesc(srcSn);
        assertThat(events).hasSize(1);
        LsDataLblHstry ev = events.get(0);
        assertThat(ev.getSrcSn()).isEqualTo(srcSn);
        assertThat(ev.getRegId()).isEqualTo("1");
        assertThat(ev.getRegDt()).isAfter(before);

        // "어느 버전으로" — 롤백 대상 버전 해시가 diff 페이로드 봉투에 담긴다(신규 컬럼 없음).
        JsonNode dtl = objectMapper.readTree(ev.getChgDtlCn());
        assertThat(dtl.path("rollbackToVersionHash").asText()).isEqualTo(v1Hash);
        // 라벨 교체 델타도 함께 기록된다(car 삭제 + person 복원).
        assertThat(ev.getDelCnt() + ev.getAddCnt()).isGreaterThan(0);
    }

    @Test
    @DisplayName("롤백_픽스처가_실제_sha256_이고_기존행_재활성을_기대한다")
    void rollbackWithRealSha256ReactivatesExistingRow() {
        // given — 두 버전 모두 payload 의 실제 SHA-256 으로 시드(프로덕션 불변식).
        //   구 테스트는 40자 가짜 해시로 도달 불가 분기를 통과시켜 회귀 방어력이 없었다(D-ISSUE-21).
        String pastPayload = "{\"srcSn\":" + srcSn + ",\"frameNo\":0,\"items\":[]}";
        String currentPayload = "{\"srcSn\":" + srcSn + ",\"frameNo\":0,\"items\":[{\"id\":1}]}";
        String pastHash = sha256Hex(pastPayload);
        LsLabelVersion past = seedVersion(pastHash, pastPayload, 1, false);
        seedVersion(sha256Hex(currentPayload), currentPayload, 2, true);

        // when
        LsLabelVersion result = versionService.rollback(pastHash, srcSn, reviewer);

        // then — 기존 행 재활성(적층 없음)이 정본이다.
        assertThat(result.getLabelVersionSn()).isEqualTo(past.getLabelVersionSn());
        assertThat(result.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        List<LsLabelVersion> all = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(all).hasSize(2);
        assertThat(all).extracting(LsLabelVersion::getSaveReasonCd)
                .containsOnly(LsLabelVersion.SAVE_REASON_APPROVED);
        assertThat(all.stream().filter(v -> LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn())).count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("도달불가_ROLLBACK_적층분기가_제거되고_참조가_남지_않는다")
    void unreachableRollbackStackingBranchRemoved() {
        // 코드 상수 제거 — 프로덕션에서 쓰이지 않는 SAVE_REASON_ROLLBACK 참조가 남아있지 않다.
        assertThat(Arrays.stream(LsLabelVersion.class.getFields()).map(Field::getName))
                .doesNotContain("SAVE_REASON_ROLLBACK");

        // 저장 해시가 payload 와 불일치하는(=구 위양성 픽스처 형태) 버전으로 롤백해도 새 행이 적층되지 않는다.
        String legacyHash = "feedface1234567890abcdef1234567890abcdef";
        String payload = "{\"srcSn\":" + srcSn + ",\"frameNo\":0,\"items\":[]}";
        LsLabelVersion legacy = seedVersion(legacyHash, payload, 1, true);

        LsLabelVersion result = versionService.rollback(legacyHash, srcSn, reviewer);

        assertThat(result.getLabelVersionSn()).isEqualTo(legacy.getLabelVersionSn());
        List<LsLabelVersion> all = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_APPROVED);
    }

    // ---------- D-24: 멱등 롤백 = 진짜 no-op ----------

    @Test
    @DisplayName("멱등_롤백은_라벨을_삭제_재생성하지_않고_LBL_SN_이_불변이다")
    void idempotentRollbackDoesNotRewriteLabels() {
        // given — 라벨 2건(하나는 AI 메타 보유)으로 승인 스냅샷 생성(= 현재 active).
        Long id1 = seedLabel("person", "[[10.0,10.0],[50.0,50.0]]").getLblSn();
        Long id2 = seedLabel("car", "[[1.0,1.0],[2.0,2.0]]").getLblSn();
        Long aiInfoSn = aiInfoRepository.save(LsDataLblAiInfo.create(id1, rawSn, srcSn,
                LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.70000"), "1")).getDataLblAiInfoSn();
        versionService.commitApproved(rawSn, reviewer);
        LsLabelVersion active = activeVersion();

        // when — 현재 active 와 동일한 버전으로 롤백.
        LsLabelVersion result = versionService.rollback(active.getVersionHash(), srcSn, reviewer);

        // then — 라벨 식별자가 그대로(delete+insert 미수행).
        assertThat(labelRepository.findBySrcSn(srcSn)).extracting(LsDataLbl::getLblSn)
                .containsExactlyInAnyOrder(id1, id2);
        // AI 메타 행도 재작성되지 않는다 — 교체 경로였다면 삭제 후 재삽입되어 PK 가 바뀐다.
        assertThat(aiInfoRepository.findByDataLblSnIn(List.of(id1)))
                .extracting(LsDataLblAiInfo::getDataLblAiInfoSn)
                .containsExactly(aiInfoSn);
        // 버전 행/active 도 그대로이고, no-op 이므로 롤백 이력도 남지 않는다.
        assertThat(result.getLabelVersionSn()).isEqualTo(active.getLabelVersionSn());
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(1);
        assertThat(labelHistoryRepository.findBySrcSnOrderByRegDtDesc(srcSn)).isEmpty();
    }

    // ---------- 이월-1: 승인 스냅샷 AI 메타 ----------

    @Test
    @DisplayName("승인_스냅샷_payload_에_AI메타가_담긴다")
    void approvedSnapshotCarriesAiMeta() throws Exception {
        // given — 오토라벨 출처/신뢰도를 가진 라벨.
        Long id = seedLabel("person", "[[10.0,10.0],[50.0,50.0]]").getLblSn();
        aiInfoRepository.save(LsDataLblAiInfo.create(id, rawSn, srcSn,
                LsDataLblAiInfo.SRC_YOLO, new BigDecimal("0.87000"), "1"));

        // when
        versionService.commitApproved(rawSn, reviewer);

        // then — 스냅샷 item 에 출처/신뢰도/자동라벨 여부가 담겨야 복원(D-ISSUE-23)이 실효를 갖는다.
        JsonNode item = objectMapper.readTree(activeVersion().getLabelPayload()).path("items").get(0);
        assertThat(item.path("id").asLong()).isEqualTo(id);
        assertThat(item.path("lblSrcCd").asText()).isEqualTo(LsDataLblAiInfo.SRC_YOLO);
        assertThat(item.path("autoLblYn").asText()).isEqualTo(LsDataLbl.AUTO_YES);
        assertThat(item.path("confScore").decimalValue()).isEqualByComparingTo("0.87000");
    }

    @Test
    @DisplayName("AI메타_왕복_롤백후_출처와_신뢰도가_복원된다")
    void aiMetaSurvivesApproveRollbackRoundTrip() {
        // given — AI 메타 보유 라벨로 v1 승인 → AI 메타 삭제·라벨 교체.
        Long id = seedLabel("person", "[[10.0,10.0],[50.0,50.0]]").getLblSn();
        aiInfoRepository.save(LsDataLblAiInfo.create(id, rawSn, srcSn,
                LsDataLblAiInfo.SRC_SAM2, new BigDecimal("0.42000"), "1"));
        versionService.commitApproved(rawSn, reviewer);
        String v1Hash = activeVersion().getVersionHash();

        aiInfoRepository.deleteAll(aiInfoRepository.findByDataLblSnIn(List.of(id)));
        labelRepository.deleteAllByIdInBatch(List.of(id));
        seedLabel("car", "[[1.0,1.0],[2.0,2.0]]");

        // when
        versionService.rollback(v1Hash, srcSn, reviewer);

        // then — 스냅샷에 담긴 출처/신뢰도가 되살아난다.
        List<LsDataLblAiInfo> ai = aiInfoRepository.findByDataLblSnIn(List.of(id));
        assertThat(ai).hasSize(1);
        assertThat(ai.get(0).getLblSrcCd()).isEqualTo(LsDataLblAiInfo.SRC_SAM2);
        assertThat(ai.get(0).getConfScore()).isEqualByComparingTo("0.42000");
    }

    // ---------- 이월-2: 스냅샷 items 순서 결정성 ----------

    @Test
    @DisplayName("동일_라벨_집합은_조회순서와_무관하게_동일_해시를_만든다")
    void snapshotItemsOrderIsDeterministic() throws Exception {
        // given — 라벨 3건.
        Long a = seedLabel("person", "[[1.0,1.0],[2.0,2.0]]").getLblSn();
        Long b = seedLabel("car", "[[3.0,3.0],[4.0,4.0]]").getLblSn();
        Long c = seedLabel("bike", "[[5.0,5.0],[6.0,6.0]]").getLblSn();

        // when
        versionService.commitApproved(rawSn, reviewer);
        LsLabelVersion v1 = activeVersion();

        // then — items 는 LBL_SN 오름차순으로 고정(heap 순서 의존 제거) → 동일 집합 = 동일 해시.
        JsonNode items = objectMapper.readTree(v1.getLabelPayload()).path("items");
        assertThat(items.size()).isEqualTo(3);
        List<Long> ids = List.of(items.get(0).path("id").asLong(),
                items.get(1).path("id").asLong(), items.get(2).path("id").asLong());
        assertThat(ids).containsExactly(a, b, c).isSorted();
        assertThat(v1.getVersionHash()).isEqualTo(sha256Hex(v1.getLabelPayload()));

        // 재승인(무변경)은 동일 해시로 판정되어 새 버전을 만들지 않는다.
        versionService.commitApproved(rawSn, reviewer);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(1);
    }
}
