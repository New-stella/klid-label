package kr.co.cudo.authoring.batch.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검수완료 재비식별(Approved Re-deidentification) 경로 구분용 REQ_KIND_CD 단위 테스트.
 * <p>기본 null = 기존 배치 비식별 경로(무영향), {@code REDEIDENT} = 검수완료 재비식별 경로.
 * 기존 배치 경로(request/succeed/fail)는 reqKindCd 가 null 로 남아 그대로 동작해야 한다(회귀 금지).
 */
class LsDeidentProcLogReqKindTest {

    private LsDeidentProcLog newRequested() {
        return LsDeidentProcLog.request(10L, "req-1", "/raw/a.mp4", "system");
    }

    @Test
    @DisplayName("REQ_KIND_CD_없으면_isRedeident_false")
    void defaultReqKindIsNotRedeident() {
        LsDeidentProcLog log = newRequested();

        assertThat(log.getReqKindCd()).isNull();
        assertThat(log.isRedeident()).isFalse();
    }

    @Test
    @DisplayName("markRedeident_호출시_isRedeident_true")
    void markRedeidentSetsRedeidentTrue() {
        LsDeidentProcLog log = newRequested();

        log.markRedeident();

        assertThat(log.getReqKindCd()).isEqualTo(LsDeidentProcLog.REQ_KIND_REDEIDENT);
        assertThat(log.isRedeident()).isTrue();
    }

    @Test
    @DisplayName("기존_배치경로_succeed는_reqKindCd가_null로_무영향이다")
    void legacyBatchPathKeepsReqKindNull() {
        LsDeidentProcLog log = newRequested();
        assertThat(log.getReqKindCd()).isNull();

        log.succeed("/deid/legacy.mp4");

        assertThat(log.getProcSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(log.getReqKindCd()).isNull();
        assertThat(log.isRedeident()).isFalse();
    }
}
