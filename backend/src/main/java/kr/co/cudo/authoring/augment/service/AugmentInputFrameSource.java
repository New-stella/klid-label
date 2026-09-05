package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 외부 증강 위탁에 실을 <b>프레임 경로</b>를 조달한다 — 프레임 축의 단일 진실원.
 *
 * <p>하는 일은 하나다: <b>조달처를 정하고 그 목록을 그대로 돌려준다.</b> 부재 판정과 거부는 호출부가
 * 한다(그쪽이 거부 사유를 원장에 남기는 자리다).
 *
 * <h3>영상 축과 <b>같은 모양</b>이다</h3>
 * <p>복사할 영상 파일의 조달은 {@link DerivativeSourceVideoResolver} 가 이미 같은 규칙으로 가른다 —
 * 관제는 비식별본, 포털 업로드 자산은 본인 원본, <b>서로 폴백하지 않는다</b>. 프레임 축만 비식별
 * 전용으로 남아 있으면 포털 자산은 위탁 입력을 <b>한 장도</b> 만들지 못해 전건 거부된다.
 *
 * <p>그래서 출처 판별을 여기서 다시 하지 않고 <b>그 판정기에 묻는다</b>. 채널 판별자를 두 곳에서
 * 비교하면 한쪽만 고쳐져 두 축이 조용히 갈린다.
 *
 * <h3>★ 기본값은 비식별본이다 — 되돌리지 말 것</h3>
 * <p>출처를 판별하지 못했을 때, 값이 비었을 때, 새 출처 값이 생겼을 때 모두 <b>비식별본</b>으로
 * 떨어진다. 그래야 관제 영상의 원본 프레임이 외부로 나가지 않는다. 「포털이 아니면 원본」이나
 * 「비식별본이 없으면 원본」으로 뒤집으면 그 순간 fail-open 이 된다.
 *
 * <p>⚠ <b>「포털이니 비우고 넘어간다」로 풀지 말 것</b> — 그렇게 하면 관제 자산에서 비식별본이 빠진
 * 경우까지 함께 열린다. 포털은 <b>다른 조달처를 갖는 것</b>이지 조달 요구가 면제되는 것이 아니다.
 *
 * @design ADR-058
 * @design ADR-013
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AugmentInputFrameSource {

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    /** 출처 판별 단일 지점 — 여기서 판별자를 다시 비교하지 않는다. */
    private final DerivativeSourceVideoResolver sourceResolver;

    /**
     * 위탁 입력 프레임 경로 목록 — {@code [srcSn, path]}, 프레임 순서 오름차순.
     *
     * <p>경로가 비어 있는 프레임도 <b>포함해</b> 돌려준다. 걸러 내면 호출부가 「몇 장이 비었는가」를
     * 알 수 없어 조용히 일부만 위탁된다.
     *
     * @param rawSn 증강 대상 영상. 없으면 조달처는 기본값(비식별본)이다
     */
    public List<Object[]> pathsOf(Long rawSn) {
        LsDataRaw parent = rawSn == null ? null : videoRepository.findById(rawSn).orElse(null);
        DerivativeSourceVideoResolver.Source source = sourceResolver.sourceOf(parent);
        return switch (source) {
            case PORTAL_ORIGINAL -> srcRepository.findOriginalFramePathsByRawSn(rawSn);
            case DEIDENTIFIED -> srcRepository.findDeidFramePathsByRawSn(rawSn);
        };
    }

}
