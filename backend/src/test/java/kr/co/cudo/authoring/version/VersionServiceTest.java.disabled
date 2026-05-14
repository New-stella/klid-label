package kr.co.cudo.authoring.version;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
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
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.service.VersionService;
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

@SpringBootTest
@ActiveProfiles("local")
@Sql(scripts = {"/db/test-data.sql", "/db/test-data-video.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VersionServiceTest {

    @Autowired private VersionService versionService;
    @Autowired private LabelService labelService;
    @Autowired private LsDataLblHstryRepository historyRepository;
    @Autowired private GiteaCommitFallbackQueue fallbackQueue;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsPjtUserAuthrtRepository authrtRepository;

    @MockBean private GiteaClient giteaClient;

    private Long srcSn;
    private Long rawSn;

    private TokenClaims reviewer;
    private TokenClaims workerAssigned;
    private TokenClaims portalUser;

    @BeforeEach
    void setup() {
        fallbackQueue.clear();
        historyRepository.deleteAll();

        // 영상/프레임 시드
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-VER-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        raw = rawRepository.save(raw);
        rawSn = raw.getRawSn();

        srcSn = srcRepository.save(LsDataSrc.create(rawSn, 0, "/raw/0.jpg", LocalDateTime.now()))
                .getSrcSn();

        // 작업자 100 만 배정
        authrtRepository.save(LsPjtUserAuthrt.createLabeler(10L, rawSn, 100L, 1L));

        Instant exp = Instant.now().plusSeconds(60);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, exp);
        workerAssigned = new TokenClaims("100", Role.WORKER, Channel.INTERNAL, exp);
        portalUser = new TokenClaims("100", Role.PORTAL_USER, Channel.PORTAL, exp);
    }

    // ---------- commit 성공/실패 ----------

    @Test
    @DisplayName("라벨_저장_성공시_Gitea_commit_생성_+_HSTRY_레코드_INSERT_+_HASH_저장")
    void commitSuccessWritesHistoryWithHash() {
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "abc1234567890abcdef1234567890abcdef12345", "msg", "100", Instant.now())));

        String sha = versionService.commit(srcSn, "{\"items\":[]}", workerAssigned);

        assertThat(sha).isEqualTo("abc1234567890abcdef1234567890abcdef12345");
        List<LsDataLblHstry> history = historyRepository.findBySrcSnOrderByRegisteredAtDesc(srcSn);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getGiteaCmtHash()).isEqualTo("abc1234567890abcdef1234567890abcdef12345");
        assertThat(history.get(0).getRegisteredUserNo()).isEqualTo("100");
        assertThat(fallbackQueue.size()).isZero();
    }

    @Test
    @DisplayName("Gitea_장애시_라벨_저장은_성공_+_재시도_큐_등록")
    void giteaFailureEnqueuesRetry() {
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        String sha = versionService.commit(srcSn, "{\"items\":[]}", workerAssigned);

        assertThat(sha).isNull();
        assertThat(fallbackQueue.size()).isEqualTo(1);
        // PENDING history 도 1건 (hash 없음)
        List<LsDataLblHstry> history = historyRepository.findBySrcSnOrderByRegisteredAtDesc(srcSn);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getGiteaCmtHash()).isNull();
    }

    // ---------- 채널 분기: PORTAL → commit skip ----------

    @Test
    @DisplayName("포털_모드_channel_PORTAL_는_커밋_호출_안함")
    void portalChannelSkipsCommit() {
        // PORTAL 채널 사용자가 라벨 저장 (LabelService.bulkUpsert 통해)
        // 사전: 포털 사용자가 같은 srcSn 에 접근 가능하도록 LabelAccessGuard 통과 필요.
        // 본 테스트는 VersionService.isCommittable 만 검증.
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
        List<LsDataLblHstry> history = historyRepository.findBySrcSnOrderByRegisteredAtDesc(srcSn);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getGiteaCmtHash()).isEqualTo("deadbeefcafebabe1234567890abcdef12345678");
    }

    // ---------- rollback 권한 ----------

    @Test
    @DisplayName("WORKER가_rollback_호출시_403")
    void workerRollbackForbidden() {
        // 사전 history 시드
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "feedface1234567890abcdef1234567890abcdef", "1", "{\"items\":[]}"));

        assertThatThrownBy(() -> versionService.rollback(
                "feedface1234567890abcdef1234567890abcdef", srcSn, workerAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        // Gitea 호출이 일어나지 않아야 함
        verify(giteaClient, never()).createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("REVIEWER_rollback_정상_동작_새_history_생성")
    void reviewerRollbackCreatesNewHistory() {
        // 사전: 과거 커밋 시드 (snapshot 보유)
        String pastSha = "feedface1234567890abcdef1234567890abcdef";
        historyRepository.save(LsDataLblHstry.create(srcSn, pastSha, "1",
                "{\"items\":[{\"label\":\"car\"}]}"));

        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "11112222333344445555666677778888aaaabbbb", "rollback", "1", Instant.now())));

        LsDataLblHstry rollback = versionService.rollback(pastSha, srcSn, reviewer);

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
        // history 0건 — Gitea 호출 자체가 일어나면 안 되고 500 도 발생하지 않아야 함
        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);

        assertThat(result).isEmpty();
        // Gitea 호출 skip 확인
        verify(giteaClient, never())
                .listCommits(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("listVersions_gitea_404_빈_리스트_또는_DB_fallback")
    void listVersionsGiteaFailureFallsBackToDb() {
        // 사전: history 1건 — Gitea path 가 아직 없는 신규 영상 시뮬레이션
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "abc1234abc1234abc1234abc1234abc1234abc12", "100", "{\"items\":[]}"));

        when(giteaClient.listCommits(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(reactor.core.publisher.Mono.error(
                        new RuntimeException("gitea 404")));

        List<VersionItem> result = versionService.listVersions(srcSn, workerAssigned);

        // Gitea 가 실패해도 500 이 아니라 DB 기반 fallback 으로 1건 반환
        assertThat(result).hasSize(1);
        assertThat(result.get(0).commitSha()).isEqualTo("abc1234abc1234abc1234abc1234abc1234abc12");
        assertThat(result.get(0).shortHash()).isEqualTo("abc1234");
        assertThat(result.get(0).isCurrent()).isTrue();
    }

    @Test
    @DisplayName("listVersions_gitea_정상_응답_시_커밋_메타_사용")
    void listVersionsUsesGiteaMetadata() {
        historyRepository.save(LsDataLblHstry.create(srcSn,
                "abc1234abc1234abc1234abc1234abc1234abc12", "100", "{}"));

        Instant now = Instant.now();
        when(giteaClient.listCommits(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(reactor.core.publisher.Mono.just(List.of(
                        new kr.co.cudo.authoring.common.client.dto.CommitResponse(
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

    // ---------- Phase 3 — 비식별 재처리 잠금 가드 ----------

    @Test
    @DisplayName("Phase3_잠금_영상_commit_시도시_409_CONFLICT_+_Gitea_호출안함")
    void lockedVideoCommitConflict() {
        // raw 를 LOCKED_FOR_REDEIDENT 로 잠금
        LsDataRaw raw = rawRepository.findById(rawSn).orElseThrow();
        raw.attachLockStts(LsDataRaw.LOCK_REDEIDENT);
        rawRepository.save(raw);

        assertThatThrownBy(() -> versionService.commit(srcSn, "{\"items\":[]}", workerAssigned))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(giteaClient, never())
                .createOrUpdateFile(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(historyRepository.findBySrcSnOrderByRegisteredAtDesc(srcSn)).isEmpty();
    }
}
