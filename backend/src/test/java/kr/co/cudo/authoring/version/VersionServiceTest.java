package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 — VersionService 통합 테스트 (DB 스냅샷 기반).
 *
 * <p>외부 버전관리 서버 제거 이후 버전/이력은 LS_LABEL_VERSION (versionHash + labelPayload) 에만 저장된다.
 * commit→스냅샷 저장, diff(두 스냅샷 비교), rollback(스냅샷 복원), 접근권한(IDOR),
 * 비식별 재처리 잠금(409), 페이로드 한도를 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VersionServiceTest {

    @Autowired private VersionService versionService;
    @Autowired private LabelService labelService;
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private WorkLockService workLockService;

    private Long srcSn;
    private Long rawSn;

    private TokenClaims reviewer;
    private TokenClaims workerAssigned;
    private TokenClaims portalUser;

    @BeforeEach
    void setup() {
        // LsLabelVersion 시드 정리 — 이 테스트 동안 새로 만든 row 만 남도록.
        labelVersionRepository.deleteAll();

        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-VER-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        srcSn = srcRepository.save(LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now()))
                .getSrcSn();

        // 작업자 100 만 배정
        authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L));

        // 이전 테스트 잔여 잠금 정리.
        workLockService.releaseRaw(rawSn, "test", "TEST_SETUP");

        Instant exp = Instant.now().plusSeconds(60);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, exp);
        workerAssigned = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, exp);
        portalUser = new TokenClaims("100", Role.PORTAL_USER, Channel.PORTAL, exp);
    }

    private LsLabelVersion seed(String versionHash, String payload, int versionNo, boolean active) {
        LsLabelVersion v = LsLabelVersion.create(rawSn, srcSn, versionHash, payload,
                versionNo, LsLabelVersion.SAVE_REASON_MANUAL, "100");
        if (!active) {
            v.deactivate();
        }
        return labelVersionRepository.save(v);
    }

    // ---------- commit ----------

    @Test
    @DisplayName("라벨_저장_성공시_DB_스냅샷_INSERT_+_versionHash_저장")
    void commitWritesSnapshotWithHash() {
        String payload = "{\"items\":[]}";
        String hash = versionService.commit(srcSn, payload, workerAssigned);

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        List<LsLabelVersion> history = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(history).hasSize(1);
        LsLabelVersion saved = history.get(0);
        assertThat(saved.getVersionHash()).isEqualTo(hash);
        assertThat(saved.getLabelPayload()).isEqualTo(payload);
        assertThat(saved.getRegId()).isEqualTo("100");
        assertThat(saved.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        assertThat(saved.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_MANUAL);
    }

    @Test
    @DisplayName("동일_스냅샷_재커밋은_멱등_새_row_생성안함_기존_해시_반환")
    void commitIsIdempotentForSamePayload() {
        String payload = "{\"items\":[{\"id\":1}]}";
        String first = versionService.commit(srcSn, payload, workerAssigned);
        String second = versionService.commit(srcSn, payload, workerAssigned);

        assertThat(second).isEqualTo(first);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).hasSize(1);
    }

    @Test
    @DisplayName("내용_변경_재커밋시_새_active_버전_생성_이전_active_deactivate")
    void commitNewPayloadCreatesNewActiveVersion() {
        versionService.commit(srcSn, "{\"items\":[]}", workerAssigned);
        versionService.commit(srcSn, "{\"items\":[{\"id\":1}]}", workerAssigned);

        List<LsLabelVersion> all = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(all).hasSize(2);
        long activeCount = all.stream()
                .filter(v -> LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn())).count();
        assertThat(activeCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("페이로드_1MB_한도_초과시_INVALID_INPUT")
    void commitRejectsOversizedPayload() {
        String huge = "x".repeat(VersionService.MAX_PAYLOAD_BYTES + 1);
        assertThatThrownBy(() -> versionService.commit(srcSn, huge, workerAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).isEmpty();
    }

    // ---------- 채널 분기: PORTAL → commit skip ----------

    @Test
    @DisplayName("포털_모드_channel_PORTAL_는_커밋_불가_isCommittable_검증")
    void portalChannelSkipsCommit() {
        assertThat(VersionService.isCommittable(portalUser)).isFalse();
        assertThat(VersionService.isCommittable(workerAssigned)).isTrue();
        assertThat(VersionService.isCommittable(reviewer)).isTrue();
    }

    @Test
    @DisplayName("LabelService_bulkUpsert_INTERNAL_채널_시_DB_스냅샷_버전_자동_기록")
    void labelBulkUpsertTriggersSnapshotVersion() {
        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null)
        ));
        labelService.bulkUpsert(srcSn, req, workerAssigned);

        List<LsLabelVersion> history = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getVersionHash()).isNotBlank();
        assertThat(history.get(0).getLabelPayload()).isNotNull();
    }

    // ---------- rollback ----------

    @Test
    @DisplayName("배정된_WORKER가_본인_프레임_rollback_호출시_대상_스냅샷_복원_새_ROLLBACK_버전_생성")
    void assignedWorkerRollbackRestoresSnapshot() {
        String pastPayload = "{\"items\":[{\"id\":7,\"label\":\"car\"}]}";
        seed("feedface1234567890abcdef1234567890abcdef", pastPayload, 1, false);
        seed("0000000000000000000000000000000000000000", "{\"items\":[]}", 2, true);

        LsLabelVersion rollback = versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, workerAssigned);

        assertThat(rollback).isNotNull();
        assertThat(rollback.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_ROLLBACK);
        assertThat(rollback.getLabelPayload()).isEqualTo(pastPayload);
        assertThat(rollback.getRegId()).isEqualTo("100");
        assertThat(rollback.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
    }

    @Test
    @DisplayName("REVIEWER_rollback_정상_동작_새_LS_LABEL_VERSION_생성")
    void reviewerRollbackCreatesNewVersion() {
        String pastPayload = "{\"items\":[{\"id\":1,\"label\":\"person\"}]}";
        seed("feedface1234567890abcdef1234567890abcdef", pastPayload, 1, false);
        seed("0000000000000000000000000000000000000000", "{\"items\":[]}", 2, true);

        LsLabelVersion rollback = versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, reviewer);

        assertThat(rollback).isNotNull();
        assertThat(rollback.getLabelPayload()).isEqualTo(pastPayload);
    }

    @Test
    @DisplayName("미배정_WORKER가_rollback_호출시_FORBIDDEN_accessGuard_차단")
    void unassignedWorkerRollbackForbidden() {
        TokenClaims unassigned = new TokenClaims("999", Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
        seed("feedface1234567890abcdef1234567890abcdef", "{\"items\":[]}", 1, true);

        assertThatThrownBy(() -> versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, unassigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("존재하지_않는_버전_해시_롤백시_NOT_FOUND")
    void unknownHashRollbackReturnsNotFound() {
        assertThatThrownBy(() -> versionService.rollback(
                "0000000000000000000000000000000000000000", srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("잘못된_해시_형식_입력시_INVALID_INPUT")
    void invalidHashFormatRejected() {
        assertThatThrownBy(() -> versionService.rollback("not-a-hash-../etc/passwd", srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ---------- listVersions ----------

    @Test
    @DisplayName("listVersions_빈_커밋_새_영상_은_빈_리스트_반환")
    void listVersionsEmptyForFreshSrc() {
        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listVersions_DB_스냅샷_메타_사용_active_가_current")
    void listVersionsUsesDbSnapshotMetadata() {
        seed("abc1234abc1234abc1234abc1234abc1234abc12", "{\"items\":[]}", 1, true);

        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).commitSha()).isEqualTo("abc1234abc1234abc1234abc1234abc1234abc12");
        assertThat(result.get(0).shortHash()).isEqualTo("abc1234");
        assertThat(result.get(0).isCurrent()).isTrue();
    }

    @Test
    @DisplayName("listVersions_미배정_WORKER_접근시_FORBIDDEN")
    void listVersionsForbiddenForUnassignedWorker() {
        TokenClaims unassigned = new TokenClaims("999", Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
        assertThatThrownBy(() -> versionService.listVersions(srcSn, unassigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ---------- diff (라벨 단위) ----------

    @Test
    @DisplayName("diff_두_스냅샷_labels_JSON_파싱하여_라벨_단위_ADDED_REMOVED_MODIFIED_반환")
    void diffParsesSnapshotsAndClassifiesPerLabel() {
        // id=1: 좌표 이동 → MODIFIED, id=2: from 에만 → REMOVED, id=3: to 에만 → ADDED
        String fromJson = "{\"frameNo\":7,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":2,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[100.0,100.0],[200.0,200.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":7,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[20.0,20.0],[60.0,60.0]]},"
                + "{\"id\":3,\"lblTypeCd\":\"BBOX\",\"label\":\"bike\",\"points\":[[300.0,300.0],[400.0,400.0]]}"
                + "]}";
        String fromHash = "c0fefe11c0fefe11c0fefe11c0fefe11c0fefe11";
        String toHash   = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef0";
        seed(fromHash, fromJson, 1, false);
        seed(toHash, toJson, 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.fromHash()).isEqualTo(fromHash);
        assertThat(resp.toHash()).isEqualTo(toHash);
        assertThat(resp.labels()).hasSize(3);
        assertThat(resp.labels()).extracting(LabelDiffDto::type)
                .containsExactlyInAnyOrder(LabelDiffDto.DiffType.MODIFIED,
                        LabelDiffDto.DiffType.REMOVED,
                        LabelDiffDto.DiffType.ADDED);

        LabelDiffDto modified = resp.labels().stream()
                .filter(l -> l.type() == LabelDiffDto.DiffType.MODIFIED).findFirst().orElseThrow();
        assertThat(modified.objectId()).isEqualTo("1");
        assertThat(modified.frameId()).isEqualTo(7);
        assertThat(modified.before()).isNotNull();
        assertThat(modified.after()).isNotNull();
        assertThat(modified.before().type()).isEqualTo("BBOX");
        assertThat(modified.after().left()).isEqualTo(20.0);

        LabelDiffDto removed = resp.labels().stream()
                .filter(l -> l.type() == LabelDiffDto.DiffType.REMOVED).findFirst().orElseThrow();
        assertThat(removed.objectId()).isEqualTo("2");
        assertThat(removed.before()).isNotNull();
        assertThat(removed.after()).isNull();

        LabelDiffDto added = resp.labels().stream()
                .filter(l -> l.type() == LabelDiffDto.DiffType.ADDED).findFirst().orElseThrow();
        assertThat(added.objectId()).isEqualTo("3");
        assertThat(added.before()).isNull();
        assertThat(added.after()).isNotNull();
    }

    @Test
    @DisplayName("diff_동일_라벨_데이터_비교시_라벨_변화_없음_회귀가드")
    void diffSameSnapshotReturnsEmptyLabels() {
        String sameJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        String fromHash = "abc111abc111abc111abc111abc111abc111abc1";
        String toHash   = "def222def222def222def222def222def222def2";
        seed(fromHash, sameJson, 1, false);
        seed(toHash, sameJson, 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.labels()).isEmpty();
    }

    @Test
    @DisplayName("diff_라벨_shape_변경시_MODIFIED_분류_before_after_좌표_모두_포함")
    void diffShapeChangeClassifiedAsModified() {
        String fromJson = "{\"frameNo\":2,\"items\":["
                + "{\"id\":42,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":2,\"items\":["
                + "{\"id\":42,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[15.0,12.0],[55.0,52.0]]}"
                + "]}";
        String fromHash = "1234abcd1234abcd1234abcd1234abcd1234abcd";
        String toHash   = "5678ef015678ef015678ef015678ef015678ef01";
        seed(fromHash, fromJson, 1, false);
        seed(toHash, toJson, 2, true);

        DiffResponseDto resp = versionService.diff(fromHash, toHash, workerAssigned);

        assertThat(resp.labels()).hasSize(1);
        LabelDiffDto only = resp.labels().get(0);
        assertThat(only.type()).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
        assertThat(only.objectId()).isEqualTo("42");
        assertThat(only.before().left()).isEqualTo(10.0);
        assertThat(only.before().right()).isEqualTo(50.0);
        assertThat(only.after().left()).isEqualTo(15.0);
        assertThat(only.after().right()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("diff_존재하지_않는_from_해시_조회시_NOT_FOUND")
    void diffUnknownFromHashNotFound() {
        seed("aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", "{\"items\":[]}", 1, true);
        assertThatThrownBy(() -> versionService.diff(
                "ffffffffffffffffffffffffffffffffffffffff",
                "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111", workerAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------- Phase 3 — 비식별 재처리 잠금 가드 (WorkLockService) ----------

    @Test
    @DisplayName("Phase3_LS_AUTH_WORK_LOCK_LOCKED_영상_commit_시도시_409_CONFLICT_+_스냅샷_미생성")
    void lockedVideoCommitConflict() {
        workLockService.lockRawForRedeident(rawSn, "100");

        assertThatThrownBy(() -> versionService.commit(srcSn, "{\"items\":[]}", workerAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).isEmpty();
    }
}
