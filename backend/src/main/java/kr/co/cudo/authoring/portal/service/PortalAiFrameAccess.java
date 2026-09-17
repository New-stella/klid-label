package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.AiFrameAccess;
import kr.co.cudo.authoring.video.service.FrameImageService;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * 포털 채널 {@link AiFrameAccess} — 포털 AI 보조(AI 탐지 · AI 분할 · AI 자동 추적)의 입력 경계.
 * 요청마다 한 행위자에 묶어 만든다({@link PortalAiFrameAccessFactory}). 빈이 아니다.
 *
 * <h3>세 축</h3>
 * <ul>
 *   <li><b>인가</b> — {@link PortalWorkTargetResolver#resolveFrame}: 데이터마트 노출(검수 완료) 영상의 프레임이거나
 *       본인 업로드 자산의 프레임만 통과. 행 부재 404 · 남의 자산/미노출 영상 403(같은 문구).</li>
 *   <li><b>입력 이미지</b> — 포털 화면이 그 프레임에 보여 주는 <b>서빙과 같은 함수</b>로 해석한다. 데이터마트는
 *       {@link PortalLabelService#resolveDatamartFrameFile}, 본인 업로드는
 *       {@link PortalUploadService#resolveUploadFrameFile}. 판정을 여기서 재구현하지 않는다 — 서빙과 추론 입력이
 *       갈리면 사용자에게 보이지 않는 픽셀이 추론 서버로 나가는 경로가 생긴다. 출처는 인가 판정이 정한다.</li>
 *   <li><b>차단 판정 없음</b> — {@link #requireNotBlocked} 를 재정의하지 않는다. 포털에는 비식별 파이프라인이
 *       없으므로 비식별 누락 신고(412)·재비식별 작업락(409)을 이 경로에 두지 않는다(2026-09-15 사용자 확정).
 *       데이터마트 서빙은 신고 구간에 412 인데 AI 창구는 막지 않는 비대칭은 그 확정의 귀결로 인지·수용됐다.</li>
 * </ul>
 *
 * <h3>파일 열기</h3>
 * <p>인코딩·치수 측정은 해석이 돌려준 실경로를 <b>링크 비추종</b>({@link FrameImageService#openNoFollow})으로 연다 —
 * 서빙과 같은 규약이다. AI 분할 본체는 {@link #resolveImage} 가 돌려준 경로를 직접 읽으므로 해석~읽기 사이
 * 링크 교체(TOCTOU)는 내부 채널 인코더와 같은 성질로 남는다(인지·수용).
 *
 * <h3>인가 메모</h3>
 * <p>한 인스턴스는 한 요청이라, 인가를 통과한 프레임과 그 출처를 메모해 같은 프레임의 중복 판정 조회를 피한다
 * (자동 추적은 창구가 교차 영상 선판정을 위해 한 번, 본체가 한 번 부른다). 인가를 거치지 않은 프레임이 이미지
 * 해석으로 들어오면 그 자리에서 인가부터 한다(fail-closed).
 */
@Slf4j
final class PortalAiFrameAccess implements AiFrameAccess {

    private final PortalWorkTargetResolver targetResolver;
    private final PortalLabelService datamartFrames;
    private final PortalUploadService uploadFrames;
    private final String portalUserNo;

    /** srcSn → 인가 판정 결과. 한 요청 안에서만 산다. */
    private final Map<Long, PortalWorkTargetResolver.FrameTarget> authorized = new HashMap<>();

    PortalAiFrameAccess(PortalWorkTargetResolver targetResolver, PortalLabelService datamartFrames,
                        PortalUploadService uploadFrames, String portalUserNo) {
        this.targetResolver = targetResolver;
        this.datamartFrames = datamartFrames;
        this.uploadFrames = uploadFrames;
        this.portalUserNo = portalUserNo;
    }

    @Override
    public LsDataSrc authorize(Long srcSn) {
        return judge(srcSn).frame();
    }

    @Override
    public Path resolveImage(LsDataSrc frame) {
        if (frame == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
        }
        PortalWorkTargetResolver.FrameTarget judged = judge(frame.getSrcSn());
        // 출처는 인가 판정이 정한다 — 넘어온 엔티티가 아니라 판정이 조회한 행으로 해석한다.
        return judged.target().isUpload()
                ? uploadFrames.resolveUploadFrameFile(judged.frame())
                : datamartFrames.resolveDatamartFrameFile(judged.frame());
    }

    @Override
    public String encodeImage(LsDataSrc frame) {
        Path imagePath = resolveImage(frame);
        try (InputStream in = FrameImageService.openNoFollow(imagePath).stream()) {
            return Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            // 내부 경로·원인 비노출(CWE-209) — 식별자 + 예외 클래스명만.
            log.warn("[PortalAi] frame image read failed srcSn={} reason={}",
                    frame.getSrcSn(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }
    }

    /**
     * 추론 입력과 <b>같은 파일</b>의 치수를 헤더만 읽어 잰다. 측정 불가면 빈 값(본체는 상한 clamp 만 생략).
     *
     * <p>내부 채널 해석기({@code FrameBoundsResolver})를 쓰지 않는다 — 내부 원본/비식별 base 전용이라 포털 업로드
     * 프레임은 상한이 조용히 생략되거나 다른 파일의 치수를 잰다.
     */
    @Override
    public Optional<int[]> resolveBounds(LsDataSrc frame) {
        if (frame == null) {
            return Optional.empty();
        }
        try {
            Path imagePath = resolveImage(frame);
            try (InputStream in = FrameImageService.openNoFollow(imagePath).stream();
                 ImageInputStream iis = ImageIO.createImageInputStream(in)) {
                if (iis == null) {
                    return Optional.empty();
                }
                Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
                if (!readers.hasNext()) {
                    return Optional.empty();
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(iis, true, true);
                    return Optional.of(new int[]{reader.getWidth(0), reader.getHeight(0)});
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[PortalAi] frame image size unavailable — upper-bound clamp skipped srcSn={} reason={}",
                    frame.getSrcSn(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private PortalWorkTargetResolver.FrameTarget judge(Long srcSn) {
        if (srcSn != null) {
            PortalWorkTargetResolver.FrameTarget cached = authorized.get(srcSn);
            if (cached != null) {
                return cached;
            }
        }
        PortalWorkTargetResolver.FrameTarget judged = targetResolver.resolveFrame(srcSn, portalUserNo);
        authorized.put(srcSn, judged);
        return judged;
    }
}
