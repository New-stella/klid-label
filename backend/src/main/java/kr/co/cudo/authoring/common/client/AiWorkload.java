package kr.co.cudo.authoring.common.client;

import java.util.Locale;

/**
 * ai-server 호출의 <b>용도(workload)</b> 축 — 실행 슬롯 선택과 서킷 분리의 단일 진실원.
 *
 * <p>ai-server 는 용도별 실행 슬롯 둘(배치 전용 / 화면 전용)을 두고 요청을 가른다. 어느 슬롯으로
 * 갈지는 요청 헤더 {@value #HEADER_NAME} 이 정하며, <b>배치만 값을 싣고 그 밖의 모든 경우는 화면</b>이다
 * (미지정·빈값·오타·대소문자 불일치 포함). 표시할 곳이 배치 두 곳뿐이라 빠뜨릴 여지가 적고,
 * 빠뜨려도 사람이 쓰는 쪽이 보호되는 방향으로 틀린다. 근거 결정은 {@code ADR-056}.
 *
 * <p><b>같은 축이 서킷 분리에도 쓰인다.</b> 배치가 연달아 실패해 서킷이 열릴 때 화면 요청까지 함께
 * 막히면, ai-server 안에서 슬롯을 아무리 잘 나눠도 소용이 없다(그 차단은 호출하는 쪽에서 일어난다).
 * 슬롯 분리와 서킷 분리는 짝이며 하나만 하면 반쪽이다.
 *
 * <p>⚠ <b>서킷 이름은 반드시 {@link #circuitName()} 하나로만 조립한다.</b> 호출부마다 문자열을
 * 이어붙이면 축이 하나 더 붙는 순간 표기가 갈린다. 이중화가 들어오면 노드 축이
 * {@code ai-batch-{nodeId}} 형태로 <b>이 메서드에 인자로 추가</b>되며, 그때
 * <b>{@code nodeId} 는 {@code [a-z0-9]+} 로 제한</b>해야 한다 — 구분자가 하이픈이라 값에 하이픈이
 * 들어가면({@code gpu-01}) {@code ai-batch-gpu-01} 이 어디서 갈리는지 <b>파싱으로 복원되지 않고</b>,
 * 이름을 되읽는 메트릭·대시보드가 오류 없이 어긋난다.
 */
public enum AiWorkload {

    /** 배치 파이프라인 — 요청에 헤더를 실어 ai-server 의 배치 전용 슬롯으로 보낸다. */
    BATCH("batch"),

    /** 저작도구 화면 — <b>기본값</b>. 헤더를 싣지 않으며 ai-server 는 화면 전용 슬롯으로 처리한다. */
    INTERACTIVE(null);

    /** ai-server 가 실행 슬롯을 가르는 요청 헤더 이름. */
    public static final String HEADER_NAME = "X-Workload";

    /** 서킷 인스턴스 이름의 공통 앞머리. 노드 축이 붙어도 이 앞머리는 유지한다. */
    private static final String CIRCUIT_PREFIX = "ai";

    private final String headerValue;

    AiWorkload(String headerValue) {
        this.headerValue = headerValue;
    }

    /**
     * 요청에 실을 헤더 값. <b>{@code null} 이면 헤더를 붙이지 않는다</b>(화면이 기본값이므로
     * 명시할 필요가 없다 — 값을 지어내 붙이면 ai-server 판정이 「정확히 batch」 하나에서 늘어난다).
     */
    public String headerValue() {
        return headerValue;
    }

    /** 이 용도에 쓸 서킷 인스턴스 이름 — {@code ai-batch} / {@code ai-interactive}. */
    public String circuitName() {
        return CIRCUIT_PREFIX + "-" + name().toLowerCase(Locale.ROOT);
    }

    /** 용도를 명시하지 않은 호출의 기본값. 화면 쪽으로 틀리는 것이 안전하다. */
    public static AiWorkload defaultWorkload() {
        return INTERACTIVE;
    }
}
