package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.label.service.AutolabelPresence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 오토라벨({@code AUTOLABEL}) 묶음의 산출물 판정 — <b>자동 생성 라벨이 있는가</b>.
 * [@design SCREEN-009] [@design AC-051]
 *
 * <p>{@link VlmBundleArtifactPresence} 와 대칭이며, 판정 자체는 라벨 축의 단일 원천
 * {@link AutolabelPresence} 에 그대로 위임한다(술어·출처 목록을 여기서 복제하지 않는다).
 *
 * <p><b>⚠ "라벨이 있는가" 로 판정하면 안 된다</b> — 오토라벨이 만든 라벨과 사람이 손으로 그린 라벨이
 * 같은 테이블에 있어, 전체 존재로 판정하면 작업자가 라벨을 <b>하나라도 그린 순간</b> 그 영상의
 * 오토라벨 묶음이 조치 목록에서 사라진다. 위임 대상이 자동 생성 라벨로 좁히는 이유가 이것이다.
 */
@Component
@RequiredArgsConstructor
public class AutolabelBundleArtifactPresence implements BundleArtifactPresence {

    private final AutolabelPresence autolabelPresence;

    @Override
    public BatchStageBundle bundle() {
        return BatchStageBundle.AUTOLABEL;
    }

    /** 자동 생성 라벨 1건 이상 = 오토라벨이 실제로 산출물을 남겼다 = 조치가 끝났다. */
    @Override
    public boolean exists(Long rawSn) {
        return autolabelPresence.exists(rawSn);
    }

    /**
     * <b>동기 완결 묶음이다</b> — AI 탐지·분할·보간이 전부 파이프라인 <b>안에서</b> 끝난다.
     *
     * <p>그래서 파이프라인이 완주했다면 <b>산출물이 0건이어도 조치는 끝난 것</b>이다. AI 가 정상
     * 수행했는데 아무것도 검출하지 못한 경우가 실재하며(검출 0건), 그것은 <b>정상 결과이지 실패가
     * 아니다</b>. 산출물 축만으로 판정하면 그 영상이 자동 라벨 0건이라 영구히 「조치 필요」로 남는다.
     */
    @Override
    public boolean completionIsSynchronous() {
        return true;
    }
}
