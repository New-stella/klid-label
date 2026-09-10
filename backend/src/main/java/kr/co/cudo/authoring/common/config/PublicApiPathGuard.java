package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link PublicApiPathDefaults#PROPERTY_KEY} 형식 검증 — <b>기동 시 한 번, fail-closed</b>.
 *
 * <h2>왜 기동을 막나</h2>
 * <p>이 값은 브라우저가 그대로 부를 <b>주소의 앞부분</b>이 된다. 잘못된 값이 실리면 증상이
 * 「영상이 재생되지 않는다」로만 나타나고, 그 원인이 설정 오타라는 사실은 어디에도 남지 않는다.
 * 요청 시점에 던지면 오타 하나가 500 폭주가 되므로, <b>배포 시점에 시끄럽게 실패</b>시킨다.
 * ({@code QuartzClusteringGuard}·{@code GenAiIntegrationWiringGuard} 와 같은 골격이다.)
 *
 * <h2>무엇을 막나</h2>
 * <ul>
 *   <li><b>스킴·호스트</b>({@code http://…}, {@code //other.example}) — 이 값은 <b>같은 출처의
 *       경로</b>여야 한다. 절대 주소를 허용하면 우리가 내려준 주소가 곧 <b>바깥으로 끌고 가는
 *       통로</b>가 된다(CWE-601). 프로토콜 상대 표기 {@code //} 도 같은 이유로 막는다.</li>
 *   <li><b>상위 경로 순회</b>({@code ..}) — 접두어를 벗어나는 주소가 만들어진다(CWE-22).</li>
 *   <li><b>질의·조각</b>({@code ?} {@code #}) — 뒤에 경로가 이어 붙으므로 그 자리에 들어가면
 *       주소가 통째로 뒤틀린다.</li>
 *   <li><b>공백·제어문자</b> — 응답 본문과 로그에 그대로 실린다(CWE-117).</li>
 * </ul>
 */
@Slf4j
@Component
public class PublicApiPathGuard {

    private final String configured;

    public PublicApiPathGuard(@Value(PublicApiPathDefaults.VALUE_EXPRESSION) String configured) {
        this.configured = configured;
    }

    @PostConstruct
    void validate() {
        String raw = configured == null ? "" : configured.trim();
        // ★ 미설정이 «정상이자 권장»이다 — 그때는 WAR 웹 컨텍스트에서 도출한다.
        //   확정된 값은 PublicApiPath 빈이 기동 로그에 한 줄로 남긴다.
        if (raw.isEmpty()) {
            log.info("[PublicApiPath] 미설정 — 웹 컨텍스트에서 도출한다(권장). 확정값은 PublicApiPath 로그 참조.");
            return;
        }
        reject(!raw.startsWith("/"), raw, "'/' 로 시작해야 합니다");
        reject(raw.startsWith("//"), raw, "프로토콜 상대 표기(//)는 허용하지 않습니다");
        reject(raw.contains("://"), raw, "스킴·호스트를 포함할 수 없습니다 (같은 출처 경로여야 합니다)");
        reject(raw.contains(".."), raw, "상위 경로 순회(..)를 포함할 수 없습니다");
        reject(raw.contains("?") || raw.contains("#"), raw, "질의(?)·조각(#)을 포함할 수 없습니다");
        reject(hasWhitespaceOrControl(raw), raw, "공백·제어문자를 포함할 수 없습니다");

        log.info("[PublicApiPath] 브라우저 호출 접두어 = {}", PublicApiPathDefaults.normalize(raw));
    }

    private static boolean hasWhitespaceOrControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return true;
        }
        return false;
    }

    private static void reject(boolean violated, String raw, String reason) {
        if (!violated) return;
        throw new IllegalStateException(
                PublicApiPathDefaults.PROPERTY_KEY + " 값이 부적합합니다: '" + raw + "' — " + reason
                        + "\n  · 이 값은 브라우저가 우리 API 를 부를 때 쓰는 «같은 출처 경로 접두어» 입니다."
                        + "\n  · 예) 포털 향 /authoring-api/v1 · 관제 향 /label-studio/api/v1"
                        + "\n  · ★ 대개는 «지정하지 않는 것»이 맞습니다 — WAR 웹 컨텍스트에서 자동 도출합니다."
                        + "\n    앞단이 접두어를 «떼고» 넘기는 향에서만 명시하세요.");
    }
}
