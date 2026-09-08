package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.common.client.AiSrvrTargetResolver;
import kr.co.cudo.authoring.common.client.AiWorkload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 추론 호출의 목적지를 <b>장비 원장에서</b> 고른다 — 배포 기본 주소 축을 대체한다. [@design ADR-057]
 *
 * <h3>구 조달 경로와 무엇이 다른가</h3>
 * <p>종전에는 추론 주소가 <b>단일 설정값</b>에서 왔다(연동 주소 판정 → 시스템 설정). 장비가 둘이어도
 * 요청은 늘 한 대로 갔다. 이제 그 축은 <b>원장</b>이 갖는다 — 근거 결정이 앞선 연동 주소 결정의
 * 「AI 추론 서버 주소 축」을 대체한다고 명시한 바로 그 지점이다. 설정값은 원장이 비었을 때
 * <b>첫 행을 심는 씨앗</b>으로만 남는다. [@design ADR-046]
 *
 * <h3>★ 판정을 여기서 새로 쓰지 않는다</h3>
 * <p>「고를 수 있는 장비인가」와 「후보가 0이면 어떻게 하는가」는 {@link AiSrvrSelector} 가 단독으로
 * 갖는다. 여기는 <b>용도 축을 옮겨 적고 주소를 꺼내는 것</b>만 한다 — 규칙을 옮겨 적으면 그 사본이
 * 두 번째 진실원이 되어, 선택기가 거른 장비를 이 자리가 그대로 쓰는 어긋남이 열린다.
 *
 * <h3>영상 고정은 여기서 하지 않는다</h3>
 * <p>이 자리는 <b>영상이 무엇인지 모른다</b>. 화면에서 쓰는 요청은 요청 하나가 한 장비로 가면 그
 * 안에서 연속성이 지켜지므로 고정할 것이 없고, 배치는 영상 단위로 고정해야 하므로
 * {@link AiSrvrBatchAssignment} 가 따로 맡아 <b>고른 주소를 호출자가 직접 넘긴다</b>.
 */
@Component
@RequiredArgsConstructor
public class LedgerAiSrvrTargetResolver implements AiSrvrTargetResolver {

    private final AiSrvrSelector selector;

    /**
     * {@inheritDoc}
     *
     * <p>용도 축은 그대로 옮긴다 — 배치 호출은 배치 슬롯의 부하만, 화면 호출은 화면 슬롯의 부하만
     * 본다. 두 값을 합쳐 보면 장비 안에서 갈라 놓은 것을 부르는 쪽에서 다시 붙이는 셈이 되어,
     * 일괄 처리가 밀린 장비를 화면 요청이 <b>피할 이유가 없는데도 피하게</b> 된다.
     */
    @Override
    public Optional<String> resolveAddress(AiWorkload workload) {
        return selector.select(LsAiSrvr.SrvrType.INFERENCE, usageOf(workload))
                .map(LsAiSrvr::getSrvrAddr);
    }

    /**
     * 호출 용도 → 장비 안의 실행 슬롯 축.
     *
     * <p>용도를 모르면 <b>화면</b>으로 본다 — 표시를 빠뜨려도 사람이 쓰는 쪽이 보호되는 방향으로
     * 틀리게 한다는 것이 이 축의 기존 규칙이다. [@design ADR-056]
     */
    static AiSrvrUsageType usageOf(AiWorkload workload) {
        return workload == AiWorkload.BATCH ? AiSrvrUsageType.BATCH : AiSrvrUsageType.INTERACTIVE;
    }
}
