package kr.co.cudo.authoring.sysconfig.endpoint;

import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 연동 주소의 <b>판정 단일 지점</b> — "설정에 값이 있으면 설정, 없으면 배포 기본값" (R11).
 *
 * <h3>왜 한 곳인가</h3>
 * <p>이 우선순위를 호출처마다 배선하면 한 곳만 갱신돼 새는 것이 이 저장소의 반복 결함이다.
 * 주소를 필요로 하는 모든 경로는 이 클래스만 부른다.
 *
 * <h3>지연 조회 ({@link ObjectProvider})</h3>
 * <p>{@code WebClientConfig} 의 빈들이 이 리졸버를 물고 만들어지는데, 리졸버가
 * {@link SystemConfigService}(→ JPA) 를 <b>생성자에서</b> 요구하면 인프라 빈 초기화 순서가 앞당겨진다.
 * 실제 조회는 <b>요청 시점</b>이라 그때 꺼내면 충분하므로 {@link ObjectProvider} 로 지연한다.
 *
 * <h3>조회 실패는 기본값으로 폴백한다 (fail-safe)</h3>
 * <p>설정 행이 없는 것이 <b>정상 상태</b>다(시드하지 않는다 — 값이 없으면 배포 기본값을 쓰는 것이
 * 설계다). DB 순단·행 부재·타입 불일치는 모두 "override 없음"으로 취급해 배포 기본값으로 나간다 —
 * 설정 조회가 안 된다고 이미 동작하던 연동을 멈추는 것은 과잉이다.
 *
 * <p>⚠ 조회 실패를 낮추는 이 fail-safe 는 <b>값 판정과 무관하다</b> — 값 판정은 저장 시점에 이미
 * 끝났다(아래 §값 판정).
 *
 * <h3>★ 값 판정은 저장 시점에 끝난다</h3>
 * <p>구 설계는 여기서 <b>요청 전송 직전에 IP 대역을 재검증</b>(DNS rebinding 대응)했으나, 대역 차단
 * 자체가 폐지되어(2026-08-10 사용자 확정 — {@link IntegrationEndpointUrlValidator} 참조) 재검증할
 * 내용이 없어졌다. 스키마·형식은 저장 시점에 확정되고 이후 이름 해석으로 바뀌지 않는다.
 *
 * <h3>캐시 — 자체 캐시를 두지 않되, <b>부재도 캐시되는</b> 조회를 쓴다</h3>
 * <p>{@link SystemConfigService#findString(String)} 이 Caffeine(TTL 60s)이며 <b>값 변경 시 전체
 * 무효화</b>된다. 그래서 같은 노드에서는 저장 즉시 다음 호출부터 새 주소가 나간다. 여기에 캐시를 한
 * 겹 더 두면 그 무효화가 도달하지 못해 "즉시 반영"이 깨진다.
 *
 * <p>★ <b>{@code getString} 이 아니라 {@code findString} 을 쓰는 이유</b>: override 행이 <b>없는 것이
 * 정상 상태</b>인데 {@code getString} 은 그때 예외를 던지고 Spring 캐시는 <b>예외를 캐시하지 않는다</b>.
 * 즉 정상 배포에서는 캐시 엔트리가 한 번도 만들어지지 않아 <b>외부 호출마다 DB 왕복 + 예외 생성</b>이
 * 반복됐다 — "Caffeine 이 이미 막는다"는 근거가 정상 상태에서 성립하지 않았다. 이 경로에는 라벨링
 * 캔버스의 온라인 오토라벨·SAM2 처럼 <b>사용자 클릭당 발생하는 대화형 핫패스</b>가 있어 무시할 수 없다.
 *
 * <p>다른 노드는 TTL 만큼(최대 60초) 늦게 반영된다 — 2노드 Active-Active 의 기존 성질이며 이
 * 기능이 새로 만든 지연이 아니다.
 */
@Slf4j
@Component
public class IntegrationEndpointResolver {

    private final ObjectProvider<SystemConfigService> systemConfigService;

    public IntegrationEndpointResolver(ObjectProvider<SystemConfigService> systemConfigService) {
        this.systemConfigService = systemConfigService;
    }

    /**
     * 현재 유효한 주소를 돌려준다 — override 가 있으면 그것, 없으면 배포 기본값.
     *
     * <p><b>예외를 던지지 않는다.</b> 값 판정은 저장 시점({@code validateForSave})에 끝났고, 조회
     * 실패는 "override 없음"으로 낮춘다. 즉 이 메서드 때문에 외부 호출이 실패하는 경우는 없다.
     *
     * @param endpoint    대상 연동
     * @param bootDefault 배포 기본값(@Value 주입값). override 부재 시 이 값이 그대로 쓰인다.
     */
    public String resolve(IntegrationEndpoint endpoint, String bootDefault) {
        String override = readOverride(endpoint);
        return override == null ? bootDefault : override;
    }

    /** override 가 설정돼 있는지(=배포 기본값이 아닌지). 화면 표시·진단용. */
    public boolean hasOverride(IntegrationEndpoint endpoint) {
        return readOverride(endpoint) != null;
    }

    /**
     * 설정 override 를 읽는다. 없거나 읽을 수 없으면 {@code null}.
     *
     * <p>"행 없음"(정상 상태)은 {@link SystemConfigService#findString(String)} 이
     * {@code Optional.empty()} 로 <b>값처럼</b> 돌려주므로 예외 경로를 타지 않는다(그래서 캐시된다).
     * 남은 예외(타입 불일치·DB 순단)도 <b>연동을 멈출 사유가 아니라</b> 삼키되, 조용히 넘기지 않고
     * DEBUG 로 남긴다 — 값을 넣었는데 반영이 안 되는 상황을 추적할 수 있어야 한다.
     */
    private String readOverride(IntegrationEndpoint endpoint) {
        SystemConfigService service = systemConfigService.getIfAvailable();
        if (service == null) {
            return null;
        }
        Optional<String> value;
        try {
            value = service.findString(endpoint.configKey());
        } catch (RuntimeException e) {
            log.debug("[IntegrationEndpoint] override 조회 실패 target={} reason={}",
                    endpoint.name(), e.getClass().getSimpleName());
            return null;
        }
        return value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }
}
