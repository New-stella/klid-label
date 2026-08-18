package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiCallCancelledException;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.AiWaitBudgetPolicy;
import kr.co.cudo.authoring.common.client.CancellableAiCall;
import kr.co.cudo.authoring.common.client.dto.Sam2TrackResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.PolygonSimplifier;
import kr.co.cudo.authoring.label.dto.AutolabelShape;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.label.dto.Sam2TrackOutcome;
import kr.co.cudo.authoring.label.dto.Sam2TrackRequest;
import kr.co.cudo.authoring.label.dto.Sam2TrackResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 6 / R12 — SAM2 트랙 서비스 (<b>DB 미저장 — 좌표만 반환</b>).
 *
 * <p><b>미저장 전환(사용자 확정)</b>: 이전에는 propagation 결과를 쓰기 트랜잭션 내 {@code labelRepository.save}
 * 로 즉시 저장했으나, AI 탐지({@link AutolabelOnlineService})
 * 와 동일하게 <b>좌표(draft)만 반환</b>하도록 통일한다. 클라이언트가 작업본에 병합 후 PUT /labels 로 확정한다.
 * 트랙 병합({@code TrackMergeService})·보간({@code TrackInterpolator})은 이미 저장된 라벨을 대상으로 하므로
 * 본 미저장 전환과 무관하다(회귀 없음).
 *
 * <p><b>비트랜잭셔널(F-1 커넥션풀 고갈 방지)</b>: DB write 가 없으므로 {@code @Transactional} 을 제거했다.
 * AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>IDOR(CWE-639): 시작 프레임 + 모든 후속 프레임에 대해 {@link LabelAccessGuard#verifyAccess} 검증(AI 호출 전).</li>
 *   <li>PII 유출(CWE-359): 프레임 이미지 인코딩이 {@link FrameImageEncoder#encodeFrame} 단일 진입점을 거치며,
 *       이 영상이 비식별 누락 신고 구간이면 412 로 끊긴다. 추적은 N 프레임을 연속 전송하므로
 *       <b>프레임마다</b> 판정되어, 추적 도중 신고가 들어와도 그 이후 프레임 픽셀은 나가지 않는다.</li>
 *   <li>좌표 검증(CWE-20): 요청 prevPolygon 및 ai-server 응답 polygon 둘 다 음수/형식 차단(400).
 *       응답 폴리곤은 추가로 <b>최소 정점 수</b>({@link Sam2CoordinateValidator#MIN_POLYGON_POINTS})를
 *       강제하며 위반은 <b>502</b>(외부 시스템이 잘못 준 것) — 요청 축의 400 과 섞지 않는다.</li>
 *   <li>정보노출(CWE-209): 예외 원문·내부 경로 비노출(LogSanitizer + 일반화 메시지).</li>
 *   <li><b>데이터 진정성(CWE-345, C-ISSUE-81)</b>: ai-server mock 응답 프레임은 결과에서 제외한다.
 *       mock track 은 시드 폴리곤 복사본을 {@code score=0.9} 로 돌려주므로 걸러내지 않으면 "N 프레임 추적"이
 *       "시드 N개 복제"로 둔갑해 학습데이터가 오염된다. 제외 사실은 {@link Sam2TrackOutcome} 으로 컨트롤러에
 *       전달되어 안내 메시지가 된다(SAM2 세그·YOLO 오토라벨과 동일 규약).</li>
 * </ul>
 *
 * <p>형태(R12): {@code shape=POLYGON}(기본) 이면 폴리곤을 그대로, {@code shape=BBOX} 면 폴리곤의 외접 bbox
 * ([[minX,minY],[maxX,maxY]]) 를 산출해 반환한다. 퇴화 폴리곤(폭/높이 &lt; 1px)은 해당 프레임만 스킵(전체 추적 미중단).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Sam2TrackService {

    private final AiServerClient aiServerClient;
    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;
    private final SystemConfigService systemConfigService;
    private final FrameImageEncoder frameImageEncoder;

    /** POLYGON_SIMPLIFY_TOLERANCE 조회 실패 시 폴백 epsilon(px). */
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 1.0;

    /** 외접 bbox 퇴화 판정 최소 폭/높이(px) — 미만이면 해당 프레임 스킵. */
    private static final double MIN_BBOX_EXTENT = 1.0;

    /**
     * 요청 하나가 프레임 루프에 쓰는 wall-clock 예산 — 오토라벨 폴리곤 경로와 <b>같은 장치</b>다
     * ({@code AutolabelOnlineService.polygonTotalBudget}).
     *
     * <p>운영 기본값은 {@link AiWaitBudgetPolicy#TRACK_BATCH_BUDGET} 이며, 예산 소진 분기를 단위
     * 테스트에서 발화시킬 수 있도록 <b>package-private 필드</b>로 노출한다(프로덕션 경로 불변).
     */
    Duration trackTotalBudget = AiWaitBudgetPolicy.TRACK_BATCH_BUDGET;

    public Sam2TrackOutcome track(Sam2TrackRequest req, TokenClaims actor) {
        // IDOR 차단: 시작 프레임에 대한 접근 권한 검증 (LabelService 와 동일 규칙).
        accessGuard.verifyAccess(req.srcSn(), actor);
        // 입력 좌표 검증 (CWE-20).
        validatePolygon(req.prevPolygon(), "prevPolygon");

        AutolabelShape shape = req.shapeOrDefault();

        // 시작 프레임 존재 검증.
        LsDataSrc startSrc = srcRepository.findById(req.srcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "시작 프레임을 찾을 수 없습니다."));

        // FEAT-007: 경계 세밀함 — SAM2 응답 폴리곤을 sysconfig epsilon 으로 단순화(Douglas-Peucker).
        double simplifyTolerance = readSimplifyTolerance();

        List<Sam2TrackResponseDto.TrackedItem> tracked = new ArrayList<>();
        List<List<Double>> currentPolygon = req.prevPolygon();
        // C-ISSUE-81 — mock 응답이 1건이라도 있었는지(안내 메시지 세팅용).
        boolean anyMock = false;

        // 시작 프레임 이미지를 prev 로 사용.
        String prevImageB64 = frameImageEncoder.encodeFrame(startSrc);

        // ── 요청 단위 시간 예산 ──────────────────────────────────────────────────
        // 프레임마다 «호출 1회 최악» 을 그대로 허용하면 요청 하나의 대기가 프레임 수에 비례해 늘어,
        // 절대 상한 안에 한 프레임밖에 못 넣는다. 그러면 화면이 프레임 수만큼 요청을 쪼개고 조각마다
        // 트래커가 리셋돼 추적 품질까지 떨어진다. 그래서 루프 전체에 단일 데드라인을 두고, 예산이
        // 다하면 남은 프레임을 처리하지 않되 <b>그때까지의 결과와 이어 보낼 지점</b>을 돌려준다.
        final List<Long> nextSrcSns = req.nextSrcSns();
        final long deadlineNanos = System.nanoTime() + trackTotalBudget.toNanos();
        boolean truncated = false;
        // 앞에서부터 몇 건을 처리했는가 — 결과 목록 크기와 다르다(mock·퇴화 프레임은 처리했지만 빠진다).
        int processed = 0;
        // 이어 보낼 요청의 srcSn — 지금 prevImageB64 가 어느 프레임의 것인가.
        Long prevSrcSn = req.srcSn();

        for (int i = 0; i < nextSrcSns.size(); i++) {
            Long nextSrcSn = nextSrcSns.get(i);
            // 데드라인은 «반복 진입 직전» 에 본다 — 폴리곤 경로와 같은 모양.
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                truncated = true;
                log.warn("[Sam2Track] budget exhausted startSrc={} processed={}/{}",
                        startSrc.getSrcSn(), processed, nextSrcSns.size());
                break;
            }
            // IDOR 차단: 후속 프레임 각각에 대해서도 권한 검증.
            accessGuard.verifyAccess(nextSrcSn, actor);

            LsDataSrc nextSrc = srcRepository.findById(nextSrcSn)
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "후속 프레임을 찾을 수 없습니다: " + nextSrcSn));

            String nextImageB64 = frameImageEncoder.encodeFrame(nextSrc);

            kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest aiReq =
                    new kr.co.cudo.authoring.common.client.dto.Sam2TrackRequest(
                            req.trackId(),
                            prevImageB64,
                            nextImageB64,
                            currentPolygon
                    );

            Sam2TrackResponse aiRes;
            try {
                // 잔여 예산을 대기 상한으로 준다 — 상한을 따로 두지 않는 것은 «한 프레임은 재시도
                // 체인을 끝까지 쓸 수 있어야 한다» 는 종전 동작을 그대로 두기 위해서다. 예산이
                // 그보다 크면 종전과 동일하게 동작하고, 작아지면 그만큼만 기다린다.
                aiRes = CancellableAiCall.block(aiServerClient.track(aiReq),
                        Duration.ofNanos(remainingNanos));
            } catch (AiCallCancelledException e) {
                // 사용자 취소 — 남은 프레임을 더 보내지 않고 그대로 올린다(502 로 바꾸지 않는다).
                // ★ 예산 소진 판정보다 <b>먼저</b> 온다. 취소를 부분 결과로 흡수하면 사용자가 누른
                //   취소가 «시간이 모자랐다» 로 둔갑해, 취소했는지 아닌지를 화면이 구분하지 못한다.
                throw e;
            } catch (Exception e) {
                // 예산이 다한 순간의 실패는 «시간이 모자랐다» 로 다룬다 — 502 로 올리면 이미 성공한
                // 앞 프레임의 결과까지 통째로 버려진다. 진행이 0 이면 부분 결과가 아니므로(이어 보내도
                // 같은 지점을 다시 시도한다) 종전대로 실패를 올린다.
                if (processed > 0 && System.nanoTime() >= deadlineNanos) {
                    truncated = true;
                    log.warn("[Sam2Track] budget exhausted while waiting startSrc={} processed={}/{} err={}",
                            startSrc.getSrcSn(), processed, nextSrcSns.size(),
                            LogSanitizer.sanitize(e.getMessage()));
                    break;
                }
                // CWE-209: 예외 원문·내부 경로를 클라이언트에 노출하지 않음. 진단 정보는 서버 로그로만.
                log.error("[Sam2Track] ai-server 호출 실패 nextSrcSn={} err={}",
                        nextSrcSn, LogSanitizer.sanitize(e.getMessage()));
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "SAM2 track 호출에 실패했습니다.", e);
            }
            if (aiRes == null || aiRes.polygon() == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 track 응답이 비어있습니다.");
            }
            // 외부 시스템 응답도 신뢰하지 않음 — 최소 정점 수(502) + 좌표 형식(400) 검증.
            validateResponsePolygon(aiRes.polygon(), "ai-server polygon");

            // mock 안전장치(C-ISSUE-81, CWE-345) — SAM2 세그/오토라벨과 동일 규약. ai-server 가 mock
            // 응답(모델 미로드·마스크 미검출)을 내면 그 좌표는 시드 폴리곤 복사본에 불과하므로 이 프레임을
            // 결과에서 제외한다(score 가 0.9 라 FE 저신뢰 분기로도 걸러지지 않는다).
            // 전파(currentPolygon)는 이어가되 — mock 폴리곤은 입력 시드와 동일하므로 새로 지어낸 좌표가
            // 유입되지 않는다 — 다음 프레임에서 실모델이 회복할 수 있게 한다.
            // 판정은 긍정 증명 기반(untrusted) — mock 메타 생략 응답도 신뢰하지 않는다(AiMockMeta).
            // 전파 상태를 한 곳에서 옮긴다 — 이 프레임은 «처리됐다». mock·퇴화로 결과 목록에서
            // 빠지더라도 전파는 이어지므로, 이어 보낼 지점도 여기 기준이어야 한다.
            // (전파 연속성은 단순화 전 원본 응답 폴리곤으로 유지)
            currentPolygon = aiRes.polygon();
            prevImageB64 = nextImageB64;
            prevSrcSn = nextSrcSn;
            processed = i + 1;

            if (aiRes.untrusted()) {
                anyMock = true;
                log.warn("[Sam2Track] mock response — exclude frame nextSrcSn={} source={} reason={}",
                        nextSrcSn, LogSanitizer.sanitize(aiRes.source()),
                        LogSanitizer.sanitize(aiRes.mockReason()));
                continue;
            }

            // FEAT-007: 경계 세밀함 적용 — epsilon 으로 폴리곤 점 감소(형태 보존).
            List<List<Double>> simplifiedPolygon = simplify(aiRes.polygon(), simplifyTolerance);

            if (shape == AutolabelShape.BBOX) {
                // R12: 외접 bbox 산출 — 퇴화(폭/높이 < 1px)면 해당 프레임만 스킵(전체 추적 미중단).
                List<List<Double>> bbox = toCircumscribedBbox(simplifiedPolygon);
                if (bbox == null) {
                    log.warn("[Sam2Track] degenerate bbox skipped nextSrcSn={}", nextSrcSn);
                    continue;
                }
                tracked.add(new Sam2TrackResponseDto.TrackedItem(
                        nextSrcSn, aiRes.trackId(), req.label(), bbox, aiRes.score(),
                        AutolabelShape.BBOX.name()));
            } else {
                tracked.add(new Sam2TrackResponseDto.TrackedItem(
                        nextSrcSn, aiRes.trackId(), req.label(), simplifiedPolygon, aiRes.score(),
                        AutolabelShape.POLYGON.name()));
            }
        }
        // 예산이 다했으면 «이어 보낼 요청» 을 그대로 만들어 준다 — 화면이 결과 목록에서 역산할 수
        // 없는 값들이다(전파 폴리곤은 mock 프레임에선 결과에 없고, BBOX 형태면 2점이라 요청 규격 위반).
        // 시드는 요청 규격 상한 안으로 줄여 발행한다 — 서버가 자기가 받지 못할 값을 주면 안 된다.
        Sam2TrackResponseDto.Resume resume = truncated
                ? new Sam2TrackResponseDto.Resume(prevSrcSn,
                        resumeSeed(currentPolygon, simplifyTolerance),
                        List.copyOf(nextSrcSns.subList(processed, nextSrcSns.size())))
                : null;

        // CWE-117 — trackId 는 클라이언트 원문(CRLF 삽입 가능)이므로 로그 출력 전 정제.
        log.info("[Sam2Track] propagated trackId={} startSrc={} shape={} count={} processed={}/{} "
                        + "truncated={} mock={} (no persist)",
                LogSanitizer.sanitize(req.trackId()), startSrc.getSrcSn(), shape, tracked.size(),
                processed, nextSrcSns.size(), truncated, anyMock);
        return Sam2TrackOutcome.of(new Sam2TrackResponseDto(tracked, truncated, resume), anyMock);
    }

    /**
     * 이어 보내기 <b>시드</b> 폴리곤 — 요청 규격({@link Sam2TrackRequest#MAX_POLYGON_POINTS})을
     * 반드시 만족시켜 발행한다.
     *
     * <h3>왜 필요한가</h3>
     * <p>전파 중인 폴리곤은 <b>단순화 전 원본 응답</b>이다(프레임을 건널 때마다 단순화 오차가 누적되지
     * 않게 한 의도된 선택). 그런데 그 값이 그대로 다음 요청의 {@code prevPolygon} 이 되는데, 추론
     * 서버는 윤곽점 수를 제한하지 않고 응답 검증도 <b>최소</b> 정점 수만 본다. 즉 경로 어디에도
     * 상한을 보장하는 지점이 없어, 마스크 윤곽이 촘촘한 프레임에서는 서버가 <b>자기 요청 검증이
     * 400 으로 거부할 값</b>을 발행한다. 목서버는 입력을 그대로 돌려주므로 <b>실모델에서만</b> 드러난다.
     *
     * <h3>왜 «넘을 때만» 손대는가</h3>
     * <p>항상 단순화해 넘기면 요청 경계마다 단순화가 한 번씩 끼어들어, 원본을 전파하기로 한 이유
     * (연속성)가 요청을 쪼갤수록 옅어진다. 반대로 상한을 넘는 경우에만 줄이면 <b>평시 동작은 전혀
     * 바뀌지 않고</b> 규격 위반만 사라진다. 대가는 그 드문 경우에 시드 정밀도가 떨어지는 것인데,
     * 이는 «다음 요청이 통째로 400 이 되어 남은 프레임을 전부 잃는 것» 보다 명백히 작다.
     *
     * <h3>두 단계인 이유</h3>
     * <ol>
     *   <li><b>단순화</b>(Douglas-Peucker, 설정 epsilon) — 형태를 보존하며 줄이는 이 저장소의 표준
     *       수단이라 먼저 쓴다. 다만 epsilon 이 고정이라 <b>결과 크기를 보장하지 못한다</b>.</li>
     *   <li><b>균등 솎기</b> — 그래도 넘으면 상한에 맞춰 등간격으로 고른다. 형태 보존은 1단계보다
     *       못하지만 <b>상한을 확실히 보장</b>한다. 보장이 없으면 이 결함을 «대개 안 난다» 로 바꾼
     *       것에 지나지 않는다.</li>
     * </ol>
     */
    private List<List<Double>> resumeSeed(List<List<Double>> polygon, double simplifyTolerance) {
        if (polygon.size() <= Sam2TrackRequest.MAX_POLYGON_POINTS) {
            return polygon;
        }
        List<List<Double>> reduced = simplify(polygon, simplifyTolerance);
        if (reduced.size() <= Sam2TrackRequest.MAX_POLYGON_POINTS) {
            log.warn("[Sam2Track] resume seed simplified to fit request cap {} -> {}",
                    polygon.size(), reduced.size());
            return reduced;
        }
        List<List<Double>> capped = decimate(reduced, Sam2TrackRequest.MAX_POLYGON_POINTS);
        log.warn("[Sam2Track] resume seed decimated to fit request cap {} -> {} -> {}",
                polygon.size(), reduced.size(), capped.size());
        return capped;
    }

    /** 순서를 지키며 등간격으로 {@code max} 점만 고른다(폐곡선 형태 유지). */
    private static List<List<Double>> decimate(List<List<Double>> polygon, int max) {
        List<List<Double>> out = new ArrayList<>(max);
        // 인덱스를 실수 간격으로 훑어 앞쪽에 몰리지 않게 한다(정수 step 은 마지막 구간이 잘린다).
        double step = (double) polygon.size() / max;
        for (int i = 0; i < max; i++) {
            out.add(polygon.get((int) (i * step)));
        }
        return out;
    }

    /** epsilon 으로 폴리곤 단순화. 3점 미만으로 줄면 원본 유지(형태 보존). */
    private List<List<Double>> simplify(List<List<Double>> polygon, double tolerance) {
        List<Point> rawPoints = new ArrayList<>(polygon.size());
        for (List<Double> p : polygon) {
            rawPoints.add(new Point(p.get(0), p.get(1)));
        }
        List<Point> simplified = PolygonSimplifier.simplify(rawPoints, tolerance);
        if (simplified.size() < 3) {
            return polygon;
        }
        List<List<Double>> out = new ArrayList<>(simplified.size());
        for (Point p : simplified) {
            out.add(List.of(p.x(), p.y()));
        }
        return out;
    }

    /**
     * 폴리곤 외접 bbox([[minX,minY],[maxX,maxY]]) 산출(R12).
     * 폭/높이가 {@link #MIN_BBOX_EXTENT} 미만인 퇴화 폴리곤은 {@code null} (호출자가 프레임 스킵).
     */
    private List<List<Double>> toCircumscribedBbox(List<List<Double>> polygon) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (List<Double> p : polygon) {
            double x = p.get(0), y = p.get(1);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if ((maxX - minX) < MIN_BBOX_EXTENT || (maxY - minY) < MIN_BBOX_EXTENT) {
            return null;
        }
        return List.of(List.of(minX, minY), List.of(maxX, maxY));
    }

    /**
     * <b>요청</b> 좌표 검증 — 각 원소가 [x, y] 두 개이고 모두 유한한 0 이상인지. CWE-20 → 400.
     *
     * <p>규칙 본체는 {@link Sam2CoordinateValidator} 로 추출했다 — {@link Sam2SegmentService} 와
     * 동일 규칙을 공유해야 두 경로의 검증이 갈라지지 않는다(과거 segment 경로에 이 검증이 없어
     * 클라이언트 입력 오류가 502 로 승격됐다).
     */
    private void validatePolygon(List<List<Double>> polygon, String fieldName) {
        Sam2CoordinateValidator.validatePolygon(polygon, fieldName);
    }

    /**
     * <b>응답</b> 폴리곤 검증 (CWE-20) — 최소 정점 수(폐곡선) 위반은 <b>502</b>, 좌표 형식 위반은 400.
     *
     * <p>정점 수 위반을 502 로 내는 이유: 클라이언트가 잘못 보낸 것이 아니라 <b>외부 시스템이 잘못
     * 준 것</b>이라 400 은 의미가 틀리다({@link Sam2SegmentService} 의 응답 검증과 동일 규약).
     * 요청 축({@code prevPolygon})은 기존대로 400 이며 두 축을 섞지 않는다.
     *
     * <p>이 검증이 없으면 1~2 점 응답이 {@link #simplify} 를 그대로 통과해(단순화 결과가 3 점
     * 미만이면 원본 유지) 퇴화 폴리곤이 POLYGON 응답에 실린다.
     */
    private void validateResponsePolygon(List<List<Double>> polygon, String fieldName) {
        Sam2CoordinateValidator.validateResponseMinPoints(polygon, fieldName);
        Sam2CoordinateValidator.validatePolygon(polygon, fieldName);
    }

    /**
     * FEAT-007 경계 세밀함 epsilon 조회. 설정 누락/오류 시 기본값으로 폴백(fail-safe).
     */
    private double readSimplifyTolerance() {
        try {
            Double v = systemConfigService.getDouble(ConfigKeys.POLYGON_SIMPLIFY_TOLERANCE);
            return v != null ? v : DEFAULT_SIMPLIFY_TOLERANCE;
        } catch (Exception e) {
            log.warn("[Sam2Track] POLYGON_SIMPLIFY_TOLERANCE 조회 실패 — 기본값 {} 사용", DEFAULT_SIMPLIFY_TOLERANCE);
            return DEFAULT_SIMPLIFY_TOLERANCE;
        }
    }
}
