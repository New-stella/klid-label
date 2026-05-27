package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3+4 — VersionService 통합 테스트.
 *
 * <p>핵심 Phase 3 검증: 비식별 재처리 잠금(LS_AUTH_WORK_LOCK) 영상에서 commit 시도시 409.
 * <p>버전 이력은 LS_LABEL_VERSION 에 저장 — Gitea 응답 또는 fallback (DB 기반) 양쪽 경로 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VersionServiceTest {

    @Autowired private VersionService versionService;
    @Autowired private LabelService labelService;
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private GiteaCommitFallbackQueue fallbackQueue;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;
    @Autowired private WorkLockService workLockService;

    @MockBean private GiteaClient giteaClient;

    private Long srcSn;
    private Long rawSn;

    private TokenClaims reviewer;
    private TokenClaims workerAssigned;
    private TokenClaims portalUser;

    @BeforeEach
    void setup() {
        fallbackQueue.clear();
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

    // ---------- commit 성공/실패 ----------

    @Test
    @DisplayName("라벨_저장_성공시_Gitea_commit_생성_+_LS_LABEL_VERSION_INSERT_+_HASH_저장")
    void commitSuccessWritesHistoryWithHash() {
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "abc1234567890abcdef1234567890abcdef12345", "msg", "100", Instant.now())));

        String sha = versionService.commit(srcSn, "{\"items\":[]}", workerAssigned);

        assertThat(sha).isEqualTo("abc1234567890abcdef1234567890abcdef12345");
        List<LsLabelVersion> history = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getGiteaCmtHash()).isEqualTo("abc1234567890abcdef1234567890abcdef12345");
        assertThat(history.get(0).getRegId()).isEqualTo("100");
        assertThat(fallbackQueue.size()).isZero();
    }

    @Test
    @DisplayName("Gitea_장애시_라벨_저장은_성공_+_재시도_큐_등록_+_LS_LABEL_VERSION_미커밋")
    void giteaFailureEnqueuesRetry() {
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        String sha = versionService.commit(srcSn, "{\"items\":[]}", workerAssigned);

        assertThat(sha).isNull();
        assertThat(fallbackQueue.size()).isEqualTo(1);
        // Gitea 실패 시 saveActiveVersion 이 호출되지 않으므로 LS_LABEL_VERSION row 도 없음.
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).isEmpty();
    }

    // ---------- 채널 분기: PORTAL → commit skip ----------

    @Test
    @DisplayName("포털_모드_channel_PORTAL_는_커밋_호출_안함_isCommittable_검증")
    void portalChannelSkipsCommit() {
        assertThat(VersionService.isCommittable(portalUser)).isFalse();
        assertThat(VersionService.isCommittable(workerAssigned)).isTrue();
        assertThat(VersionService.isCommittable(reviewer)).isTrue();
    }

    @Test
    @DisplayName("LabelService_bulkUpsert_INTERNAL_채널_시_versionService_commit_호출됨")
    void labelBulkUpsertTriggersGiteaCommit() {
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "deadbeefcafebabe1234567890abcdef12345678", "msg", "100", Instant.now())));

        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(10.0, 10.0), List.of(50.0, 50.0)), null)
        ));
        labelService.bulkUpsert(srcSn, req, workerAssigned);

        verify(giteaClient).createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
        List<LsLabelVersion> history = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getGiteaCmtHash()).isEqualTo("deadbeefcafebabe1234567890abcdef12345678");
    }

    // ---------- rollback 권한 ----------

    @Test
    @DisplayName("배정된_WORKER가_본인_프레임_rollback_호출시_정상_LS_LABEL_VERSION_생성")
    void assignedWorkerRollbackCreatesNewHistory() {
        // given: WORKER(100) 가 본인에게 배정된 프레임에 대해 롤백
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, pastSha, 1, "SAVE", "1"));

        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("{\"items\":[]}"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "99998888777766665555444433332222aaaa1111", "rollback", "100", Instant.now())));

        // when
        LsLabelVersion rollback = versionService.rollback(pastSha, srcSn, workerAssigned);

        // then: 새 ROLLBACK 버전 생성
        assertThat(rollback).isNotNull();
        assertThat(rollback.getSaveReasonCd()).isEqualTo(LsLabelVersion.SAVE_REASON_ROLLBACK);
        assertThat(rollback.getGiteaCmtHash()).isEqualTo("99998888777766665555444433332222aaaa1111");
        assertThat(rollback.getRegId()).isEqualTo("100");
    }

    @Test
    @DisplayName("미배정_WORKER가_rollback_호출시_FORBIDDEN_accessGuard_차단")
    void unassignedWorkerRollbackForbidden() {
        // given: WORKER(999) 는 어떤 프레임에도 배정되지 않음
        TokenClaims unassigned = new TokenClaims("999", Role.WORKER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn,
                "feedface1234567890abcdef1234567890abcdef", 1, "SAVE", "1"));

        // when / then: accessGuard 에서 403
        assertThatThrownBy(() -> versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, unassigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(giteaClient, never()).createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("REVIEWER_rollback_정상_동작_새_LS_LABEL_VERSION_생성")
    void reviewerRollbackCreatesNewHistory() {
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, pastSha, 1, "SAVE", "1"));

        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("{\"items\":[{\"label\":\"car\"}]}"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "11112222333344445555666677778888aaaabbbb", "rollback", "1", Instant.now())));

        LsLabelVersion rollback = versionService.rollback(pastSha, srcSn, reviewer);

        assertThat(rollback).isNotNull();
        assertThat(rollback.getGiteaCmtHash()).isEqualTo("11112222333344445555666677778888aaaabbbb");
    }

    // ---------- 잘못된 입력 ----------

    @Test
    @DisplayName("존재하지_않는_커밋_해시_조회시_NOT_FOUND")
    void unknownCommitHashReturnsNotFound() {
        assertThatThrownBy(() -> versionService.rollback(
                "0000000000000000000000000000000000000000", srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("잘못된_SHA_형식_입력시_INVALID_INPUT")
    void invalidShaFormatRejected() {
        assertThatThrownBy(() -> versionService.rollback("not-a-sha-../etc/passwd", srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ---------- listVersions 회귀 방어 ----------

    @Test
    @DisplayName("listVersions_빈_커밋_새_영상_은_빈_리스트_반환")
    void listVersionsEmptyForFreshSrc() {
        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);

        assertThat(result).isEmpty();
        verify(giteaClient, never())
                .listCommits(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("listVersions_gitea_장애시_DB_fallback")
    void listVersionsGiteaFailureFallsBackToDb() {
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn,
                "abc1234abc1234abc1234abc1234abc1234abc12", 1, "SAVE", "100"));

        when(giteaClient.listCommits(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Mono.error(new RuntimeException("gitea 404")));

        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).commitSha()).isEqualTo("abc1234abc1234abc1234abc1234abc1234abc12");
        assertThat(result.get(0).shortHash()).isEqualTo("abc1234");
        assertThat(result.get(0).isCurrent()).isTrue();
    }

    @Test
    @DisplayName("listVersions_gitea_정상_응답_시_커밋_메타_사용")
    void listVersionsUsesGiteaMetadata() {
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn,
                "abc1234abc1234abc1234abc1234abc1234abc12", 1, "SAVE", "100"));

        Instant now = Instant.now();
        when(giteaClient.listCommits(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Mono.just(List.of(
                        new CommitResponse(
                                "abc1234abc1234abc1234abc1234abc1234abc12",
                                "라벨 수정",
                                "100",
                                now)
                )));

        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).message()).isEqualTo("라벨 수정");
        assertThat(result.get(0).committedAt()).isEqualTo(now);
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

    // ---------- diff API — Gitea compare files 비어있을 때 raw content fallback ----------

    @Test
    @DisplayName("getDiff_compare_API_files_미포함시_두_SHA_content_직접_비교하여_변경된_파일_반환")
    void diffFallsBackToRawContentWhenCompareHasNoFiles() {
        // given: 같은 srcSn 의 두 버전 — fromSha / toSha 가 LS_LABEL_VERSION 에 등록되어 있음
        String fromSha = "c9491e1c9491e1c9491e1c9491e1c9491e1c9491";
        String toSha   = "9099ee69099ee69099ee69099ee69099ee69099e";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, fromSha, 1, "SAVE", "100"));
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, toSha,   2, "SAVE", "100"));

        // Gitea compare API 는 files 미포함 응답 (실제 Gitea 사양 재현)
        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of())));

        // 두 SHA 시점의 라벨 파일 내용이 다름 → 변경 감지되어야 함
        String fromContent = "{\"items\":[{\"label\":\"car\"}]}";
        String toContent   = "{\"items\":[{\"label\":\"person\"},{\"label\":\"car\"}]}";
        when(giteaClient.getContent(anyString(), org.mockito.ArgumentMatchers.eq(fromSha), anyString()))
                .thenReturn(Mono.just(fromContent));
        when(giteaClient.getContent(anyString(), org.mockito.ArgumentMatchers.eq(toSha), anyString()))
                .thenReturn(Mono.just(toContent));

        // when
        DiffResponseDto resp = versionService.diff(fromSha, toSha, workerAssigned);

        // then: files 가 비어있지 않고, modified 상태로 표시
        assertThat(resp.fromSha()).isEqualTo(fromSha);
        assertThat(resp.toSha()).isEqualTo(toSha);
        assertThat(resp.files()).isNotEmpty();
        assertThat(resp.files().get(0).path()).isEqualTo("labels/" + srcSn + ".json");
        assertThat(resp.files().get(0).change()).isEqualTo("modified");
    }

    @Test
    @DisplayName("getDiff_동일_내용_비교시_files_빈_배열_회귀가드")
    void diffSameContentReturnsEmpty() {
        String fromSha = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111";
        String toSha   = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, fromSha, 1, "SAVE", "100"));
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, toSha,   2, "SAVE", "100"));

        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of())));
        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("{\"items\":[]}"));

        DiffResponseDto resp = versionService.diff(fromSha, toSha, workerAssigned);

        assertThat(resp.files()).isEmpty();
    }

    @Test
    @DisplayName("getDiff_compare_API가_files_제공해도_라벨_단위_diff_위해_getContent_호출됨")
    void diffUsesCompareFilesWhenProvided() {
        // 2026-05-19: FE 작업이력 패널 라벨 단위 diff 요구로 변경 —
        // compare API 가 files 메타를 제공하더라도, 라벨 단위 변경 산출을 위해 getContent 로 두 SHA 의 라벨 JSON 을 조회한다.
        // 기존 "getContent never" 가드는 제거. files 메타는 그대로 사용한다는 부분만 검증.
        String fromSha = "1111111111111111111111111111111111111111";
        String toSha   = "2222222222222222222222222222222222222222";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, fromSha, 1, "SAVE", "100"));
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, toSha,   2, "SAVE", "100"));

        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of(
                        new kr.co.cudo.authoring.common.client.dto.DiffFile(
                                "labels/" + srcSn + ".json", "modified", 7, 3, "@@ patch ...")))));
        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("{\"items\":[]}"));

        DiffResponseDto resp = versionService.diff(fromSha, toSha, workerAssigned);

        assertThat(resp.files()).hasSize(1);
        assertThat(resp.files().get(0).additions()).isEqualTo(7);
        assertThat(resp.files().get(0).deletions()).isEqualTo(3);
        // 라벨 JSON 이 양쪽 동일한 빈 items → 라벨 단위 변경은 없음
        assertThat(resp.labels()).isEmpty();
    }

    // ---------- diff API — 라벨 단위 분류 (2026-05-19 추가) ----------

    @Test
    @DisplayName("getDiff_두_SHA_labels_JSON_파싱하여_라벨_단위_ADDED_REMOVED_MODIFIED_반환")
    void diffParsesLabelsJsonAndClassifiesPerLabel() {
        // given
        String fromSha = "c0fefe11c0fefe11c0fefe11c0fefe11c0fefe11";
        String toSha   = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, fromSha, 1, "SAVE", "100"));
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, toSha,   2, "SAVE", "100"));

        // id=1: 좌표 이동 → MODIFIED
        // id=2: from 에만 → REMOVED
        // id=3: to 에만 → ADDED
        String fromJson = "{\"frameNo\":7,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":2,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[100.0,100.0],[200.0,200.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":7,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[20.0,20.0],[60.0,60.0]]},"
                + "{\"id\":3,\"lblTypeCd\":\"BBOX\",\"label\":\"bike\",\"points\":[[300.0,300.0],[400.0,400.0]]}"
                + "]}";

        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of())));
        when(giteaClient.getContent(anyString(), org.mockito.ArgumentMatchers.eq(fromSha), anyString()))
                .thenReturn(Mono.just(fromJson));
        when(giteaClient.getContent(anyString(), org.mockito.ArgumentMatchers.eq(toSha), anyString()))
                .thenReturn(Mono.just(toJson));

        // when
        DiffResponseDto resp = versionService.diff(fromSha, toSha, workerAssigned);

        // then: 3건 (ADDED 1 / REMOVED 1 / MODIFIED 1) + frameId=7
        assertThat(resp.labels()).hasSize(3);
        assertThat(resp.labels()).extracting(LabelDiffDto::type)
                .containsExactlyInAnyOrder(LabelDiffDto.DiffType.MODIFIED,
                        LabelDiffDto.DiffType.REMOVED,
                        LabelDiffDto.DiffType.ADDED);
        // 각 라벨의 objectId 매핑
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
    @DisplayName("getDiff_동일_라벨_데이터_비교시_라벨_변화_없음_회귀가드")
    void diffSameLabelsReturnsEmptyLabels() {
        String fromSha = "abc111abc111abc111abc111abc111abc111abc1";
        String toSha   = "def222def222def222def222def222def222def2";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, fromSha, 1, "SAVE", "100"));
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, toSha,   2, "SAVE", "100"));

        String sameJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of())));
        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(sameJson));

        DiffResponseDto resp = versionService.diff(fromSha, toSha, workerAssigned);

        assertThat(resp.labels()).isEmpty();
    }

    @Test
    @DisplayName("getDiff_라벨_shape_변경시_MODIFIED_분류_before_after_좌표_모두_포함")
    void diffShapeChangeClassifiedAsModifiedWithBeforeAfter() {
        String fromSha = "1234abcd1234abcd1234abcd1234abcd1234abcd";
        String toSha   = "5678ef015678ef015678ef015678ef015678ef01";
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, fromSha, 1, "SAVE", "100"));
        labelVersionRepository.save(LsLabelVersion.create(rawSn, srcSn, toSha,   2, "SAVE", "100"));

        String fromJson = "{\"frameNo\":2,\"items\":["
                + "{\"id\":42,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        String toJson = "{\"frameNo\":2,\"items\":["
                + "{\"id\":42,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[15.0,12.0],[55.0,52.0]]}"
                + "]}";
        when(giteaClient.diff(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new DiffResponse(List.of())));
        when(giteaClient.getContent(anyString(), org.mockito.ArgumentMatchers.eq(fromSha), anyString()))
                .thenReturn(Mono.just(fromJson));
        when(giteaClient.getContent(anyString(), org.mockito.ArgumentMatchers.eq(toSha), anyString()))
                .thenReturn(Mono.just(toJson));

        DiffResponseDto resp = versionService.diff(fromSha, toSha, workerAssigned);

        assertThat(resp.labels()).hasSize(1);
        LabelDiffDto only = resp.labels().get(0);
        assertThat(only.type()).isEqualTo(LabelDiffDto.DiffType.MODIFIED);
        assertThat(only.objectId()).isEqualTo("42");
        assertThat(only.before().left()).isEqualTo(10.0);
        assertThat(only.before().right()).isEqualTo(50.0);
        assertThat(only.after().left()).isEqualTo(15.0);
        assertThat(only.after().right()).isEqualTo(55.0);
    }

    // ---------- Phase 3 — 비식별 재처리 잠금 가드 (WorkLockService) ----------

    @Test
    @DisplayName("Phase3_LS_AUTH_WORK_LOCK_LOCKED_영상_commit_시도시_409_CONFLICT_+_Gitea_호출안함_+_LS_LABEL_VERSION_미생성")
    void lockedVideoCommitConflict() {
        // raw 를 WorkLockService 로 잠금 (LS_AUTH_WORK_LOCK INSERT)
        workLockService.lockRawForRedeident(rawSn, "100");

        assertThatThrownBy(() -> versionService.commit(srcSn, "{\"items\":[]}", workerAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(giteaClient, never())
                .createOrUpdateFile(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn)).isEmpty();
    }

    // ---------- commit 메시지 enrichment (2026-05-19 추가) ----------

    /**
     * commit() 의 message 인자(4번째)를 캡처해 enriched 포맷인지 검증한다.
     * GiteaClient.createOrUpdateFile(repo, path, contentBase64, message, author, branch) 시그니처 기준.
     */
    private ArgumentCaptor<String> captureCommitMessageOnSuccess(String resultingSha) {
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(resultingSha, "msg", "100", Instant.now())));
        return ArgumentCaptor.forClass(String.class);
    }

    @Test
    @DisplayName("commit_메시지에_frame_번호와_added_removed_modified_카운트_포함")
    void commitMessageIncludesFrameAndDiffCounts() {
        // given: HEAD 에 id=1,2 두 라벨 → 새 commit 은 id=1 수정 + id=2 제거 + id=3,4 추가
        String headJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":2,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[100.0,100.0],[200.0,200.0]]}"
                + "]}";
        String newJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[20.0,20.0],[60.0,60.0]]},"
                + "{\"id\":3,\"lblTypeCd\":\"BBOX\",\"label\":\"bike\",\"points\":[[300.0,300.0],[400.0,400.0]]},"
                + "{\"id\":4,\"lblTypeCd\":\"BBOX\",\"label\":\"truck\",\"points\":[[500.0,500.0],[600.0,600.0]]}"
                + "]}";
        when(giteaClient.getContent(anyString(), eq("HEAD"), anyString()))
                .thenReturn(Mono.just(headJson));
        ArgumentCaptor<String> messageCap = captureCommitMessageOnSuccess(
                "abc1234567890abcdef1234567890abcdef12345");

        // when
        versionService.commit(srcSn, newJson, workerAssigned);

        // then
        verify(giteaClient).createOrUpdateFile(anyString(), anyString(), anyString(),
                messageCap.capture(), anyString(), anyString());
        String msg = messageCap.getValue();
        assertThat(msg).contains("프레임 0");
        assertThat(msg).contains("+2개 추가");
        assertThat(msg).contains("-1개 삭제");
        assertThat(msg).contains("~1개 수정");
        assertThat(msg).contains("작성자: 100");
    }

    @Test
    @DisplayName("최초_commit_은_added_카운트만_포함")
    void initialCommitMessageHasOnlyAddedCount() {
        // given: HEAD content 없음 (최초 commit) — getContent 가 빈 Mono 반환
        when(giteaClient.getContent(anyString(), eq("HEAD"), anyString()))
                .thenReturn(Mono.empty());
        String newJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":2,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[100.0,100.0],[200.0,200.0]]}"
                + "]}";
        ArgumentCaptor<String> messageCap = captureCommitMessageOnSuccess(
                "abc1234567890abcdef1234567890abcdef12345");

        versionService.commit(srcSn, newJson, workerAssigned);

        verify(giteaClient).createOrUpdateFile(anyString(), anyString(), anyString(),
                messageCap.capture(), anyString(), anyString());
        String msg = messageCap.getValue();
        assertThat(msg).contains("프레임 0");
        assertThat(msg).contains("+2개 추가");
        assertThat(msg).doesNotContain("삭제");
        assertThat(msg).doesNotContain("수정");
        assertThat(msg).contains("작성자: 100");
    }

    @Test
    @DisplayName("변화_없는_재_commit_은_no_changes_메시지")
    void reCommitWithNoChangesEmitsNoChangesMessage() {
        // given: HEAD content 와 새 content 가 동일 라벨 셋
        String sameJson = "{\"frameNo\":0,\"items\":["
                + "{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]}"
                + "]}";
        when(giteaClient.getContent(anyString(), eq("HEAD"), anyString()))
                .thenReturn(Mono.just(sameJson));
        ArgumentCaptor<String> messageCap = captureCommitMessageOnSuccess(
                "abc1234567890abcdef1234567890abcdef12345");

        versionService.commit(srcSn, sameJson, workerAssigned);

        verify(giteaClient).createOrUpdateFile(anyString(), anyString(), anyString(),
                messageCap.capture(), anyString(), anyString());
        String msg = messageCap.getValue();
        assertThat(msg).contains("프레임 0");
        assertThat(msg).contains("변경 없음");
        assertThat(msg).contains("재커밋");
        assertThat(msg).contains("작성자: 100");
    }

    @Test
    @DisplayName("Gitea_content_fetch_실패시_기본_메시지_fallback")
    void giteaContentFetchFailureFallsBackToDefaultMessage() {
        // given: HEAD content fetch 실패 (Mono.error)
        when(giteaClient.getContent(anyString(), eq("HEAD"), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea timeout")));
        ArgumentCaptor<String> messageCap = captureCommitMessageOnSuccess(
                "abc1234567890abcdef1234567890abcdef12345");

        // 빈 items 새 commit — 정상이라면 initial commit 메시지가 나와야 하지만,
        // fetch 실패의 경우에도 enrichment 가 정상 동작해야 함 (빈 content → initial commit).
        // 다만 raw "라벨 저장 (작성자: ACTOR)" fallback 은 parse/unknown 예외 시 작동하므로,
        // 이 케이스(getContent 만 실패)는 안전하게 빈 content 로 처리되어 정상 enrichment 가 적용됨을 확인.
        versionService.commit(srcSn, "{\"frameNo\":0,\"items\":[{\"id\":1,\"lblTypeCd\":\"BBOX\",\"label\":\"x\",\"points\":[[1.0,1.0]]}]}",
                workerAssigned);

        verify(giteaClient).createOrUpdateFile(anyString(), anyString(), anyString(),
                messageCap.capture(), anyString(), anyString());
        String msg = messageCap.getValue();
        // getContent 실패는 빈 content 로 흡수 → 최초 커밋 또는 +1개 추가 형태
        // 어떻든 작성자 포함 + 프레임 번호 포함은 보장되어야 함
        assertThat(msg).contains("작성자: 100");
        assertThat(msg).contains("프레임 0");
    }

    @Test
    @DisplayName("여러_라벨_타입_변경시_종합_카운트_정확성")
    void aggregateCountAccuracyAcrossMultipleLabelTypes() {
        // given: HEAD 에 BBOX/POLYGON 혼합 3개 → 새 commit 에서 1개 수정 / 1개 제거 / 신규 2개 추가
        String headJson = "{\"frameNo\":12,\"items\":["
                + "{\"id\":10,\"lblTypeCd\":\"BBOX\",\"label\":\"person\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":11,\"lblTypeCd\":\"POLYGON\",\"label\":\"road\",\"points\":[[0.0,0.0],[100.0,0.0],[100.0,100.0]]},"
                + "{\"id\":12,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[200.0,200.0],[300.0,300.0]]}"
                + "]}";
        // id=10 수정(label 변경), id=11 제거, id=12 유지, id=20/21 추가
        String newJson = "{\"frameNo\":12,\"items\":["
                + "{\"id\":10,\"lblTypeCd\":\"BBOX\",\"label\":\"pedestrian\",\"points\":[[10.0,10.0],[50.0,50.0]]},"
                + "{\"id\":12,\"lblTypeCd\":\"BBOX\",\"label\":\"car\",\"points\":[[200.0,200.0],[300.0,300.0]]},"
                + "{\"id\":20,\"lblTypeCd\":\"SEGMENT\",\"label\":\"sidewalk\",\"points\":[[400.0,400.0],[500.0,500.0]]},"
                + "{\"id\":21,\"lblTypeCd\":\"BBOX\",\"label\":\"truck\",\"points\":[[600.0,600.0],[700.0,700.0]]}"
                + "]}";
        when(giteaClient.getContent(anyString(), eq("HEAD"), anyString()))
                .thenReturn(Mono.just(headJson));
        ArgumentCaptor<String> messageCap = captureCommitMessageOnSuccess(
                "abc1234567890abcdef1234567890abcdef12345");

        // setup() 의 srcSn 은 frameNo=0 으로 생성됨 → 메시지의 frame 번호는 0
        // (newJson 의 frameNo 는 enrichment 카운트 계산에만 영향 없음, 메시지 frame label 은 LsDataSrc 기준)
        versionService.commit(srcSn, newJson, workerAssigned);

        verify(giteaClient).createOrUpdateFile(anyString(), anyString(), anyString(),
                messageCap.capture(), anyString(), anyString());
        String msg = messageCap.getValue();
        assertThat(msg).contains("프레임 0");
        assertThat(msg).contains("+2개 추가");
        assertThat(msg).contains("-1개 삭제");
        assertThat(msg).contains("~1개 수정");
        assertThat(msg).contains("작성자: 100");
    }
}
