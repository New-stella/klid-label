package kr.co.cudo.authoring.migration;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.status.LsBatchProcLog;
import kr.co.cudo.authoring.batch.status.LsBatchProcLogRepository;
import kr.co.cudo.authoring.review.entity.LsIssueComment;
import kr.co.cudo.authoring.review.repository.IssueCommentRepository;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V84 안전 타입 정합(비파괴 확대)의 실 PostgreSQL(Testcontainers) round-trip 검증.
 *
 * <p>마이그레이션 이전에는 아래 저장이 컬럼 제약(INTEGER 범위 / VARCHAR 길이)에 걸려 실패한다.
 * V84 확대 + 엔티티 필드/@Column 정합 이후 성공(GREEN)함을 고정한다.
 *
 * <ul>
 *   <li>FRM_NO / VDO_FRM_NO : INTEGER → BIGINT (Integer 범위 초과 Long 값 저장·조회)</li>
 *   <li>CMNT_CN            : VARCHAR(1000) → VARCHAR(4000) (4000자 저장)</li>
 *   <li>REQ/RESP_PAYLOAD_CN : TEXT (표준 정합, 대용량 저장)</li>
 *   <li>IDMP_KEY           : VARCHAR(64) → VARCHAR(128) (128자 저장)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class ColumnTypeAlignmentIT {

    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private IssueCommentRepository issueCommentRepository;
    @Autowired private LsBatchProcLogRepository batchProcLogRepository;
    @Autowired private LsWebhookIdempotencyRepository webhookIdempotencyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("FRM_NO_VDO_FRM_NO_BIGINT_범위_초과값_저장_조회")
    void FRM_NO_VDO_FRM_NO_BIGINT_범위_초과값_저장_조회() {
        // given — Integer.MAX_VALUE(2,147,483,647) 를 초과하는 Long 프레임 번호
        // 부모 영상 선시드 — V146 FK(LS_DATA_SRC → LS_DATA_RAW). 검증 대상은 FRM_NO/VDO_FRM_NO 의
        // BIGINT round-trip 이라 부모 존재 여부와 무관하다.
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        long bigFrameNo = 3_000_000_000L;      // > Integer.MAX_VALUE
        long bigVideoFrameNo = 5_000_000_000L; // > Integer.MAX_VALUE
        LsDataSrc src = LsDataSrc.create(rawSn, bigFrameNo, bigVideoFrameNo,
                "/frames/raw/" + rawSn + "/big.jpg", LocalDateTime.now());

        // when — 실 PostgreSQL BIGINT 컬럼에 저장 후 재조회
        LsDataSrc saved = srcRepository.saveAndFlush(src);
        LsDataSrc found = srcRepository.findById(saved.getSrcSn()).orElseThrow();

        // then — Integer 범위 초과값이 손실 없이 round-trip
        assertThat(found.getFrameNo()).isEqualTo(bigFrameNo);
        assertThat(found.getVideoFrameNo()).isEqualTo(bigVideoFrameNo);
    }

    @Test
    @DisplayName("CMNT_CN_4000자_저장_성공")
    void CMNT_CN_4000자_저장_성공() {
        // given — 4000자 댓글 (구 VARCHAR(1000) 한도 초과)
        String content = "가".repeat(4000);
        LsIssueComment comment = LsIssueComment.create(System.nanoTime(), "worker-1", "WORKER", content);

        // when
        LsIssueComment saved = issueCommentRepository.saveAndFlush(comment);
        LsIssueComment found = issueCommentRepository.findById(saved.getIssueCommentSn()).orElseThrow();

        // then — 4000자 truncation 없이 저장·조회
        assertThat(found.getCmntCn()).hasSize(4000);
    }

    @Test
    @DisplayName("REQ_RESP_PAYLOAD_CN_TEXT_대용량_저장_성공")
    void REQ_RESP_PAYLOAD_CN_TEXT_대용량_저장_성공() {
        // given — VARCHAR(4000) 를 초과하는 대용량 페이로드 (TEXT 컬럼 검증)
        String largePayload = "{\"data\":\"" + "z".repeat(10_000) + "\"}";
        // 부모 영상 선시드 — V146 FK(LS_BATCH_PROC_LOG → LS_DATA_RAW).
        LsBatchProcLog log = LsBatchProcLog.create(RawVideoFixture.newRaw(jdbcTemplate), BatchStage.VLM);
        log.setResPayloadCn(largePayload);

        // when
        LsBatchProcLog saved = batchProcLogRepository.saveAndFlush(log);
        LsBatchProcLog found = batchProcLogRepository.findById(saved.getBatchProcLogSn()).orElseThrow();

        // then — 10KB 페이로드가 truncation 없이 round-trip
        assertThat(found.getResPayloadCn()).isEqualTo(largePayload);
    }

    @Test
    @DisplayName("IDMP_KEY_128자_저장_성공")
    void IDMP_KEY_128자_저장_성공() {
        // given — 128자 멱등키 (구 VARCHAR(64) 한도 초과)
        String key128 = "k".repeat(128);
        LsWebhookIdempotency entity = LsWebhookIdempotency.issue(key128, "VLM", "job-" + System.nanoTime());

        // when
        webhookIdempotencyRepository.saveAndFlush(entity);
        LsWebhookIdempotency found = webhookIdempotencyRepository.findById(key128).orElseThrow();

        // then — 128자 키가 truncation 없이 PK 로 저장·조회
        assertThat(found.getIdmpKey()).hasSize(128);
    }
}
