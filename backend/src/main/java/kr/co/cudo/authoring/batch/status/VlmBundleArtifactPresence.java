package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 시계열({@code VLM}) 묶음의 산출물 판정 — <b>시계열 메타가 적재됐는가</b>. [@design SCREEN-009] [@design AC-051]
 *
 * <p>판정을 여기서 다시 쓰지 않고 이미 있는 단일 원천 {@link VlmTimeseriesMetaPresence} 에 그대로
 * 위임한다. 그 판정은 보류 재개 러너의 멱등 조건과 중복 위탁 차단이 이미 공유하고 있어, 사본을 만들면
 * "재위탁을 건너뛰는 기준"과 "조치가 끝났다고 보는 기준"이 어느 날 조용히 갈린다.
 *
 * <p><b>⚠ 전체 메타 카운트로 판정하면 안 된다</b> — {@code LS_DATA_META} 에는 {@code video.*} 기술메타가
 * 같은 테이블에 들어 있어, 전체 카운트로 보면 <b>기술메타만 있고 시계열은 0건</b>인 영상(= 조치가
 * 필요한 바로 그 상태)이 "산출물 있음"으로 오산입돼 배너에서 사라진다. 위임 대상이 그 접두를
 * 제외하는 유일한 이유가 이것이다.
 */
@Component
@RequiredArgsConstructor
public class VlmBundleArtifactPresence implements BundleArtifactPresence {

    private final VlmTimeseriesMetaPresence timeseriesMetaPresence;

    @Override
    public BatchStageBundle bundle() {
        return BatchStageBundle.VLM;
    }

    /** 시계열 메타 1건 이상 = 위탁이 실제로 결과를 남겼다 = 조치가 끝났다. */
    @Override
    public boolean exists(Long rawSn) {
        return timeseriesMetaPresence.exists(rawSn);
    }

    /**
     * <b>동기 완결 묶음이 아니다</b> — 기본값({@code false})을 그대로 쓰되, 그 이유가 이 묶음의
     * 핵심 성질이라 명시적으로 재선언한다.
     *
     * <p>시계열 위탁은 <b>논블로킹 제출</b>이다. 스텝은 제출만 개시하고 즉시 반환하며 결과는 벤더가
     * <b>웹훅으로 나중에</b> 보낸다. 따라서 파이프라인이 완주해도 그것은 「보냈다」일 뿐 「끝났다」가
     * 아니다 — 여기에 종결 축을 붙이면 <b>벤더가 답하기 전에 조치 완료를 주장</b>하게 된다.
     * 「완주 시점에 성공 표식을 남기는 안」이 기각된 근거가 정확히 그것이다.
     *
     * <p>그래서 이 묶음은 <b>산출물 축(시계열 메타) 단독</b>으로 판정한다 — <b>서술이 적재되면 그게
     * 곧 성공</b>이라 논블로킹 문제를 자연히 비켜간다. 「검출 0건」에 해당하는 상태도 없으므로 종결
     * 축은 얻는 것 없이 거짓 음성만 만든다.
     */
    @Override
    public boolean completionIsSynchronous() {
        return false;
    }
}
