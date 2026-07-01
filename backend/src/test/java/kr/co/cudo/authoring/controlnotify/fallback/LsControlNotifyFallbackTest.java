package kr.co.cudo.authoring.controlnotify.fallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 (통지 인프라) -- LsControlNotifyFallback 엔티티 비즈니스 로직 단위 테스트.
 */
class LsControlNotifyFallbackTest {

    // ---------- pending 정적 팩토리 ----------

    @Test
    @DisplayName("pending_정적팩토리_정상_생성_시_STATUS_PENDING_retryCount_0")
    void pendingCreates() {
        // given
        String idempotencyKey = "notify-key-1";
        String eventType = "TASK_COMPLETED";
        Long rawSn = 100L;
        String payload = "{\"eventType\":\"TASK_COMPLETED\"}";

        // when
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                idempotencyKey, eventType, rawSn, payload);

        // then
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_PENDING);
        assertThat(q.getEventTypeCd()).isEqualTo("TASK_COMPLETED");
        assertThat(q.getRawSn()).isEqualTo(100L);
        assertThat(q.getRtryCnt()).isZero();
        assertThat(q.getMaxRtryCnt()).isEqualTo(LsControlNotifyFallback.DEFAULT_MAX_RETRY);
        assertThat(q.getNextRtryDt()).isNotNull();
        assertThat(q.getIdmpKey()).isEqualTo(idempotencyKey);
        assertThat(q.getPayloadCn()).isEqualTo(payload);
    }

    @Test
    @DisplayName("pending_idempotencyKey_blank_시_IllegalArgument")
    void pendingRejectsBlankKey() {
        // given / when / then
        assertThatThrownBy(() -> LsControlNotifyFallback.pending(
                "  ", "TASK_COMPLETED", 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pending_rawSn_null_시_IllegalArgument")
    void pendingRejectsNullRawSn() {
        // given / when / then
        assertThatThrownBy(() -> LsControlNotifyFallback.pending(
                "key", "TASK_COMPLETED", null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pending_eventType_blank_시_IllegalArgument")
    void pendingRejectsBlankEventType() {
        // given / when / then
        assertThatThrownBy(() -> LsControlNotifyFallback.pending(
                "key", "", 1L, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- SEND_RSLT_CD (발송 결과 관찰) ----------

    @Test
    @DisplayName("pending_정적팩토리는_SEND_RSLT_FAILED로_생성된다")
    void pendingSetsSendResultFailed() {
        // given / when
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // then — 즉시 발송 실패 → 폴백 큐 진입이므로 발송 결과는 FAILED.
        assertThat(q.getSendRsltCd()).isEqualTo(LsControlNotifyFallback.SEND_RSLT_FAILED);
    }

    @Test
    @DisplayName("succeeded_정적팩토리는_STTS_SUCCEEDED_SEND_RSLT_SUCCESS로_생성된다")
    void succeededFactoryCreatesTerminalSuccess() {
        // given / when — 즉시 발송 성공 관찰 행.
        LsControlNotifyFallback q = LsControlNotifyFallback.succeeded(
                "notify-ok-1", "TASK_COMPLETED", 100L, "{\"eventType\":\"TASK_COMPLETED\"}");

        // then
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_SUCCEEDED);
        assertThat(q.getSendRsltCd()).isEqualTo(LsControlNotifyFallback.SEND_RSLT_SUCCESS);
        assertThat(q.getNextRtryDt()).isNull();
        assertThat(q.getIdmpKey()).isEqualTo("notify-ok-1");
        assertThat(q.getRawSn()).isEqualTo(100L);
    }

    @Test
    @DisplayName("즉시성공_행은_재시도_잡_조회대상_및_depth_게이지에서_제외된다")
    void succeededRowExcludedFromRetryAndDepth() {
        // given / when
        LsControlNotifyFallback q = LsControlNotifyFallback.succeeded(
                "notify-ok-2", "TASK_MODIFIED", 200L, "{}");

        // then — 재시도 잡 조회(STTS_CD=PENDING) / depth 게이지(PENDING+RETRYING) 어디에도 안 잡힘.
        assertThat(q.getSttsCd())
                .isNotEqualTo(LsControlNotifyFallback.STATUS_PENDING)
                .isNotEqualTo(LsControlNotifyFallback.STATUS_RETRYING);
    }

    @Test
    @DisplayName("markSucceeded_재시도성공시_SEND_RSLT_SUCCESS로_갱신")
    void markSucceededSetsSendResultSuccess() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.markSucceeded();

        // then
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_SUCCEEDED);
        assertThat(q.getSendRsltCd()).isEqualTo(LsControlNotifyFallback.SEND_RSLT_SUCCESS);
    }

    @Test
    @DisplayName("failAndSchedule_재시도최종실패시_DEAD_LETTER_SEND_RSLT_FAILED")
    void failAndScheduleDeadLetterSetsSendResultFailed() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when -- 6회 실패 (max 5 초과)
        for (int i = 0; i < 6; i++) {
            q.failAndSchedule("err-" + i);
        }

        // then
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_DEAD_LETTER);
        assertThat(q.getSendRsltCd()).isEqualTo(LsControlNotifyFallback.SEND_RSLT_FAILED);
    }

    // ---------- 상태 전이 ----------

    @Test
    @DisplayName("markRetrying_상태전이_PENDING_to_RETRYING")
    void markRetryingTransitions() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.markRetrying();

        // then
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_RETRYING);
    }

    @Test
    @DisplayName("markSucceeded_상태전이_SUCCEEDED_nextRetryAt_null")
    void markSucceededClears() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.markSucceeded();

        // then
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_SUCCEEDED);
        assertThat(q.getNextRtryDt()).isNull();
    }

    // ---------- failAndSchedule ----------

    @Test
    @DisplayName("failAndSchedule_재시도_가능_시_PENDING_retryCount_증가_nextRetryAt_미래")
    void failAndScheduleReschedules() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        boolean dead = q.failAndSchedule("HttpServerErrorException");

        // then
        assertThat(dead).isFalse();
        assertThat(q.getRtryCnt()).isEqualTo(1);
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_PENDING);
        assertThat(q.getNextRtryDt()).isAfter(LocalDateTime.now().minusSeconds(2));
        assertThat(q.getLastErrMsg()).contains("HttpServerErrorException");
    }

    @Test
    @DisplayName("failAndSchedule_백오프_2의n승분_cap_60분_검증")
    void failAndScheduleBackoff() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when -- 1회 실패 -> 백오프 2^1 = 2분
        q.failAndSchedule("err1");
        LocalDateTime first = q.getNextRtryDt();

        // when -- 2회 실패 -> 백오프 2^2 = 4분
        q.failAndSchedule("err2");
        LocalDateTime second = q.getNextRtryDt();

        // then -- 두 번째 nextRetryAt 이 첫 번째보다 더 뒤여야 한다
        assertThat(second).isAfter(first);
    }

    @Test
    @DisplayName("failAndSchedule_maxRetry_초과시_DEAD_LETTER")
    void failAndScheduleExhaustsToDeadLetter() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when -- 6회 실패 (max 5 초과)
        boolean dead = false;
        for (int i = 0; i < 6; i++) {
            dead = q.failAndSchedule("err-" + i);
        }

        // then
        assertThat(dead).isTrue();
        assertThat(q.getSttsCd()).isEqualTo(LsControlNotifyFallback.STATUS_DEAD_LETTER);
        assertThat(q.getDlqDt()).isNotNull();
        assertThat(q.getNextRtryDt()).isNull();
    }

    // ---------- sanitizeError (CWE-117 + CWE-209) ----------

    @Test
    @DisplayName("sanitizeError_제어문자_치환")
    void sanitizeErrorControlChars() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.failAndSchedule("line1\r\nline2\ttab");

        // then -- 제어문자(\r, \n, \t)가 공백으로 치환됨
        String sanitized = q.getLastErrMsg();
        assertThat(sanitized).doesNotContain("\r");
        assertThat(sanitized).doesNotContain("\n");
        assertThat(sanitized).doesNotContain("\t");
        assertThat(sanitized).contains("line1");
        assertThat(sanitized).contains("line2");
    }

    @Test
    @DisplayName("sanitizeError_Bearer_토큰_마스킹")
    void sanitizeErrorBearerTokenMasked() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.failAndSchedule("401 Unauthorized: Authorization: Bearer abc123secret, token=xyz789");

        // then
        assertThat(q.getLastErrMsg())
                .doesNotContain("abc123secret")
                .doesNotContain("xyz789")
                .contains("***");
    }

    @Test
    @DisplayName("sanitizeError_URL_마스킹")
    void sanitizeErrorUrlRedacted() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.failAndSchedule("Connection failed to https://control.internal:8090/api/v1/notify");

        // then
        assertThat(q.getLastErrMsg())
                .doesNotContain("control.internal")
                .doesNotContain("https://")
                .contains("URL_REDACTED");
    }

    @Test
    @DisplayName("sanitizeError_1900자_초과_시_truncate")
    void sanitizeErrorTruncated() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.failAndSchedule("x".repeat(3000));

        // then
        assertThat(q.getLastErrMsg()).hasSize(1900);
    }

    @Test
    @DisplayName("sanitizeError_null_허용")
    void sanitizeErrorNullPassThrough() {
        // given
        LsControlNotifyFallback q = LsControlNotifyFallback.pending(
                "k", "TASK_COMPLETED", 1L, "{}");

        // when
        q.failAndSchedule(null);

        // then
        assertThat(q.getLastErrMsg()).isNull();
    }
}
