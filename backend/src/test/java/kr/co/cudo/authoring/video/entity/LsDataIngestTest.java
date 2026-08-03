package kr.co.cudo.authoring.video.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 인입 엔티티({@link LsDataIngest}) 상태 전이·정제 규칙 단위 검증 (Phase 2).
 *
 * <p>DB 왕복이 필요한 매핑 정합·조회 순서·전이 영속화는
 * {@code kr.co.cudo.authoring.video.repository.LsDataIngestRepositoryIT} 가 담당한다.
 */
class LsDataIngestTest {

    /** CR(0x0D) · LF(0x0A) · TAB(0x09) · NUL(0x00) · 유니코드 라인 구분자(U+2028) — 제거 대상. */
    private static final String FORGED_MESSAGE =
            "실패" + (char) 0x0D + (char) 0x0A + "INFO 위조 라인" + (char) 0x09
                    + "삽입" + (char) 0x00 + (char) 0x2028 + "끝";

    @Test
    @DisplayName("처리착수_전이는_엔티티에_비원자_통로로_존재하지_않는다")
    void 처리착수_전이는_엔티티에_비원자_통로로_존재하지_않는다() {
        // given — 관제가 INSERT 한 직후의 미처리 행(DEFAULT PENDING)
        LsDataIngest ingest = pendingIngest();
        assertThat(ingest.getProcSttsCd()).isEqualTo(LsDataIngest.PROC_STTS_PENDING);

        // then — PENDING→PROCESSING 을 필드 대입으로 수행하는 메서드가 하나도 없다.
        //   구 markProcessing() 은 "읽고-쓰기"라 두 실행이 같은 행을 각자 PROCESSING 으로 쓰고
        //   둘 다 "내가 잡았다"고 착각했다(CWE-362 → 같은 클립 중복 적재). 착수 전이는
        //   LsDataIngestRepository#claimForProcessing 의 조건부 UPDATE 한 곳으로만 한다.
        assertThat(Arrays.stream(LsDataIngest.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .map(Method::getName))
                .as("비원자 착수 전이 통로 금지 — 재도입 차단")
                .doesNotContain("markProcessing", "markProcessed", "markInProgress", "claim");

        // then — 상태 상수 자체는 남는다(클레임 쿼리·판정이 참조하는 어휘)
        assertThat(LsDataIngest.PROC_STTS_PROCESSING).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("markDone_호출시_상태와_RAW_SN과_처리일시가_갱신된다")
    void markDone_호출시_상태와_RAW_SN과_처리일시가_갱신된다() {
        // given
        LsDataIngest ingest = pendingIngest();

        // when — 적재 성공(또는 기적재 확인)으로 종결
        ingest.markDone(4242L);

        // then
        assertThat(ingest.getProcSttsCd()).isEqualTo(LsDataIngest.PROC_STTS_DONE);
        assertThat(ingest.getRawSn()).isEqualTo(4242L);
        assertThat(ingest.getPrcsDt()).isNotNull();
    }

    @Test
    @DisplayName("markFailed_호출시_재시도횟수가_증가하고_에러메시지가_정제되어_저장된다")
    void markFailed_호출시_재시도횟수가_증가하고_에러메시지가_정제되어_저장된다() {
        // given — 재시도 카운터 초기값 0
        LsDataIngest ingest = pendingIngest();
        assertThat(ingest.getRtyCnt()).isZero();

        // when
        ingest.markFailed("파일 형식이 지원되지 않습니다");

        // then — 상태·사유·시각·재시도 횟수가 함께 남는다(조용한 유실 금지)
        assertThat(ingest.getProcSttsCd()).isEqualTo(LsDataIngest.PROC_STTS_FAILED);
        assertThat(ingest.getRtyCnt()).isEqualTo(1);
        assertThat(ingest.getErrMsg()).isEqualTo("파일 형식이 지원되지 않습니다");
        assertThat(ingest.getPrcsDt()).isNotNull();

        // when — 두 번째 실패
        ingest.markFailed("두 번째 실패");

        // then — 재시도 횟수가 누적된다
        assertThat(ingest.getRtyCnt()).isEqualTo(2);
        assertThat(ingest.getErrMsg()).isEqualTo("두 번째 실패");
    }

    @Test
    @DisplayName("ERR_MSG에_개행이_포함돼도_정제되어_저장된다")
    void ERR_MSG에_개행이_포함돼도_정제되어_저장된다() {
        // given — 로그·감사 라인 위조 시도 (CWE-117)
        LsDataIngest ingest = pendingIngest();

        // when
        ingest.markFailed(FORGED_MESSAGE);

        // then — 개행·제어문자·라인 구분자만 제거되고 일반 공백(U+0020) 등 가시 문자는 보존된다
        assertThat(ingest.getErrMsg())
                .doesNotContain(String.valueOf((char) 0x0D))
                .doesNotContain(String.valueOf((char) 0x0A))
                .doesNotContain(String.valueOf((char) 0x09))
                .doesNotContain(String.valueOf((char) 0x00))
                .doesNotContain(String.valueOf((char) 0x2028));
        assertThat(ingest.getErrMsg()).isEqualTo("실패INFO 위조 라인삽입끝");
    }

    @Test
    @DisplayName("ERR_MSG가_컬럼_길이를_넘으면_잘려서_저장된다")
    void ERR_MSG가_컬럼_길이를_넘으면_잘려서_저장된다() {
        // given — VARCHAR(4000) 상한을 넘는 비정상 입력(외부 예외 메시지 유입 등)
        LsDataIngest ingest = pendingIngest();
        String tooLong = "가".repeat(LsDataIngest.ERR_MSG_MAX + 500);

        // when
        ingest.markFailed(tooLong);

        // then — 컬럼 길이를 넘지 않아 UPDATE 가 값 초과로 실패하지 않는다
        assertThat(ingest.getErrMsg()).hasSizeLessThanOrEqualTo(LsDataIngest.ERR_MSG_MAX);
    }

    @Test
    @DisplayName("markFailed에_null_사유가_들어와도_placeholder를_저장하지_않는다")
    void markFailed에_null_사유가_들어와도_placeholder를_저장하지_않는다() {
        // given
        LsDataIngest ingest = pendingIngest();

        // when — 사유를 특정하지 못한 경우
        ingest.markFailed(null);

        // then — 로그 전용 "(null)" placeholder 가 DB 컬럼으로 새지 않는다
        assertThat(ingest.getErrMsg()).isNull();
        assertThat(ingest.getRtyCnt()).isEqualTo(1);
    }

    @Test
    @DisplayName("관제_수신_컬럼에는_setter가_없다")
    void 관제_수신_컬럼에는_setter가_없다() {
        // given — 관제 소유값(29컬럼)을 저작도구가 덮지 않는다는 계약 (CWE-915 Mass Assignment 방어)
        List<Method> declared = Arrays.stream(LsDataIngest.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .toList();

        // then — setter 형태 메서드가 하나도 없다(@Data/@Setter 재도입 차단)
        assertThat(declared).extracting(Method::getName)
                .as("setter 금지")
                .noneMatch(name -> name.startsWith("set"));

        // then — 외부에 열린 변경 통로는 저작도구 운영 컬럼 <종결> 전이 2종뿐이다.
        //   착수 전이(PENDING→PROCESSING)는 엔티티에 두지 않는다 — 원자 클레임
        //   (LsDataIngestRepository#claimForProcessing) 이 유일한 통로다.
        List<String> publicMutators = declared.stream()
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.getReturnType() == void.class)
                .map(Method::getName)
                .distinct()
                .sorted()
                .toList();
        assertThat(publicMutators)
                .as("운영 컬럼 전이 외 변경 통로 금지")
                .containsExactly("markDone", "markFailed");

        // then — 필드 직접 대입도 막혀 있다
        assertThat(Arrays.stream(LsDataIngest.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .map(Field::getModifiers))
                .as("인스턴스 필드는 전부 private")
                .allMatch(Modifier::isPrivate);
    }

    @Test
    @DisplayName("엔티티에는_INSERT_팩토리가_없다")
    void 엔티티에는_INSERT_팩토리가_없다() {
        // given — 행을 만드는 주체는 관제다. 저작도구는 읽고 상태만 바꾼다.
        //   유일한 예외가 내부 REVIEWER 업로드(우리가 정당한 origin 인 흐름)이고, 그 통로는
        //   InternalUploadIngestWriter <하나>여야 한다. 통로가 늘면 그 자체가 관제 소유 컬럼의
        //   일반 쓰기 경로가 된다(CWE-915).
        //   ※ 프로덕션 소스 스캔(통로가 정말 하나인가)은 아키텍처 가드가 담당한다 —
        //     kr.co.cudo.authoring.architecture.LsDataIngestWriteGuardTest (주석 스트리핑 포함).

        // then — 엔티티에 인스턴스를 만들어 돌려주는 public 정적 팩토리가 없다
        assertThat(Arrays.stream(LsDataIngest.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.getReturnType() == LsDataIngest.class)
                .map(Method::getName))
                .as("엔티티 INSERT 팩토리 금지")
                .isEmpty();
    }

    /** 관제 INSERT 직후 형상 — 운영 컬럼은 DB DEFAULT(PENDING/0)와 동일하게 초기화된다. */
    private LsDataIngest pendingIngest() {
        return new LsDataIngest();
    }
}
