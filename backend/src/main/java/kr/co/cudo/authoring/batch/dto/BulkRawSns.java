package kr.co.cudo.authoring.batch.dto;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 일괄 요청의 <b>대상 목록 규칙</b> 단일 지점 — 상한 · 중복 축약 · 순서 보존.
 * [@design API-212] [@design API-213] [@design API-214]
 *
 * <p>일괄 스킵/해제/재수행 요청 DTO 가 셋인데 규칙은 하나다. record 는 상속이 없으므로 규칙을 이 곳에
 * 모아 각 DTO 가 <b>호출</b>하게 한다 — 복사해 두면 한쪽만 고쳐져 같은 요청이 엔드포인트에 따라 다르게
 * 해석된다(이 저장소의 반복 결함 패턴).
 */
public final class BulkRawSns {

    /**
     * 1회 호출 상한 — 초과 요청은 400 으로 거부한다.
     *
     * <p>값이 100 인 것은 기존 일괄 재처리({@code BatchBulkRetryRequest.MAX_SIZE})와 <b>같은 계약을
     * 의도적으로 따르는 것</b>이다(설계가 "배치 일괄 재처리와 같은 계약"이라고 규정한다). 두 값이
     * 갈리면 화면이 엔드포인트마다 다른 상한을 알아야 한다 — 동치는 테스트가 고정한다.
     *
     * <p>상한을 두는 이유(CWE-770): 각 건이 DB 쓰기·비동기 파이프라인 기동을 유발하므로 무제한 목록은
     * 한 요청으로 자원을 포화시킬 수 있다. 남은 건은 응답을 보고 재호출로 이어서 처리한다.
     */
    public static final int MAX_SIZE = 100;

    private BulkRawSns() {
    }

    /**
     * 중복·null 을 제거한 처리 대상(요청 순서 보존).
     *
     * <p>순서를 보존하는 이유: 응답의 {@code results} 가 요청 순서와 같아야 화면이 행을 짝지을 수 있다.
     */
    public static List<Long> distinct(List<Long> rawSns) {
        if (rawSns == null) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long rawSn : rawSns) {
            if (rawSn != null) {
                unique.add(rawSn);
            }
        }
        return List.copyOf(unique);
    }
}
