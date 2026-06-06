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
import kr.co.cudo.authoring.portal.dto.DatamartLabelResponse;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
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

    /** 라벨 좌표 JSON 파싱용. 생성자 주입 (@RequiredArgsConstructor). */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

    /** V2.0 — 사용자 라벨 저장. 원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재. */
    @Transactional("controlTransactionManager")
    public PortalUserLabelResponse saveUserLabel(PortalUserLabelRequest req, TokenClaims actor) {
        requireActor(actor);
        // R17 이슈2 — @NotBlank 가 NULL/공백을 막더라도 빈 좌표 JSON('[]','[[]]')은 통과한다.
        // 좌표가 0개로 파싱되는 라벨은 거부 (빈 라벨 row 생성 차단 — fail-closed).
        if (parsePoints(req.points()).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "points 좌표가 비어있습니다.");
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
                        .map(s -> new PortalFrameLabelsResponse.Sibling(s.getSrcSn(), s.getFrameNo()))
                        .toList();

        List<LsPortalUserLabel> mine =
                userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(actor.sub(), srcSn);

        List<PortalFrameLabelsResponse.Item> items;
        // R17 이슈2 — points 가 비어있는(NULL/공백/빈 좌표) user-label 행은 제외 (로드 방어).
        // 검증 우회로 생성된 stale row(point_cn NULL) 가 빈 라벨로 반환되어 FE 렌더 크래시 → navigate(-1)
        // 튕김을 유발하던 회귀를 차단한다. 저장 경로는 @NotBlank pointsJson 으로 1차 차단.
        List<LsPortalUserLabel> mineWithPoints = mine.stream()
                .filter(u -> !parsePoints(u.getPointCn()).isEmpty())
                .toList();
        if (!mineWithPoints.isEmpty()) {
            items = mineWithPoints.stream()
                    .map(u -> new PortalFrameLabelsResponse.Item(
                            u.getUserLblSn(), u.getLblTypeCd(), u.getLabelNm(),
                            parsePoints(u.getPointCn())))
                    .toList();
        } else {
            items = lblRepository.findBySrcSn(srcSn).stream()
                    .map(l -> new PortalFrameLabelsResponse.Item(
                            l.getLblSn(), l.getLblTypeCd(), l.getLabelNm(),
                            parsePoints(l.getPointCn())))
                    .toList();
        }

        return new PortalFrameLabelsResponse(frame.getFrameNo(), srcSn, rawSn, siblings, items);
    }

    /** 좌표 JSON 문자열 → [[x,y],...] 중첩 리스트. 파싱 실패 시 빈 리스트(fail-secure). */
    private List<List<Double>> parsePoints(String pointsJson) {
        List<kr.co.cudo.authoring.common.util.Point> parsed =
                kr.co.cudo.authoring.common.util.LabelPointSerializer.fromJson(pointsJson, objectMapper);
        return parsed.stream()
                .map(p -> List.of(p.x(), p.y()))
                .toList();
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
        Path resolved = kr.co.cudo.authoring.video.service.FrameImageService.resolveSafe(baseDir, deid);
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[Portal] frame image file not found srcSn={}", srcSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }

        MediaType mediaType = kr.co.cudo.authoring.video.service.FrameImageService.resolveMediaType(resolved);
        long contentLength = Files.size(resolved);
        Resource body = new org.springframework.core.io.FileSystemResource(resolved);

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
