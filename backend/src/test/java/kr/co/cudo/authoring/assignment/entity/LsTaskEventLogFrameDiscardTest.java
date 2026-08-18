package kr.co.cudo.authoring.assignment.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프레임 폐기·복원 감사 이벤트(P1) 단위 테스트.
 *
 * <p>폐기는 학습데이터 산출물에서 그 프레임을 빼는 <b>사람의 판정</b>이므로 누가·언제·어느 프레임을
 * 어느 방향으로 바꿨는지 행 단위로 남는다(OWASP A09). 영상(rawSn) 스코프 + actor 를 이미 갖춘
 * {@code LS_TASK_EVNT_LOG} 가 유일하게 맞는 축이다 — 라벨 이력 {@code LS_DATA_LBL_HSTRY} 는
 * {@code SRC_SN NOT NULL} 인 프레임 스코프지만 "행위자·사유" 축이 없다.
 *
 * <h3>담지 않는 것 (Critical)</h3>
 * <ul>
 *   <li><b>사유를 받지 않는다</b>(설계 확정 A13) — 팩토리에 사유 파라미터가 없다.</li>
 *   <li><b>프레임 이미지 경로·PII 를 남기지 않는다</b>(CWE-359) — 개인정보 선언 감사와 같은 규칙.</li>
 * </ul>
 *
 * @design D1
 * @req R4
 * @req R5
 */
class LsTaskEventLogFrameDiscardTest {

    private static final Long RAW_SN = 4200L;
    private static final Long SRC_SN = 91234L;
    private static final Long ACTOR = 100L;

    @Test
    @DisplayName("폐기_감사_이벤트는_행위자와_프레임과_방향을_담는다")
    void 폐기_감사_이벤트는_행위자와_프레임과_방향을_담는다() {
        // when
        LsTaskEventLog discarded = LsTaskEventLog.frameDiscarded(RAW_SN, SRC_SN, ACTOR);
        LsTaskEventLog restored = LsTaskEventLog.frameRestored(RAW_SN, SRC_SN, ACTOR);

        // then — 방향은 이벤트 타입 코드가 구분한다(사유 문구로 구분하지 않는다)
        assertThat(discarded.getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_FRAME_DISCARD);
        assertThat(restored.getEventTypeCd()).isEqualTo(LsTaskEventLog.EVENT_FRAME_RESTORE);

        for (LsTaskEventLog log : new LsTaskEventLog[]{discarded, restored}) {
            assertThat(log.getRawDataId()).isEqualTo(RAW_SN);   // NOT NULL 컬럼
            assertThat(log.getActorUserNo()).isEqualTo(ACTOR);  // 누가
            assertThat(log.getOcrnDt()).isNotNull();            // 언제
            // 어느 프레임 — RSN 에 식별자만 싣는다(privacyMetaReset 의 rprtSn= 선례와 동일)
            assertThat(log.getRsn()).isEqualTo("srcSn=" + SRC_SN);
        }
    }

    @Test
    @DisplayName("폐기_감사_이벤트는_사유와_이미지경로를_담지_않는다")
    void 폐기_감사_이벤트는_사유와_이미지경로를_담지_않는다() {
        // given / when
        LsTaskEventLog discarded = LsTaskEventLog.frameDiscarded(RAW_SN, SRC_SN, ACTOR);
        LsTaskEventLog restored = LsTaskEventLog.frameRestored(RAW_SN, SRC_SN, ACTOR);

        for (LsTaskEventLog log : new LsTaskEventLog[]{discarded, restored}) {
            // then ① RSN 은 식별자 한 토큰뿐 — 자유 문구·경로·확장자가 낄 자리가 없다(CWE-359).
            assertThat(log.getRsn()).matches("^srcSn=\\d+$");
            assertThat(log.getRsn()).doesNotContain("/", "\\", ".jpg", ".png", ".mp4");
            // then ② 사람 축(subject/prev)은 쓰지 않는다 — 폐기는 배정 이벤트가 아니다.
            assertThat(log.getSubjectUserNo()).isNull();
            assertThat(log.getPrevUserNo()).isNull();
        }

        // then ③ 사유를 <받을 수도> 없다 — 팩토리 시그니처에 String 파라미터가 없다.
        //   (설계 확정 A13: 폐기 사유를 입력받지 않는다. 파라미터가 있으면 호출부가 언젠가 채운다.)
        for (String factory : new String[]{"frameDiscarded", "frameRestored"}) {
            Method method = Arrays.stream(LsTaskEventLog.class.getDeclaredMethods())
                    .filter(m -> factory.equals(m.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("LsTaskEventLog." + factory + " 가 없다"));
            assertThat(method.getParameterTypes())
                    .as("%s 는 사유(String) 파라미터를 받지 않아야 한다", factory)
                    .doesNotContain(String.class);
        }
    }

    @Test
    @DisplayName("이벤트_타입_코드는_표준도메인_20자를_넘지_않는다")
    void 이벤트_타입_코드는_표준도메인_20자를_넘지_않는다() {
        // EVNT_TYPE_CD 는 코드값 표준도메인 VARCHAR(20) — 넘으면 적재 시점에 DB 오류(500)가 된다.
        assertThat(LsTaskEventLog.EVENT_FRAME_DISCARD).hasSizeLessThanOrEqualTo(20);
        assertThat(LsTaskEventLog.EVENT_FRAME_RESTORE).hasSizeLessThanOrEqualTo(20);
    }
}
