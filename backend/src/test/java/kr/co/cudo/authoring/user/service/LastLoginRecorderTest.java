package kr.co.cudo.authoring.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link LastLoginRecorder} 단위 테스트 — throttle 창 계산과 fail-open 계약.
 *
 * <p>throttle 의 <b>실제 판정</b>은 조건부 UPDATE 의 WHERE 절이 수행하므로 그 의미는
 * {@code LastLoginRecordIT} 가 실제 SQL 로 검증한다. 여기서는 그 판정에 필요한
 * <b>창(threshold)이 제대로 계산돼 넘어가는지</b>와 예외 삼킴(fail-open)을 고정한다.
 *
 * @design SCREEN-024
 */
@ExtendWith(MockitoExtension.class)
class LastLoginRecorderTest {

    @Mock
    private LastLoginTouchTxService touchTxService;

    @Test
    @DisplayName("기록_요청은_지금시각과_throttle_창을_함께_넘긴다")
    void 기록_요청은_지금시각과_throttle_창을_함께_넘긴다() {
        // given
        LastLoginRecorder recorder = new LastLoginRecorder(touchTxService, 5);
        when(touchTxService.touch(anyLong(), any(), any())).thenReturn(1);
        LocalDateTime before = LocalDateTime.now();

        // when
        recorder.record(7L);

        // then — now 은 호출 시각 근방이고 threshold 는 정확히 그보다 throttle 분 이전이다.
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(touchTxService).touch(eq(7L), now.capture(), threshold.capture());

        assertThat(now.getValue()).isAfterOrEqualTo(before);
        assertThat(Duration.between(threshold.getValue(), now.getValue()).toMinutes())
                .as("threshold 는 now - throttle(5분) 이어야 한다 — now 를 그대로 넘기면 매 요청 UPDATE 가 된다")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("기록에_실패해도_요청은_정상_처리된다")
    void 기록에_실패해도_요청은_정상_처리된다() {
        // given — 부가 기록이므로 DB 장애가 인증 경로를 죽이면 안 된다(fail-open).
        LastLoginRecorder recorder = new LastLoginRecorder(touchTxService, 5);
        when(touchTxService.touch(anyLong(), any(), any()))
                .thenThrow(new QueryTimeoutException("db down"));

        // when & then
        assertThatCode(() -> recorder.record(7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("사용자번호가_없으면_DB를_건드리지_않는다")
    void 사용자번호가_없으면_DB를_건드리지_않는다() {
        // given — 비숫자/누락 sub 는 필터가 null 로 선처리한다.
        LastLoginRecorder recorder = new LastLoginRecorder(touchTxService, 5);

        // when
        recorder.record(null);

        // then
        verifyNoInteractions(touchTxService);
    }

    @Test
    @DisplayName("예외를_삼키는_쪽은_트랜잭션_경계가_아니다")
    void 예외를_삼키는_쪽은_트랜잭션_경계가_아니다() throws Exception {
        // given: fail-open 의 try/catch 가 트랜잭션 <안쪽>에 있으면 그 트랜잭션은 이미 rollback-only 로
        //   표시돼 커밋 시점에 다시 터진다(이 저장소의 반복 결함 패턴). 즉 "예외를 잡았다"만으로는
        //   fail-open 이 성립하지 않고 <잡는 위치>가 계약이다.
        //   런타임 테스트로는 이 배치를 관측할 수 없으므로(경계 빈을 mock 으로 바꾸면 트랜잭션 자체가
        //   사라진다) 구조로 고정한다.

        // then: 삼키는 쪽(recorder)에는 트랜잭션이 없다 — 클래스도 메서드도.
        assertThat(LastLoginRecorder.class.getAnnotation(Transactional.class))
                .as("recorder 에 @Transactional 을 붙이면 fail-open 이 rollback-only 로 무너진다")
                .isNull();
        assertThat(LastLoginRecorder.class.getMethod("record", Long.class)
                .getAnnotation(Transactional.class))
                .as("record 에 @Transactional 을 붙이면 fail-open 이 rollback-only 로 무너진다")
                .isNull();

        // and: 경계는 <별도 빈>이 소유한다 — 같은 빈 안이면 자기호출로 프록시를 우회해
        //      트랜잭션이 열리지 않는다(테스트는 통과하는데 런타임만 깨지는 패턴).
        assertThat(LastLoginTouchTxService.class
                .getMethod("touch", long.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Transactional.class))
                .as("쓰기 문장은 트랜잭션 안에서 실행돼야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("throttle_설정이_0이하면_안전_기본값으로_폴백한다")
    void throttle_설정이_0이하면_안전_기본값으로_폴백한다() {
        // given — 0/음수는 "매 요청 UPDATE"(throttle 무력화)와 같다.
        LastLoginRecorder recorder = new LastLoginRecorder(touchTxService, 0);
        when(touchTxService.touch(anyLong(), any(), any())).thenReturn(1);

        // when
        recorder.record(7L);

        // then
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> threshold = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(touchTxService, times(1)).touch(eq(7L), now.capture(), threshold.capture());
        assertThat(Duration.between(threshold.getValue(), now.getValue()).toMinutes())
                .as("0 이하 설정은 기본값(%d분)으로 폴백해야 한다", LastLoginRecorder.DEFAULT_THROTTLE_MINUTES)
                .isEqualTo((long) LastLoginRecorder.DEFAULT_THROTTLE_MINUTES);
    }
}
