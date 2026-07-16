package kr.co.cudo.authoring.label;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
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
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Phase 4 — 배타 락 가시성 통합 테스트 (실제 DB, non-transactional).
 *
 * <p>DEV_FIX 실증: {@link WorkLockService#lockRawExclusiveInNewTx} 가 {@code REQUIRES_NEW} 로 LOCKED 를
 * 즉시 커밋하여, 병합 진행 중에도 다른 조회/편집이 LOCKED 를 관측하는지 검증한다.
 * <ul>
 *   <li>락 획득(REQUIRES_NEW 커밋) 후 {@code isRawLocked=true} 가 별도 조회에서 관측된다.</li>
 *   <li>락 유지 중 {@code LabelService.bulkUpsert} 가 409(CONFLICT) 로 차단된다(병합 중 편집 침묵 덮어쓰기 방지).</li>
 *   <li>{@code releaseRawInNewTx} 로 해제하면 {@code isRawLocked=false} 로 복귀한다.</li>
 * </ul>
 *
 * <p>테스트는 {@code @Transactional} 을 붙이지 않는다 — REQUIRES_NEW 커밋을 실제로 관측해야 하며,
 * 생성한 데이터는 {@code @AfterEach} 에서 명시 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class TrackMergeLockVisibilityIntegrationTest {

    @Autowired private WorkLockService workLockService;
    @Autowired private LabelService labelService;
    @Autowired private VideoRepository rawRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsTaskAssignmentRepository authrtRepository;

    private Long rawSn;
    private Long srcSn;
    private Long assignmentId;

    @BeforeEach
    void setup() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-LOCK-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        rawSn = rawRepository.save(raw).getRawSn();
        srcSn = srcRepository.save(LsDataSrc.create(rawSn, 0, "0.jpg", LocalDateTime.now())).getSrcSn();
        assignmentId = authrtRepository.save(LsTaskAssignment.createLabeler(rawSn, 100L, 1L)).getAssignmentId();
    }

    @AfterEach
    void cleanup() {
        // REQUIRES_NEW 로 커밋된 락 정리(잔여 LOCKED 방지) + 테스트 데이터 삭제.
        workLockService.releaseRawInNewTx(rawSn, "test", "CLEANUP");
        authrtRepository.deleteById(assignmentId);
        srcRepository.deleteById(srcSn);
        rawRepository.deleteById(rawSn);
    }

    private TokenClaims worker() {
        return new TokenClaims("100", Role.WORKER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    @Test
    @DisplayName("락_획득_REQUIRES_NEW_커밋_후_isRawLocked_true_관측")
    void 락_가시성() {
        assertThat(workLockService.isRawLocked(rawSn)).isFalse();

        workLockService.lockRawExclusiveInNewTx(rawSn, "1");
        // 별도 조회 트랜잭션에서 LOCKED 관측 — REQUIRES_NEW 커밋으로 가시화됨(과거엔 커밋 안 돼 false 였음).
        assertThat(workLockService.isRawLocked(rawSn)).isTrue();

        workLockService.releaseRawInNewTx(rawSn, "1", LsAuthWorkLock.REASON_MERGE);
        assertThat(workLockService.isRawLocked(rawSn)).isFalse();
    }

    @Test
    @DisplayName("병합중_락유지_bulkUpsert_409_차단")
    void 병합중_편집_차단() {
        workLockService.lockRawExclusiveInNewTx(rawSn, "1");

        LabelBulkUpsertRequest req = new LabelBulkUpsertRequest(List.of(
                new LabelItemDto(null, "BBOX", null, "person",
                        List.of(List.of(10.0, 10.0), List.of(40.0, 60.0)), null)));

        CustomException ex = catchThrowableOfType(
                () -> labelService.bulkUpsert(srcSn, req, worker()), CustomException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
    }
}
