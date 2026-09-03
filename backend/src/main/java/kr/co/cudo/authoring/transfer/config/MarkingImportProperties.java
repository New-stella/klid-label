package kr.co.cudo.authoring.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 마킹 산출물 일괄 가져오기 설정 — {@code authoring.import.marking.*}.
 *
 * <h3>왜 값을 코드에 박지 않는가</h3>
 * <p>여기 담긴 값은 모두 <b>운영 형상에 따라 달라지는 상한</b>이다. 산출물 묶음의 규모도, 저장소의
 * 폴더 깊이도, 노드가 감당할 동시 처리 수도 우리가 정하는 것이 아니다. 코드에 박으면 값을 바꾸는 데
 * 배포가 필요하고, 근거 없는 숫자가 사양처럼 굳는다.
 *
 * <p>⚠ 이 값들을 <b>화면에 베껴 적지 않는다</b>. 설정이 바뀌면 화면이 거짓을 말한다. 상한에 걸린
 * 사실은 숫자가 아니라 {@code truncated} 로 알린다.
 *
 * @param maxDepth            훑을 폴더 깊이 상한(시작 폴더가 0). 넘는 하위 폴더는 훑지 않고 알린다
 * @param maxEntries          훑을 파일 수 상한. 넘으면 읽기를 그만두고 알린다(CWE-770)
 * @param maxItems            한 번에 다룰 마킹 문서 수 상한
 * @param maxUnmatchedNames   응답에 담을 「어느 문서도 가리키지 않은 영상」 이름 수 상한
 * @param maxProgressItems    진행 조회 응답에 담을 건별 결과 수 상한
 * @param videoExtensions     영상으로 볼 확장자 — 짝짓기 대상과 미참조 영상 집계의 기준이다
 * @param concurrency         한 노드가 동시에 처리할 항목 수 상한
 * @param maxRetry            같은 항목을 다시 집을 수 있는 횟수. 넘으면 실패로 마감한다(AC-1033)
 * @param fpsTolerance        역산값과 실측값의 허용 오차. 넘으면 그 항목의 마킹을 적용하지 않는다
 * @param staleTimeoutMinutes 처리 중인 채로 이만큼 갱신이 멈춘 항목을 다시 집을 수 있게 되돌린다
 * @design DOMAIN-017
 * @design ADR-053
 * @design API-216
 * @design API-217
 * @design AC-1033
 */
@ConfigurationProperties(prefix = "authoring.import.marking")
public record MarkingImportProperties(
        @DefaultValue("8") int maxDepth,
        @DefaultValue("20000") int maxEntries,
        @DefaultValue("2000") int maxItems,
        @DefaultValue("100") int maxUnmatchedNames,
        @DefaultValue("500") int maxProgressItems,
        @DefaultValue({"mp4", "mov", "avi", "mkv", "m4v", "wmv"}) List<String> videoExtensions,
        @DefaultValue("2") int concurrency,
        @DefaultValue("3") int maxRetry,
        @DefaultValue("0.5") double fpsTolerance,
        @DefaultValue("30") long staleTimeoutMinutes
) {

    /**
     * 동시 처리 수의 하한 — 0·음수 설정이 그대로 먹히면 <b>일꾼이 하나도 뜨지 않아</b> 작업이
     * 등록만 되고 영원히 진행되지 않는다. 그 상태는 예외도 로그도 남기지 않는 조용한 정지다.
     */
    public int effectiveConcurrency() {
        return Math.max(1, concurrency);
    }

    /**
     * 다시 집을 수 있는 횟수의 하한 — 음수면 첫 재시도부터 실패로 마감되어 <b>재기동 복구가 사실상
     * 꺼진다</b>. 0 은 「다시 집지 않는다」는 정당한 선택이라 그대로 둔다.
     */
    public int effectiveMaxRetry() {
        return Math.max(0, maxRetry);
    }

    /**
     * 허용 오차의 하한 — 0·음수면 부동소수 꼬리만으로도 대조가 틀어져 <b>정상 묶음이 전건 실패</b>
     * 한다. 역산은 나눗셈이라 실측값과 정확히 같을 수 없다.
     */
    public double effectiveFpsTolerance() {
        return fpsTolerance > 0 ? fpsTolerance : 0.5;
    }

    /**
     * 되돌리기 임계의 하한 — 너무 짧으면 <b>지금 돌고 있는 처리</b>를 빼앗아 같은 항목이 두 번
     * 적재된다. 정상 항목 하나는 파일 복사를 포함해도 분 단위를 넘지 않는다.
     */
    public long effectiveStaleTimeoutMinutes() {
        return Math.max(5L, staleTimeoutMinutes);
    }
}
