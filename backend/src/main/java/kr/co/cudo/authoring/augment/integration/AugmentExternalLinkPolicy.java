package kr.co.cudo.authoring.augment.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 외부 증강 <b>연동 여부</b> 판정 — 단일 진실원 ({@code @design API-060}).
 *
 * <h3>★ 판정 축은 「주소가 주입됐는가」 하나다 (2026-09-03 사용자 확정, 구속)</h3>
 * <p>구 판정 축인 <b>미연동 모드 토글</b>({@code authoring.augment.external.mode=noop})은 <b>설정 키·
 * 판정기·전용 클라이언트 구현까지 통째로 폐기</b>됐다. 이제 <b>연동이 유일한 형상</b>이며 「나갈지
 * 말지」를 환경설정으로 고르지 않는다. 그 결과 <b>「아직 연동 안 됨」의 유일한 표현은 위탁 주소가
 * 비어 있는 것</b>이고, 그때 <b>기동은 정상</b>이며 <b>연동을 시도하는 시점에 실패</b>한다.
 *
 * <p>⚠ <b>모드 토글을 되살리지 말 것.</b> 그 축은 「벤더 주소가 아직 없다」는 <b>일시 상태</b>를
 * 피하려고 만든 우회였고, 그 결과 개발·스테이징·운영이 전부 미연동으로 도망가 <b>증강 위탁이 실제로
 * 나간 적이 한 번도 없는</b> 상태가 굳었다. 주소 축 하나로 합치면 그 상태가 <b>설정 파일에 그대로
 * 드러난다</b>(주소가 비었으면 미연동이다 — 다른 곳을 볼 필요가 없다).
 *
 * <h3>왜 빈 타입 검사가 아니라 프로퍼티를 읽는가</h3>
 * <p>{@link ExternalAugmentClient} 인터페이스에는 연동 여부를 알려주는 메서드가 없고, 구현체 타입
 * 검사는 AOP 프록시가 끼면 조용히 거짓이 된다. 그래서 <b>위탁 WebClient 의 base-url 을 정하는 같은
 * 프로퍼티</b>를 같은 기본값(빈 문자열)으로 읽는다 — 판정과 배선이 같은 값을 본다.
 *
 * <p><b>생명주기</b>: 프로퍼티라 재기동 없이 바뀌지 않는다 — 위탁 WebClient 의 base-url 과 같은
 * 생명주기다. 동적 override(설정 화면·DB 기반 토글)를 여기에 얹지 말 것. 얹는 순간 "판정은 연동인데
 * 클라이언트가 보는 주소는 비었다" 같은 어긋남이 다시 열린다. ⚠ 증강은 아직
 * {@code IntegrationEndpoint} 에 등록돼 있지 않아 <b>운영 화면 주소 override 대상이 아니다</b>.
 */
@Component
public class AugmentExternalLinkPolicy {

    /** 위탁 주소 프로퍼티 키 — 위탁 WebClient 가 읽는 것과 <b>같은 키</b>다. */
    public static final String KEY_BASE_URL = "authoring.augment.external.base-url";

    private final String baseUrl;

    public AugmentExternalLinkPolicy(@Value("${" + KEY_BASE_URL + ":}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 외부 증강 시스템과 <b>연동되지 않은</b> 형상인가 (= 위탁 주소 미주입).
     *
     * <p>공백만 있는 값도 미주입으로 본다 — 설정 파일에 {@code base-url: " "} 가 남는 실수를 연동으로
     * 오인하면 상대 URI 가 되어 <b>loopback:80 으로 실제 연결이 나간다</b>.
     *
     * <p>⚠ <b>주소의 「형식·안전성」은 여기서 보지 않는다</b> — 그 판정은
     * {@code AugmentUrlPolicy} 가 기동 시점에 하고 위반이면 기동을 막는다. 이 메서드는 "정해졌는가"
     * 만 본다(정해졌는데 위험한 주소면 애초에 기동하지 못하므로 여기 도달하지 않는다).
     */
    public boolean isNotLinked() {
        return !StringUtils.hasText(baseUrl);
    }
}
