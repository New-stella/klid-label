package kr.co.cudo.authoring.label.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.observability.metrics.LabelBoundsMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * 프레임 이미지 실측 해상도(폭·높이) 해석기 — C-ISSUE-22 좌표 상한 검증의 기준값 공급원.
 *
 * <h3>왜 파일을 읽는가 (설계 선택 근거)</h3>
 * 프레임 해상도는 <b>DB 에 존재하지 않는다</b>: {@code LS_DATA_SRC}·{@code LS_DATA_RAW} 어느 쪽에도
 * width/height 컬럼이 없다. 해상도가 필요한 기존 기능들도 모두 <b>이미지 파일을 열어</b> 치수를 읽는다
 * ({@code ImageResizer#readDimensions} 포트, {@code Sam2SegmentService} 는 요청 시점에 직접 읽어 외부
 * 응답 좌표의 상한을 검증한다). 컬럼 신설(안 b)은 ①표준용어 신규 컬럼 2개 ②기존 전 프레임 백필
 * ③백필 불가 행(원천 이미지 부재 — 파생영상은 원본 프레임 경로가 null)의 정책까지 필요해 비용·위험이
 * 크고, 그렇게 채운 값도 결국 파일에서 읽어온 값이라 <b>진실원이 파일</b>이라는 사실은 바뀌지 않는다.
 * 따라서 <b>안 (a) 저장 시점 파일 읽기 + 캐시</b>를 택한다. 프레임 이미지는 추출 후 불변이라 캐시가 안전하다.
 *
 * <h3>캐시 한계 (명시)</h3>
 * 프로세스 로컬 Caffeine 캐시다. 배포는 <b>2노드 Active-Active</b> 이므로 노드별로 캐시가 따로 채워진다
 * (정합성 문제는 없다 — 값이 불변이라 노드 간 값이 갈릴 수 없고, 최악은 노드마다 1회씩 파일을 읽는 것뿐).
 * 재비식별로 프레임 이미지가 <b>교체</b>되는 경우에도 해상도는 동일 해상도로 재추출되므로 값이 바뀌지 않는다
 * (해상도가 바뀌는 파생은 새 RAW_SN·새 SRC_SN 으로 생성된다 — 캐시 키가 달라 오염되지 않는다).
 * 그럼에도 무한 보관하지 않도록 {@code expireAfterWrite} 상한을 둔다.
 *
 * <h3>측정 실패 시 정책 (fail-open + 가시화)</h3>
 * 파일 부재·권한·손상·미지원 포맷으로 치수를 못 읽으면 {@link Optional#empty()} 를 반환한다. 호출부는
 * <b>상한 검증만 건너뛰고</b> 저장을 계속한다 — 여기서 저장을 막으면 원천 이미지가 없는 정상 작업(파생영상,
 * NAS 일시 장애 등)이 전면 차단되며, 이는 리포에서 반복된 "게이트가 정상 플로우를 끊는" 사고 유형이다.
 * 대신 <b>"검증 불가"가 "검증 통과"로 조용히 둔갑하지 않도록</b> WARN 로그로 드러낸다(경로 원문은
 * 남기지 않는다 — CWE-209/359). 실패는 캐시하지 않아 파일이 나중에 복구되면 즉시 검증이 되살아난다.
 *
 * <h3>DEV_FIX(H4) — skip 을 메트릭으로 노출</h3>
 * WARN 로그만으로는 "상한 검증이 실효 중인가"를 운영에서 알 수 없었다(실측: 프레임 경로 규약 불일치로
 * 전 프레임 skip 인데 지표상 무신호 — "검증 통과"와 구분 불가). {@link LabelBoundsMetrics} 로 skip 을
 * 사유별 카운터({@code label.bounds.skipped{reason}})로 올리고 실측 성공({@code label.bounds.resolved})도
 * 함께 계상해 대시보드/알람에서 skip 비율을 볼 수 있게 한다. 카운터는 <b>캐시 미스(실측 시도)</b> 시에만
 * 증가한다.
 */
@Slf4j
@Component
public class FrameBoundsResolver {

    /** 캐시 최대 엔트리 수 (프레임 수 상한 가정 — 초과 시 LRU 축출). */
    private static final int MAX_CACHED_FRAMES = 20_000;

    /** 캐시 보관 상한 — 값이 불변이라 정합 목적이 아니라 메모리 상한 목적. */
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final FrameImageEncoder frameImageEncoder;
    private final LabelBoundsMetrics metrics;
    private final Cache<Long, int[]> cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_FRAMES)
            .expireAfterWrite(CACHE_TTL)
            .build();

    public FrameBoundsResolver(FrameImageEncoder frameImageEncoder, LabelBoundsMetrics metrics) {
        this.frameImageEncoder = frameImageEncoder;
        this.metrics = metrics;
    }

    /**
     * 프레임 이미지의 실측 [width, height] 를 반환한다. 측정 불가면 {@link Optional#empty()}.
     *
     * @param frame 대상 프레임(비식별 우선 폴백으로 이미지 경로 해석)
     */
    public Optional<int[]> resolve(LsDataSrc frame) {
        if (frame == null || frame.getSrcSn() == null) {
            return Optional.empty();
        }
        int[] cached = cache.getIfPresent(frame.getSrcSn());
        if (cached != null) {
            return Optional.of(cached);
        }
        int[] measured = measure(frame);
        if (measured == null) {
            // 실패는 캐시하지 않는다 — 원천 이미지가 나중에 복구되면 다음 저장부터 검증이 되살아난다.
            //   (skip 사유 카운터는 measure() 안에서 이미 계상됐다.)
            return Optional.empty();
        }
        metrics.incrementResolved();
        cache.put(frame.getSrcSn(), measured);
        return Optional.of(measured);
    }

    /** 이미지 치수 측정. 어떤 실패든 null + WARN + skip 카운터(경로 원문 미출력 — CWE-209/359). */
    private int[] measure(LsDataSrc frame) {
        Path imagePath;
        try {
            // 경로 해석 실패(경로 부재 / 기준 base 밖 — 프레임 경로 규약 불일치 포함)와
            // 파일 읽기 실패를 구분해 계상한다. 전자는 데이터/설정 정합 문제 신호다.
            imagePath = frameImageEncoder.resolveFrameImage(frame);
        } catch (Exception e) {
            metrics.incrementSkipped(LabelBoundsMetrics.REASON_UNRESOLVED);
            log.warn("[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn={} cause={}",
                    frame.getSrcSn(), e.getClass().getSimpleName());
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(imagePath.toFile());
            if (img == null) {
                metrics.incrementSkipped(LabelBoundsMetrics.REASON_UNREADABLE);
                log.warn("[Label] frame image unreadable — coordinate upper-bound check skipped srcSn={} reason=UNSUPPORTED_OR_CORRUPT",
                        frame.getSrcSn());
                return null;
            }
            return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception e) {
            metrics.incrementSkipped(LabelBoundsMetrics.REASON_UNREADABLE);
            log.warn("[Label] frame image size unavailable — coordinate upper-bound check skipped srcSn={} cause={}",
                    frame.getSrcSn(), e.getClass().getSimpleName());
            return null;
        }
    }
}
