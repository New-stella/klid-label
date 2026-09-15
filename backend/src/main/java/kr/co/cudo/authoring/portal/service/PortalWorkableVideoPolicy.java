package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 포털 <b>작업 가능 영상</b> 판정의 단일 지점 — 「검수 승인(APPROVED)」 <b>또는</b> 「출처 PORTAL_DATASET」.
 *
 * <h3>왜 조건이 둘인가 (ADR-068)</h3>
 * <p>포털 채널 배포본에는 검수·승인 흐름이 없다. 포털 데이터셋 소재에서 등록한 영상은 승인이 영영 생기지
 * 않으므로 <b>승인이 아니라 출처</b>로 허용한다. 반대로 그 영상에 승인 상태를 꾸며 넣으면 관제 조회 뷰·
 * 완료/수정 통지·산출물 재생성이 그 영상을 승인 영상으로 읽고 반응한다 — 그래서 상태를 건드리지 않고
 * <b>판정을 넓힌다</b>. 승인 조건은 관제향에서 넘어온 기존 데이터마트 경로를 위해 그대로 남는다.
 *
 * <h3>★ 판정은 여기 한 곳이다 — 창구마다 넓히지 않는다</h3>
 * <p>프레임 라벨 조회·프레임 이미지·라벨 저장·메타·이벤트 어노테이션·내 작업 진입·ZIP 내려받기가 모두
 * 이 판정을 쓴다. 창구마다 따로 넓히면 <b>넓히지 않은 창구 하나에서 흐름이 끊긴다</b>(오류가 아니라 403
 * 이라 조용하다).
 *
 * <h3>★ 단건은 일괄에 위임하고 「그 식별자를 담고 있는가」로 답한다</h3>
 * <p>「결과가 비어 있지 않다」로 답하면 안전성이 <b>조회가 요청한 것만 돌려준다</b>는 계약으로 이전된다.
 * 일괄 결과도 <b>요청한 식별자만</b> 남기도록 한 번 더 거른다.
 *
 * <h3>이 판정이 하지 않는 것</h3>
 * <ul>
 *   <li><b>본인 업로드 자산(PORTAL_ULD)</b> — 소유자 축이라 이 판정의 대상이 아니다. 업로드 자산은 여기서
 *       언제나 거짓이며, 소유 판정은 {@link PortalWorkTargetResolver} 가 따로 건다.</li>
 *   <li><b>비식별 누락 신고 게이트</b> — 호출부가 그대로 함께 건다. 이 판정이 참이라고 열리는 것이 아니다.</li>
 *   <li><b>구 데이터마트 영상 목록</b> — 그 목록은 승인 영상만 싣는다(데이터셋 영상은 데이터셋 영상 목록
 *       창구로만 노출된다). 이 판정으로 그 목록을 채우지 말 것.</li>
 * </ul>
 *
 * @design ADR-068
 * @design AC-1120
 */
@Component
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class PortalWorkableVideoPolicy {

    /**
     * 포털 작업 가능 영상이 아닐 때의 거부 문구 — <b>한 상수</b>다.
     *
     * <p>구 문구(「데이터마트에 노출되지 않은 영상입니다.」)는 데이터셋 영상 축과 어긋난다. 문구를 창구마다
     * 적으면 한쪽만 바뀌어 같은 사유가 서로 다른 말로 나간다.
     */
    public static final String NOT_WORKABLE_MESSAGE = "포털에서 작업할 수 없는 영상입니다.";

    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final VideoRepository videoRepository;

    /** 단건 판정 — 행 부재·타 상태·타 출처는 거짓(fail-closed). */
    public boolean isWorkable(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return workable(List.of(rawSn)).contains(rawSn);
    }

    /**
     * 일괄 판정 — 요청한 식별자 중 작업 가능한 것만 돌려준다. 부재·거짓은 <b>결과에 없다</b>.
     *
     * <p>승인 상태로 이미 참인 식별자는 원장 조회를 생략한다 — 대부분의 기존 호출(승인 영상)이 추가 조회를
     * 치르지 않는다.
     */
    public Set<Long> workable(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Set.of();
        }
        Set<Long> requested = new LinkedHashSet<>();
        rawSns.stream().filter(Objects::nonNull).forEach(requested::add);
        if (requested.isEmpty()) {
            return Set.of();
        }

        Set<Long> result = new LinkedHashSet<>();
        for (LsRawDataStatus status : rawDataStatusRepository.findAllById(List.copyOf(requested))) {
            if (status != null && LsRawDataStatus.STTS_APPROVED.equals(status.getDataSttsCd())) {
                result.add(status.getRawDataId());
            }
        }

        Set<Long> remaining = new LinkedHashSet<>(requested);
        remaining.removeAll(result);
        if (!remaining.isEmpty()) {
            for (LsDataRaw raw : videoRepository.findAllById(List.copyOf(remaining))) {
                if (raw != null && raw.isPortalDataset()) {
                    result.add(raw.getRawSn());
                }
            }
        }

        // ★ 요청하지 않은 식별자를 섞어 돌려주지 않는다 — 조회 계약에 안전성을 기대지 않는다.
        result.retainAll(requested);
        return result;
    }
}
