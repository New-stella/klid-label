package kr.co.cudo.authoring.dataset.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import kr.co.cudo.authoring.observability.metrics.MetaReplicationMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * Phase 3 복제 워커 <b>순수 단위 테스트</b>(Mockito) — 실 DB 없이 tick 제어흐름을 검증한다.
 *
 * <ul>
 *   <li>graceful-skip false 경로: 복제본 미프로비저닝(isReplicaAvailable=false) 시 outbox 미터치·0건.</li>
 *   <li>markFailure 자체 실패 시 tick 조기 중단 방지: 나머지 outbox 를 계속 처리한다(SLA 보호).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MetaReplicationWorkerTest {

    @Mock
    private LsMetaReplOutboxRepository outboxRepository;
    @Mock
    private PortalMetaReplicaWriter replicaWriter;
    @Mock
    private MetaReplicationOutboxService outboxService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetaReplicationMetrics metrics = new MetaReplicationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    private MetaReplicationWorker worker() {
        return new MetaReplicationWorker(outboxRepository, replicaWriter, outboxService, metrics, objectMapper, 100);
    }

    /** create() 는 IDENTITY PK 를 채우지 않으므로 테스트용 outboxSn 을 주입해 markFailure/markDone 인자를 구분한다. */
    private LsMetaReplOutbox outbox(long outboxSn, long rawSn) {
        LsMetaReplOutbox o = LsMetaReplOutbox.create(rawSn, "hash-" + rawSn, "{\"rawSn\":" + rawSn + "}");
        ReflectionTestUtils.setField(o, "outboxSn", outboxSn);
        return o;
    }

    @Test
    @DisplayName("복제본_미프로비저닝시_outbox_미터치_0건반환")
    void gracefulSkip_whenReplicaUnavailable_touchesNothing() {
        // given — 포털 복제본 테이블 미프로비저닝
        given(replicaWriter.isReplicaAvailable()).willReturn(false);

        // when
        int done = worker().replicatePending();

        // then — 0건 반환 + outbox 폴링/전이/복제 어느 것도 하지 않음(중단 아님)
        assertThat(done).isZero();
        then(outboxRepository).shouldHaveNoInteractions();
        then(outboxService).shouldHaveNoInteractions();
        then(replicaWriter).should().isReplicaAvailable();
        then(replicaWriter).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("markFailure_실패해도_다음_outbox_계속처리")
    void markFailureError_doesNotAbortTick() {
        // given — PENDING 2건, 둘 다 복제 실패. 첫 outbox 의 markFailure 는 control DB 오류로 실패.
        given(replicaWriter.isReplicaAvailable()).willReturn(true);
        LsMetaReplOutbox o1 = outbox(1L, 1001L);
        LsMetaReplOutbox o2 = outbox(2L, 1002L);
        given(outboxRepository.findBySttsCdOrderByRegDtAsc(any(), any())).willReturn(List.of(o1, o2));
        willThrow(new RuntimeException("portal down")).given(replicaWriter).replicate(any(LsDatasetVideoMeta.class));
        willThrow(new RuntimeException("control down")).given(outboxService).markFailure(o1.getOutboxSn());

        // when — 첫 markFailure 가 던져도 예외가 tick 밖으로 전파되지 않고 둘째까지 처리
        int done = worker().replicatePending();

        // then — 완료 0건, 두 outbox 모두 markFailure 시도됨(루프 계속)
        assertThat(done).isZero();
        then(outboxService).should().markFailure(o1.getOutboxSn());
        then(outboxService).should().markFailure(o2.getOutboxSn());
    }

    @Test
    @DisplayName("정상_복제시_DONE_전이_카운트")
    void happyPath_marksDone() {
        // given — PENDING 1건, 복제 성공
        given(replicaWriter.isReplicaAvailable()).willReturn(true);
        LsMetaReplOutbox o1 = outbox(3L, 2001L);
        given(outboxRepository.findBySttsCdOrderByRegDtAsc(any(), any())).willReturn(List.of(o1));

        // when
        int done = worker().replicatePending();

        // then
        assertThat(done).isEqualTo(1);
        then(outboxService).should().markDone(o1.getOutboxSn());
        then(outboxService).should(org.mockito.Mockito.never()).markFailure(anyLong());
    }
}
