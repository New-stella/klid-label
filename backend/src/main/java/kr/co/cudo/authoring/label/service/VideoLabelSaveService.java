package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveResponse;
import kr.co.cudo.authoring.version.config.StartVersionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * API-196 — 영상 라벨 <b>일괄 확정 저장</b>({@code PUT /v1/videos/{rawSn}/labels}) 의 진입점.
 *
 * <h3>이 빈은 트랜잭션 밖에서 할 일만 한다 (Critical)</h3>
 * 쓰기는 {@link VideoLabelSaveTxService#saveInTx} 가 소유하고, 이 빈은 그 <b>앞에서</b> 두 가지를 한다:
 * <ol>
 *   <li><b>자원 상한 선판정</b> — 프레임 수를 세어 상한 초과를 트랜잭션 진입 <b>이전에</b> 끊는다.</li>
 *   <li><b>인가·신고 게이트 선평가</b> — 아래 워밍이 피해 영상의 전 프레임 이미지를 여는 비용을 갖기
 *       때문이다(F-04). 권위 있는 판정은 쓰기 트랜잭션 안의 같은 게이트가 유지한다.</li>
 *   <li><b>좌표 경계 기준값 사전 확보(워밍)</b> — {@link FrameBoundsResolver} 는 프레임 이미지 파일을
 *       열어 디코딩한다. 트랜잭션 안에서 프레임마다 돌면 <b>프레임 행 락 + DB 커넥션을 쥔 채</b> NAS
 *       I/O 를 분 단위로 수행한다(캐시는 프로세스 로컬이라 2노드 콜드 스타트에서 전량 미스가 정상
 *       시나리오다). 이 저장소에는 정확히 같은 이유로 프레임 이미지 서빙의 파일 I/O 를 트랜잭션 밖으로
 *       뺀 전례가 있다({@code FrameImageLookupService} / {@code FrameImageServingHardeningTest}).</li>
 * </ol>
 *
 * <p><b>별도 빈으로 나눈 이유</b>: 같은 클래스의 {@code @Transactional} 메서드를 자기호출하면 프록시를
 * 우회해 트랜잭션이 걸리지 않는다(이 저장소의 실사고 유형). 워밍을 트랜잭션 밖에 두려면 경계가
 * <b>빈 사이</b>에 있어야 한다.
 *
 * @design API-196
 * @req R6
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoLabelSaveService {

    private final LabelAccessGuard accessGuard;
    private final LsDataSrcRepository srcRepository;
    private final FrameBoundsResolver frameBoundsResolver;
    /** 실제 쓰기(게이트·잠금·저장)의 소유자 — 여기서 재구현하지 않는다. */
    private final VideoLabelSaveTxService txService;
    /** 불러오기와 <b>같은</b> 프레임 수 상한 설정(두 곳에 상한을 두면 한쪽만 조정돼 어긋난다). */
    private final StartVersionProperties properties;

    /**
     * 영상 전체 라벨·폐기 상태를 확정한다.
     *
     * @design API-196
     */
    public VideoLabelSaveResponse save(Long rawSn, VideoLabelSaveRequest req, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (rawSn == null || req == null || req.frameVersions() == null || req.frameVersions().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "저장할 프레임이 없습니다.");
        }
        // CWE-770 — 상한 초과는 잘라내지 않고 거부한다. 조용히 자르면 일부 프레임만 확정되어
        //   서로 다른 회차가 섞인 혼합 영상이 된다. ★ 트랜잭션·잠금 진입 <b>이전</b>에 판정한다.
        if (req.frameVersions().size() > properties.maxFrames()) {
            log.warn("[Label] video save rejected — frame limit exceeded rawSn={} frames={} limit={}",
                    rawSn, req.frameVersions().size(), properties.maxFrames());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "프레임이 너무 많아 한 번에 저장할 수 없습니다(최대 " + properties.maxFrames() + "장).");
        }
        // ★ F-04 — 인가·신고 게이트를 <b>워밍보다 먼저</b> 평가한다.
        //   워밍은 피해 영상의 <b>전 프레임 이미지</b>를 열어 디코딩하므로, 인가 뒤에 두지 않으면
        //   인증된 사용자가 남의 영상 번호만 알아도 프레임 1건짜리 최소 요청으로 그 비용을 유발할 수
        //   있다(비용이 요청 크기가 아니라 <b>피해 영상의 프레임 수</b>에 비례하는 비대칭 증폭).
        //   신고 구간 영상의 파일도 이 시점에 열리면 안 된다.
        //   ⚠ 트랜잭션 밖 워밍은 그대로 유지된다 — 인가만 앞세우면 되고, 이 호출들은 각자 짧은 읽기다.
        //   쓰기 트랜잭션 안의 게이트는 <b>그대로 둔다</b>(권위 있는 판정은 그쪽이며, 여기 통과 후
        //   커밋까지 사이에 상태가 바뀔 수 있다).
        accessGuard.verifyRawAccess(rawSn, actor);
        accessGuard.requireNotUnderDeidentReport(rawSn);
        return txService.saveInTx(rawSn, req, actor, warmFrameBounds(rawSn));
    }

    /**
     * 좌표 경계 기준값을 <b>트랜잭션 밖에서</b> 전 프레임 일괄 확보한다.
     *
     * <p>측정 불가 프레임은 값이 {@code null} 인 항목으로 남고, 저장 코어는 그 프레임의 상한 검증만
     * 건너뛴다(기존 fail-open 정책 — 원천 이미지가 없는 파생영상·NAS 일시 장애가 저장을 전면 차단하지
     * 않게 한다). 여기서 예외를 던지지 않는 이유도 같다.
     *
     * <p>호출부가 <b>인가·신고 게이트를 통과시킨 뒤에만</b> 부른다(F-04). 기밀성 축(픽셀을 응답에 싣지
     * 않는다)만으로는 부족했다 — 가용성 축에서 미인가 요청이 피해 영상 전 프레임의 디코딩을 유발할 수
     * 있었기 때문이다.
     */
    private Map<Long, int[]> warmFrameBounds(Long rawSn) {
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        Map<Long, int[]> bounds = new HashMap<>(frames.size());
        for (LsDataSrc frame : frames) {
            bounds.put(frame.getSrcSn(), frameBoundsResolver.resolve(frame).orElse(null));
        }
        return bounds;
    }
}
