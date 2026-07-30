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
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.util.KeypointPoint;
import kr.co.cudo.authoring.common.util.KeypointSerializer;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
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
 *  - 포털은 데이터마트 영상 선택 전용 — 오토라벨링 미제공.
 *  - 검수 / 버전관리(DB 스냅샷) / VLM 검증 미제공 → LabelService 의 풀 워크플로우 미사용.
 *  - 본 서비스는 데이터마트 라벨 Load + 본인 작업 라벨 별도 적재만 수행.
 *  - ADR-013 예외(2026-07-17): 포털 사용자 본인 자산(이미지/영상) 업로드 + 수동 라벨링(BBOX/POLYGON)은
 *    별도 경로(LS_PORTAL_* 전용, 내부 파이프라인·데이터마트와 완전 분리)로 신설됨 — 본 서비스가 아닌
 *    포털 업로드 전용 서비스가 담당. 본 서비스는 데이터마트 라벨 Load 책임만 유지.
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
    /**
     * S7 (DEV_FIX-A/H2) — 비식별 누락 신고 구간 게이트. 포털은 <b>외부 채널</b>이라 노출 영향이 가장 크므로
     * 내부 경로와 동일한 단일 게이트({@link LabelAccessGuard#requireNotUnderDeidentReport})를 재사용한다
     * (판정 복제 금지 — 배선 누락이 결함의 원인이었다).
     */
    private final LabelAccessGuard accessGuard;

    /** 라벨 좌표 JSON 파싱용. 생성자 주입 (@RequiredArgsConstructor). */
    private final ObjectMapper objectMapper;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * R17 이슈1 — 비식별 프레임 이미지 base 경로.
     * deidFilePath 는 FFmpeg 추출 단계에서 deidentified-path 기준 절대경로로 저장된다
     * ({@code FfmpegFrameExtractor.attachDeidPath(deidFrame.toString())}).
     * 따라서 경로 검증({@link StorageSubtreePolicy#verifyDeidentifiedFile})의 base 도
     * raw-path 가 아닌 deidentified-path 여야 한다.
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

        // S7 (DEV_FIX-A/H2 — HIGH, CWE-359) — 비식별 누락 신고 구간(DE_IDNTF_YN='F')에는 라벨 좌표를
        //   내려주지 않는다. APPROVED 게이트만으로는 성립하지 않는 노출 경로다: 신고(report)는
        //   LS_RAW_DATA_STATUS 를 건드리지 않아 APPROVED 가 유지되고, 정책 반전(2026-07-27)으로 라벨도
        //   보존되므로 PORTAL_USER 가 PII 위치를 특정하는 좌표를 계속 읽을 수 있었다.
        //   포털은 외부 채널이므로 역할 예외 없이 차단한다. resolve('F'→'Y') 로 자동 해제.
        accessGuard.requireNotUnderDeidentReport(rawSn);

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
     * R16 — 포털 프레임 이미지 서빙 ({@code GET /v1/portal/frames/{srcSn}/image}).
     *
     * <p><b>혼동 주의 — 포털에는 이미지 서빙 경로가 2종이며 성격이 정반대다.</b>
     * <ul>
     *   <li><b>이 메서드(데이터마트 프레임)</b>: <b>내부 파이프라인이 만든 비식별 프레임</b>({@code LS_DATA_SRC})
     *       을 외부 채널로 내보낸다. 비식별 누락 신고 게이트 <b>대상</b>이며(아래 {@code requireNotUnderDeidentReport}),
     *       따라서 응답 캐시는 반드시 {@code no-store} 다.</li>
     *   <li><b>포털 업로드 자산</b>({@code PortalUploadService#serveFrameImage},
     *       {@code GET /v1/portal/uploads/frames/{uldFrmeSn}/image}, {@code LS_PORTAL_ULD_FRME}):
     *       포털 사용자가 <b>본인이 업로드한</b> 자산이라 비식별 대상이 아니고 신고 게이트도 없다
     *       (ADR-013 예외, 내부 파이프라인·데이터마트와 완전 분리). 이 캐시 정책 통일 대상이 아니다.</li>
     * </ul>
     *
     * <p>정책:
     * <ul>
     *   <li>데이터마트 노출(검수 완료 = DATA_STTS_CD 'APPROVED') 영상의 프레임만 서빙.
     *       미승인 영상 프레임은 403 (FORBIDDEN).</li>
     *   <li>비식별(DEID) 경로만 서빙 — 포털은 데이터마트 비식별본 대상. 원본 폴백 금지
     *       (deid 경로 부재 시 404).</li>
     *   <li><b>경로 검증은 단일 판정기</b>({@link StorageSubtreePolicy#verifyDeidentifiedFile}) —
     *       base 포함(CWE-22) + 존재/정규파일 + <b>실경로({@code toRealPath}) 기준</b> 비식별 서브트리
     *       ({@code frames/deid/**}·{@code videos/**}). 아래 "왜 lexical 검증으로는 부족한가" 참조.</li>
     *   <li>확장자 allowlist 기반 MIME (FrameImageService.resolveMediaType 재사용).</li>
     *   <li><b>open 은 NOFOLLOW</b>({@link FrameImageService#openNoFollow}) — 내부 경로와 <b>같은 헬퍼</b>.</li>
     *   <li>Information Leak (CWE-209): 파일 부재/오류 시 내부 경로·예외 원인 비노출.</li>
     * </ul>
     *
     * <h3>왜 lexical 검증(구 {@code resolveSafe})으로는 부족한가 (CWE-59/367/22/359)</h3>
     * <p>운영 형상은 {@code STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH}(={@code /nas-storage},
     * 의도된 동일 설정)다. 이때 {@code startsWith(deidBase)} 만 보는 lexical 검사는
     * {@code frames/raw/**}(마스킹 전 원본 프레임)도 그대로 통과시킨다. 여기에 더해
     * {@code frames/deid/{rawSn}/f.jpg → ../../raw/{rawSn}/f.jpg} 심링크가 있으면
     * {@code Files.exists}/{@code Files.size}/{@code FileSystemResource} 는 모두 <b>링크를 따라가</b>
     * 원본 픽셀을 "비식별본"으로 200 서빙한다 — 그것도 <b>외부 채널(PORTAL_USER)</b> 로.
     * 따라서 ①서브트리 판정을 <b>실경로</b>에 적용하고 ②판정에 쓴 <b>그 실경로</b>를 열며
     * ③open 자체를 {@code NOFOLLOW_LINKS} 로 해 판정~open 사이 교체(TOCTOU)까지 fail-closed 로 막는다.
     * export({@code FrameSource})·내부 서빙({@code FrameImageService})이 이미 같은 규약이며,
     * 이 포털 경로만 남아 있던 것을 정합했다. <b>판정·open 규약을 여기서 재구현하지 않는다.</b>
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

        // S7 (DEV_FIX-A/H2 — HIGH, CWE-359) — 신고 구간에는 "비식별 누락이 확인된" 그 비식별 프레임을
        //   서빙하지 않는다(라벨 좌표보다 상위 위험 = 실제 PII 이미지). 라벨 조회와 동일 게이트·동일 조건.
        accessGuard.requireNotUnderDeidentReport(src.getRawSn());

        // 비식별 경로만 (원본 폴백 금지)
        String deid = src.getDeidFilePath();
        if (deid == null || deid.isBlank()) {
            log.warn("[Portal] deid path missing srcSn={} rawSn={}", srcSn, src.getRawSn());
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 프레임이 존재하지 않습니다.");
        }

        // R17 이슈1 — deid 프레임은 deidentified-path 기준 절대경로. baseDir 도 deidentified-path 로 잡아야
        // base 포함 검증을 통과한다 (CWE-22 Path Traversal 가드는 그대로 유지).
        // 여기서 판정을 국소 재구현하지 않고 export·내부 서빙과 <b>literally 같은 판정기</b>를 쓴다 —
        // 두 base 동일 운영 형상에서 lexical 검사는 frames/raw/** 를 통과시키고(fail-open),
        // 심링크는 실경로 검사 없이는 잡히지 않는다(CWE-59/359).
        Path baseDir = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        StorageSubtreePolicy.Verification verification =
                StorageSubtreePolicy.verifyDeidentifiedFile(baseDir, deid);
        if (!verification.ok()) {
            // 사유 코드만 로그에 남긴다 — 경로 원문/내부 구조 비노출(CWE-209/117).
            log.warn("[Portal] frame image rejected srcSn={} rawSn={} verdict={}",
                    srcSn, src.getRawSn(), verification.verdict());
            // 응답 코드는 <b>기존 포털 계약 그대로</b>: 경로 부재/파일 없음 = 404,
            // base 이탈·비식별 서브트리 밖(심링크 우회 포함) = 403(구 resolveSafe FORBIDDEN 과 동일).
            throw switch (verification.verdict()) {
                case BLANK, MISSING, NOT_REGULAR_FILE, REALPATH_FAILED ->
                        new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
                default -> new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
            };
        }
        // A-1 — 판정에 쓴 <b>실경로</b>를 그대로 사용한다(lexical 경로를 열면 검증 대상 ≠ 사용 대상).
        Path resolved = verification.path();

        MediaType mediaType = FrameImageService.resolveMediaType(resolved);

        // open 도 내부 서빙과 동일 규약(NOFOLLOW_LINKS) — 판정~open 사이에 최종 컴포넌트가
        // 원본 프레임을 가리키는 심링크로 교체돼도 따라가지 않고 실패한다(TOCTOU, CWE-367).
        // 크기와 스트림을 같은 open 에서 얻어 판정 대상과 응답 대상이 어긋나지 않게 한다.
        FrameImageService.OpenedFile opened;
        try {
            opened = FrameImageService.openNoFollow(resolved);
        } catch (IOException e) {
            // 내부 경로/원인 노출 없이 규약 4xx 로 마감(CWE-209, OWASP A10) — 식별자 + 예외 클래스명만.
            log.warn("[Portal] frame image open failed srcSn={} reason={}", srcSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }
        Resource body = new InputStreamResource(opened.stream());

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(opened.size())
                // 신고 게이트(위 requireNotUnderDeidentReport)가 매 요청 평가되려면 브라우저 HTTP 캐시가 응답을 재사용하면
                // 안 된다 — max-age 동안 캐시된 "마스킹 실패" 비식별 프레임이 412 로 바뀐 뒤에도
                // 그대로 재노출된다(CWE-359/525). 내부 /v1/frames/{srcSn}/image ·
                // /deid-image · 영상 /stream 과 동일하게 no-store 로 통일.
                .cacheControl(CacheControl.noStore())
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
