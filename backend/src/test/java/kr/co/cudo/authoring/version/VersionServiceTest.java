package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
                new LabelItemDto(null, "BBOX", "person",
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
        labelVersionRepository.save(LsLabelVersion.create(0L, rawSn, srcSn, pastSha, 1, "SAVE", "1"));

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
        labelVersionRepository.save(LsLabelVersion.create(0L, rawSn, srcSn,
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
        labelVersionRepository.save(LsLabelVersion.create(0L, rawSn, srcSn, pastSha, 1, "SAVE", "1"));

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
        labelVersionRepository.save(LsLabelVersion.create(0L, rawSn, srcSn,
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
        labelVersionRepository.save(LsLabelVersion.create(0L, rawSn, srcSn,
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
}
