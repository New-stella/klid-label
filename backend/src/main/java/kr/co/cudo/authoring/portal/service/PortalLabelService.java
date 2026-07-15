package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.KeypointPoint;
import kr.co.cudo.authoring.common.util.KeypointSerializer;
import kr.co.cudo.authoring.portal.dto.DatamartLabelResponse;
import kr.co.cudo.authoring.portal.dto.DatamartVideoResponse;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.FrameImageService;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 11 — 포털 간편 라벨링 서비스 (데이터마트 라벨 Load + 사용자 작업 라벨 저장).
 *
 * 정책 (ADR-013):
 *  - 포털은 데이터마트 영상 선택 전용 — 업로드/오토라벨링 미제공.
 *  - 검수 / 버전관리(DB 스냅샷) / VLM 검증 미제공 → LabelService 의 풀 워크플로우 미사용.
 *  - 본 서비스는 데이터마트 라벨 Load + 본인 작업 라벨 별도 적재만 수행.
 *
 * 보안:
 *  - IDOR (CWE-639): 사용자 작업 라벨은 portalUserNo = token sub 로만 조회 → 타 사용자 데이터 거부.
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
@RequiredArgsConstructor
public class PortalLabelService {

    private final LsDataLblRepository lblRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsPortalUserLabelRepository userLabelRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final VideoRepository videoRepository;

    /** 라벨 좌표 JSON 파싱용. 생성자 주입 (@RequiredArgsConstructor). */
    private final ObjectMapper objectMapper;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * R17 이슈1 — 비식별 프레임 이미지 base 경로.
     * deidFilePath 는 FFmpeg 추출 단계에서 deidentified-path 기준 절대경로로 저장된다
     * ({@code FfmpegFrameExtractor.attachDeidPath(deidFrame.toString())}).
     * 따라서 Path Traversal 가드(resolveSafe)의 baseDir 도 raw-path 가 아닌 deidentified-path 여야 한다.
     * (구버전은 raw-path 를 baseDir 로 잡아 startsWith 검증 실패 → 전 프레임 403 회귀)
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /** V2.0 — 데이터마트 라벨 Load. rawSn 에 해당하는 원본 라벨 목록 반환 (페이징). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        int clampedSize = Math.min(Math.max(size, 1), 100);
        int clampedPage = Math.max(page, 0);
        List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);
        int fromIndex = clampedPage * clampedSize;
        if (fromIndex >= all.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + clampedSize, all.size());
        return all.subList(fromIndex, toIndex).stream()
                .map(DatamartLabelResponse::from)
                .toList();
    }

    /**
     * Phase B — 포털 홈 데이터마트 영상 목록 (PORTAL_USER 전용).
     *
     * <p>데이터마트 노출(검수 완료 = LS_RAW_DATA_STATUS.DATA_STTS_CD 'APPROVED') 영상만 페이징 조회한다.
     * 이 게이트는 {@link #isExposedToDatamart(Long)} / 프레임 이미지·라벨 Load 가드와 동일 조건이며,
     * BE 쿼리({@code findAllWithReviewStatus(null, APPROVED, ...)}) 단에서 INNER JOIN 으로 강제되어
     * 미승인 영상은 애초에 결과에 포함되지 않는다(HIGH 방어 — 게이트 누락 차단).
     *
     * <p>프레임 0건 영상은 라벨링 진입(/portal/label/{firstSrcSn}) 대상 프레임이 없어 진입 불가하므로
     * 목록에서 제외한다(MED 방어). firstSrcSn / frameCount 는 N+1 회피 batch lookup 으로 enrich.
     *
     * <p>N+1 회피: 페이지 rawSn 집합에 대해 firstSrcSn / frameCount / lastUpdatedAt 을 각 1회 IN 쿼리로 조회.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<DatamartVideoResponse> listDatamartVideos(TokenClaims actor, Pageable pageable) {
        requireActor(actor);

        Page<LsDataRaw> page =
                videoRepository.findAllWithReviewStatus(null, LsRawDataStatus.STTS_APPROVED, pageable);
        List<LsDataRaw> rows = page.getContent();
        if (rows.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        }

        List<Long> rawSns = rows.stream().map(LsDataRaw::getRawSn).toList();

        Map<Long, Long> firstSrcSnByVideo = lookupFirstSrcSnByVideo(rawSns);
        Map<Long, Long> frameCountByVideo = lookupFrameCountByVideo(rawSns);
        Map<Long, LocalDateTime> lastUpdatedAtByVideo = lookupLastUpdatedAtByVideo(rawSns);

        // 프레임 0건(=firstSrcSn 부재) 영상은 진입 불가하므로 제외 (MED 방어).
        List<DatamartVideoResponse> content = rows.stream()
                .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)
                .map(r -> new DatamartVideoResponse(
                        r.getRawSn(),
                        r.getVmsClipId(),
                        r.getEvntTypeCd(),
                        frameCountByVideo.getOrDefault(r.getRawSn(), 0L),
                        firstSrcSnByVideo.get(r.getRawSn()),
                        lastUpdatedAtByVideo.get(r.getRawSn())))
                .toList();

        // 제외로 인해 페이지 size 보다 적어질 수 있으나 totalElements 는 원본(게이트 후) 기준 유지.
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    private Map<Long, Long> lookupFirstSrcSnByVideo(List<Long> rawSns) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : srcRepository.findFirstSrcSnGroupedByRawSn(rawSns)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    private Map<Long, Long> lookupFrameCountByVideo(List<Long> rawSns) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : srcRepository.countByRawSnsGrouped(rawSns)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    /**
     * MED-3: LS_RAW_DATA_STATUS.UPD_DT(마지막 상태 변경 일시)를 lastUpdatedAt 으로 노출한다.
     * 정확한 승인 시각 컬럼이 없어 'approvedAt' 으로 명명하면 재승인 전 상태 전이 시 오해를 일으키므로
     * 의미에 맞춘 lastUpdatedAt 으로 매핑한다.
     */
    private Map<Long, LocalDateTime> lookupLastUpdatedAtByVideo(List<Long> rawSns) {
        Map<Long, LocalDateTime> map = new HashMap<>();
        for (LsRawDataStatus s : rawDataStatusRepository.findAllById(rawSns)) {
            if (s == null || s.getRawDataId() == null) continue;
            map.put(s.getRawDataId(), s.getUpdDt());
        }
        return map;
    }

    /** V2.0 — 사용자 라벨 저장. 원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재. */
    @Transactional("controlTransactionManager")
    public PortalUserLabelResponse saveUserLabel(PortalUserLabelRequest req, TokenClaims actor) {
        requireActor(actor);
        // Phase 9 이슈4 — 저장 경로도 로드/이미지 서빙과 동일 인가(APPROVED 게이트) 적용.
        // 비APPROVED sourceRawSn 은 애초에 포털에 노출되지 않으므로 저장도 거부(로드는 막고 저장만 허용하던
        // 인가 비일관성 제거 — IDOR/무결성 방어).
        if (!isExposedToDatamart(req.sourceRawSn())) {
            log.warn("[Portal] user label save denied — video not approved rawSn={}", req.sourceRawSn());
            throw new CustomException(ErrorCode.FORBIDDEN, "데이터마트에 노출되지 않은 영상입니다.");
        }
        // Phase 9 — 포털 키포인트(SKELETON) 저장 허용. 삼중값(17점 [x,y,v]) 전용 검증 경로로 type-route
        // (기존 2-튜플 경로와 격리). 형식 위반/개수 불일치는 조용히 통과시키지 않고 명시적 400(#6 fail-closed).
        if (LsDataLbl.TYPE_SKELETON.equals(req.lblTypeCd())) {
            validateSkeletonPoints(req.points());
        } else {
            // R17 이슈2 — @NotBlank 가 NULL/공백을 막더라도 빈 좌표 JSON('[]','[[]]')은 통과한다.
            // 좌표가 0개로 파싱되는 라벨은 거부 (빈 라벨 row 생성 차단 — fail-closed).
            if (parsePoints(req.points()).isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "points 좌표가 비어있습니다.");
            }
        }
        LsPortalUserLabel saved = userLabelRepository.save(
                LsPortalUserLabel.create(actor.sub(), req.sourceRawSn(), req.sourceSrcSn(),
                        req.lblTypeCd(), req.label(), req.points()));  // req JSON 키(sourceRawSn/sourceSrcSn/label/points)는 FE 계약 유지
        log.info("[Portal] user label saved userId={} rawSn={} srcSn={}",
                actor.sub(), req.sourceRawSn(), req.sourceSrcSn());
        return PortalUserLabelResponse.from(saved);
    }

    /** V2.0 — 본인 작업 라벨 조회 (IDOR: portalUserNo = token sub). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<PortalUserLabelResponse> listMyLabels(Long rawSn, TokenClaims actor) {
        requireActor(actor);
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        return userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(actor.sub(), rawSn)
                .stream().map(PortalUserLabelResponse::from).toList();
    }

    /**
     * R16 — 포털 프레임 라벨 Load.
     *
     * <p>datamart 원본 라벨 + 본인 user-label 병합. 병합 정책:
     * <ul>
     *   <li>본인 user-label 이 있으면 user-label 만 반환 ("기존 라벨 확인·수정" — 작업본 우선).</li>
     *   <li>없으면 datamart 원본 라벨을 초기값으로 반환.</li>
     * </ul>
     * 응답은 내부 LabelsResponse 와 동일 shape (frameNo/srcSn/videoId/siblings/labels) 으로 정렬한다.
     *
     * <p>보안: user-label 은 token sub (portalUserNo) 로만 조회 → 타 사용자 작업분 미노출 (CWE-639).
     */
    public PortalFrameLabelsResponse loadFrameLabels(Long srcSn, TokenClaims actor) {
        requireActor(actor);
        LsDataSrc frame = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        Long rawSn = frame.getRawSn();

        // R17 이슈5 — 포털은 데이터마트 노출(검수 완료=APPROVED) 영상만 접근 가능.
        // 미승인 영상은 라벨 Load 도 403 (이미지 서빙 가드와 정합). FE 는 403 을 graceful 차단 화면으로 처리.
        if (!isExposedToDatamart(rawSn)) {
            log.warn("[Portal] frame labels denied — video not approved srcSn={} rawSn={}", srcSn, rawSn);
            throw new CustomException(ErrorCode.FORBIDDEN, "데이터마트에 노출되지 않은 영상입니다.");
        }

        List<PortalFrameLabelsResponse.Sibling> siblings =
                srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).stream()
                        .map(s -> new PortalFrameLabelsResponse.Sibling(s.getSrcSn(), Math.toIntExact(s.getFrameNo())))
                        .toList();

        List<LsPortalUserLabel> mine =
                userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(actor.sub(), srcSn);

        List<PortalFrameLabelsResponse.Item> items;
        // R17 이슈2 — points 가 비어있는(NULL/공백/빈 좌표) user-label 행은 제외 (로드 방어).
        // 검증 우회로 생성된 stale row(point_cn NULL) 가 빈 라벨로 반환되어 FE 렌더 크래시 → navigate(-1)
        // 튕김을 유발하던 회귀를 차단한다. 저장 경로는 @NotBlank pointsJson 으로 1차 차단.
        // Phase 9 — 포털 키포인트(SKELETON) 제공. 삼중값(17×[x,y,v])은 LBL_TYPE_CD 기반 type-route 로
        // KeypointSerializer 로 파싱해 정상 반환하고, 그 외(BBOX/POLYGON)는 기존 2-튜플 경로로 파싱한다
        // (내부 LabelResponse.Item.parsePoints 와 동일 라우팅). SKELETON skip 필터 제거 — 저장→로드
        // round-trip 이 성립한다(구 ADR-013 SKELETON skip 폐지).
        List<LsPortalUserLabel> mineWithPoints = mine.stream()
                .filter(u -> !parsePointsRouted(u.getLblTypeCd(), u.getPointCn()).isEmpty())
                .toList();
        if (!mineWithPoints.isEmpty()) {
            items = mineWithPoints.stream()
                    .map(u -> new PortalFrameLabelsResponse.Item(
                            u.getUserLblSn(), u.getLblTypeCd(), u.getLabelNm(),
                            parsePointsRouted(u.getLblTypeCd(), u.getPointCn())))
                    .toList();
        } else {
            items = lblRepository.findBySrcSn(srcSn).stream()
                    .map(l -> new PortalFrameLabelsResponse.Item(
                            l.getLblSn(), l.getLblTypeCd(), l.getLabelNm(),
                            parsePointsRouted(l.getLblTypeCd(), l.getPointCn())))
                    .filter(item -> !item.points().isEmpty())
                    .toList();
        }

        return new PortalFrameLabelsResponse(Math.toIntExact(frame.getFrameNo()), srcSn, rawSn, siblings, items);
    }

    /**
     * Phase 9 — 포털 키포인트(SKELETON, 17-keypoint COCO 포즈) 삼중값 검증 (CWE-20 fail-closed).
     *
     * <p>내부 {@code LabelService.validateSkeletonPoints} 와 동일 규칙:
     * <ul>
     *   <li>points 개수 = 정확히 {@value KeypointSerializer#KEYPOINT_COUNT}</li>
     *   <li>각 원소 = [x, y, v] (크기 3), v ∈ {0, 1, 2}, x/y ≥ 0</li>
     * </ul>
     * 형식 위반/개수 불일치/null 원소는 모두 400(INVALID_INPUT). 내부 {@code LabelBulkUpsertRequest}/
     * {@code LS_DATA_LBL} 는 재사용하지 않고 포털 전용 {@code LS_PORTAL_USER_LABEL} 로만 적재한다.
     */
    private void validateSkeletonPoints(String pointsJson) {
        List<KeypointPoint> kps;
        try {
            kps = KeypointSerializer.fromJson(pointsJson, objectMapper);
        } catch (RuntimeException e) {
            // 형식 위반(삼중값 아님/손상 JSON) — 조용히 저장하지 않고 명시적 400.
            throw new CustomException(ErrorCode.INVALID_INPUT, "키포인트 좌표 형식이 올바르지 않습니다.");
        }
        if (kps.size() != KeypointSerializer.KEYPOINT_COUNT) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "SKELETON 키포인트는 정확히 " + KeypointSerializer.KEYPOINT_COUNT + " 개여야 합니다.");
        }
        for (KeypointPoint kp : kps) {
            int v = kp.v();
            if (v < KeypointSerializer.VISIBILITY_MIN || v > KeypointSerializer.VISIBILITY_MAX) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "가시성 v 는 0/1/2 중 하나여야 합니다.");
            }
            // Phase 9 이슈4 — NaN/Infinity 거부 (Phase3 autolabel Double.isFinite 패턴). 음수 검사 이전에 유한성 확인.
            if (!Double.isFinite(kp.x()) || !Double.isFinite(kp.y())) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 유한한 숫자여야 합니다.");
            }
            if (kp.x() < 0 || kp.y() < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 0 이상이어야 합니다.");
            }
        }
    }

    /**
     * Phase 9 — 좌표 파싱 {@code LBL_TYPE_CD} 기반 type-route (내부 {@code LabelResponse.Item.parsePoints} 정합).
     * <ul>
     *   <li>SKELETON: {@link KeypointSerializer#fromJson} 삼중값 [[x,y,v], x17] (v 보존 — round-trip 무손실)</li>
     *   <li>그 외(BBOX/POLYGON/SEGMENT/TRACK): 기존 2-튜플 {@link #parsePoints}</li>
     * </ul>
     * 형식 위반/손상 JSON 은 빈 리스트(fail-secure) — 상위 스트림에서 빈 항목을 걸러 500/렌더 크래시를 차단한다.
     */
    private List<List<Double>> parsePointsRouted(String lblTypeCd, String pointsJson) {
        if (LsDataLbl.TYPE_SKELETON.equals(lblTypeCd)) {
            try {
                List<KeypointPoint> kps = KeypointSerializer.fromJson(pointsJson, objectMapper);
                List<List<Double>> nested = new java.util.ArrayList<>(kps.size());
                for (KeypointPoint kp : kps) {
                    nested.add(List.of(kp.x(), kp.y(), (double) kp.v()));
                }
                return nested;
            } catch (RuntimeException e) {
                log.warn("[Portal] keypoint points parse skipped reason={}", e.getClass().getSimpleName());
                return List.of();
            }
        }
        return parsePoints(pointsJson);
    }

    /**
     * 좌표 JSON 문자열 → [[x,y],...] 중첩 리스트 (2-튜플 전용). 파싱 실패 시 빈 리스트(fail-secure).
     *
     * <p>SKELETON 삼중값은 {@link #parsePointsRouted} 에서 {@link KeypointSerializer} 로 라우팅되며,
     * 여기로 유입되면 {@link LabelPointSerializer} 가 예외를 던지므로 방어적으로 catch 하여 빈 리스트를
     * 반환한다(500 차단).
     */
    private List<List<Double>> parsePoints(String pointsJson) {
        try {
            List<kr.co.cudo.authoring.common.util.Point> parsed =
                    kr.co.cudo.authoring.common.util.LabelPointSerializer.fromJson(pointsJson, objectMapper);
            return parsed.stream()
                    .map(p -> List.of(p.x(), p.y()))
                    .toList();
        } catch (RuntimeException e) {
            // 형식 위반(삼중값/손상 JSON) — 내부 오류가 아닌 데이터 형식 문제. 빈 좌표로 안전 처리.
            log.warn("[Portal] label points parse skipped reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * R16 — 포털 프레임 이미지 서빙.
     *
     * <p>정책:
     * <ul>
     *   <li>데이터마트 노출(검수 완료 = DATA_STTS_CD 'APPROVED') 영상의 프레임만 서빙.
     *       미승인 영상 프레임은 403 (FORBIDDEN).</li>
     *   <li>비식별(DEID) 경로만 서빙 — 포털은 데이터마트 비식별본 대상. 원본 폴백 금지
     *       (deid 경로 부재 시 404).</li>
     *   <li>Path Traversal (CWE-22): baseDir 외부 경로 거부 (FrameImageService.resolveSafe 재사용).</li>
     *   <li>확장자 allowlist 기반 MIME (FrameImageService.resolveMediaType 재사용).</li>
     *   <li>Information Leak (CWE-209): 파일 부재/오류 시 내부 경로 비노출.</li>
     * </ul>
     */
    public ResponseEntity<Resource> serveFrameImage(Long srcSn, TokenClaims actor) throws IOException {
        requireActor(actor);
        LsDataSrc src = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));

        // 데이터마트 노출 조건 = 검수 완료(APPROVED) 영상만
        if (!isExposedToDatamart(src.getRawSn())) {
            log.warn("[Portal] frame image denied — video not approved srcSn={} rawSn={}", srcSn, src.getRawSn());
            throw new CustomException(ErrorCode.FORBIDDEN, "데이터마트에 노출되지 않은 영상입니다.");
        }

        // 비식별 경로만 (원본 폴백 금지)
        String deid = src.getDeidFilePath();
        if (deid == null || deid.isBlank()) {
            log.warn("[Portal] deid path missing srcSn={} rawSn={}", srcSn, src.getRawSn());
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 프레임이 존재하지 않습니다.");
        }

        // R17 이슈1 — deid 프레임은 deidentified-path 기준 절대경로. baseDir 도 deidentified-path 로 잡아야
        // resolveSafe 의 startsWith 검증을 통과한다 (CWE-22 Path Traversal 가드는 그대로 유지).
        Path baseDir = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        Path resolved = FrameImageService.resolveSafe(baseDir, deid);
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[Portal] frame image file not found srcSn={}", srcSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }

        MediaType mediaType = FrameImageService.resolveMediaType(resolved);
        long contentLength = Files.size(resolved);
        Resource body = new FileSystemResource(resolved);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"frame_" + srcSn + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /** 데이터마트 노출 조건 — 검수 완료(APPROVED) 영상만 true. row 부재/타 상태는 false. */
    private boolean isExposedToDatamart(Long rawSn) {
        return rawDataStatusRepository.findById(rawSn)
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }

    private void requireActor(TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }
}
