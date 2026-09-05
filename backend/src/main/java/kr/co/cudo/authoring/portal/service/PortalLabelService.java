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
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

    /** 라벨 마스터 활성 플래그({@code LS_LABEL.USE_YN}) — soft delete 된 마스터는 참조 대상이 아니다. */
    private static final String USE_YN_ACTIVE = "Y";

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

    /**
     * 보존기간 만료 예정 시각 <b>단일 판정 지점</b> — 계산식을 여기서 재유도하지 않는다.
     * @design AC-1068, DFEAT-055
     */
    private final PortalRetentionPolicy retentionPolicy;

    /** 라벨 좌표 JSON 파싱용. 생성자 주입 (@RequiredArgsConstructor). */
    private final ObjectMapper objectMapper;

    /**
     * 라벨 마스터 — 저장 요청이 실어 보낸 {@code labelId} 가 <b>활성 마스터에 실재하는지</b> 확인하는
     * 데만 쓴다. FE 요청을 그대로 믿으면 임의 값이 산출 어노테이션의 분류 식별자로 나간다
     * ({@code AutolabelOnlineService.resolveDetectClasses} 와 같은 원칙 — 요청을 신뢰하지 않고
     * 마스터와 교집합만 취한다).
     */
    private final LsLabelRepository labelMasterRepository;

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

    /**
     * V2.0 — 데이터마트 라벨 Load. rawSn 에 해당하는 원본 라벨 목록 반환 (페이징).
     *
     * <p><b>게이트 2종은 형제 경로({@link #loadFrameLabels})와 동일 순서·동일 컴포넌트다</b> — 판정을
     * 여기서 재구현하지 않는다(2차 QA HIGH: 이 메서드에만 게이트가 <b>복제 누락</b>되어, 포털 채널이
     * rawSn 하나로 미승인·반려·신고구간 영상의 라벨 좌표를 전건 열람할 수 있었다 — CWE-862/639/359).
     * <ol>
     *   <li>{@link #requireActor} — 토큰 부재 401</li>
     *   <li>{@link #isExposedToDatamart} — 검수 완료(APPROVED) 아니면 403.
     *       <b>미존재 rawSn 도 동일하게 403</b>(존재 여부 오라클 차단, CWE-209)</li>
     *   <li>{@link LabelAccessGuard#requireNotUnderDeidentReport} — 비식별 누락 신고 구간이면 412.
     *       resolve('F'→'Y') 로 자동 해제</li>
     * </ol>
     * 세 검사는 모두 {@code findAllByRawSn} <b>이전</b>에 수행한다 — 거부될 요청이 영상 전체 라벨을
     * 풀스캔하지 않도록(CWE-770).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size, TokenClaims actor) {
        requireActor(actor);
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        if (!isExposedToDatamart(rawSn)) {
            log.warn("[Portal] datamart labels denied — video not approved rawSn={}", rawSn);
            throw new CustomException(ErrorCode.FORBIDDEN, "데이터마트에 노출되지 않은 영상입니다.");
        }
        accessGuard.requireNotUnderDeidentReport(rawSn);

        int clampedSize = Math.min(Math.max(size, 1), 100);
        int clampedPage = Math.max(page, 0);
        List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);
        // 3차 QA (CWE-190/129) — page 는 상한이 없어 clampedPage * clampedSize 가 int 를 넘으면
        //   음수로 접힌다(page=2147483647 & size=100). 그러면 아래 범위 가드를 통과해
        //   subList(음수, 음수) → IndexOutOfBoundsException → 500 이 됐다(인증된 PORTAL_USER 가
        //   APPROVED rawSn 하나만 알면 트리거). long 으로 계산해 오버플로 자체를 없앤다.
        long from = (long) clampedPage * clampedSize;
        if (from >= all.size()) {
            return List.of();
        }
        int fromIndex = (int) from;  // 위 가드로 from < all.size() ≤ Integer.MAX_VALUE 가 보장된다.
        int toIndex = (int) Math.min((long) fromIndex + clampedSize, all.size());
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
     * <p>N+1 회피: 페이지 rawSn 집합에 대해 firstSrcSn / frameCount / lastUpdatedAt /
     * 본인 저장 라벨 최초 저장일을 각 1회 IN 쿼리로 조회한다.
     *
     * <p>{@code myLabelExpiresAt} 은 본인 저장 라벨의 보존기간 만료 예정 시각으로,
     * <b>저장되지 않는 조회 시점 파생값</b>이다(AC-1068). 보존기간 설정을 바꾸면 이미 저장된 라벨의
     * 만료 예정도 다음 조회부터 즉시 달라진다 — 판정은 {@link PortalRetentionPolicy} 한 곳에서 하고
     * 설정은 페이지당 1회만 읽는다({@code datamartExpiry()} 스냅샷).
     *
     * <p>기준점은 <b>최초</b> 저장일이라 저장을 반복해도 만료 예정이 뒤로 밀리지 않는다
     * (DFEAT-055 — 포털 확정 회신 2026-09-03). @design DFEAT-055, AC-1068
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
        Map<Long, LocalDateTime> myFirstLabelSavedAt = lookupMyFirstLabelSavedAt(actor.sub(), rawSns);
        PortalRetentionPolicy.DatamartExpiry expiry = retentionPolicy.datamartExpiry();

        // 프레임 0건(=firstSrcSn 부재) 영상은 진입 불가하므로 제외 (MED 방어).
        List<DatamartVideoResponse> content = rows.stream()
                .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)
                .map(r -> new DatamartVideoResponse(
                        r.getRawSn(),
                        r.getVmsClipId(),
                        r.getEvntTypeCd(),
                        frameCountByVideo.getOrDefault(r.getRawSn(), 0L),
                        firstSrcSnByVideo.get(r.getRawSn()),
                        lastUpdatedAtByVideo.get(r.getRawSn()),
                        expiry.expiresAt(myFirstLabelSavedAt.get(r.getRawSn()))))
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

    /**
     * 영상별 <b>본인</b> 저장 라벨의 <b>최초</b> 저장일 — 만료 예정 시각의 기준점.
     * @design DFEAT-055, AC-1068
     *
     * <p>단일 집계 쿼리 1회(N+1 회피). 저장 라벨이 없는 영상은 <b>키가 없어</b> null 로 읽히고,
     * 그러면 {@link PortalRetentionPolicy.DatamartExpiry} 가 만료 예정 시각을 만들지 않는다.
     *
     * <p>삭제 배치가 후보를 고르는 쿼리와 <b>같은 집계 함수</b>({@code MIN})를 써야 화면이 고지한
     * 만료일과 실제 삭제일이 어긋나지 않는다 — 한쪽만 바꾸지 말 것.
     */
    private Map<Long, LocalDateTime> lookupMyFirstLabelSavedAt(String portalUserNo, List<Long> rawSns) {
        Map<Long, LocalDateTime> map = new HashMap<>();
        for (Object[] row : userLabelRepository.findMinRegDtGroupedBySrcRawSn(portalUserNo, rawSns)) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            map.put(((Number) row[0]).longValue(), (LocalDateTime) row[1]);
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

    /**
     * V2.0 — 사용자 라벨 저장. 원본 미수정 — LS_PORTAL_USER_LABEL 별도 적재.
     *
     * <p>게이트·검증 순서(전부 저장 이전 — fail-closed):
     * <ol>
     *   <li>{@link #requireActor} — 토큰 부재 401</li>
     *   <li>{@link #isExposedToDatamart} — 검수 완료(APPROVED) 아니면 403</li>
     *   <li>{@link LabelAccessGuard#requireNotUnderDeidentReport} — 비식별 누락 신고 구간이면 412.
     *       조회 4경로(datamart 라벨 / 본인 라벨 / 프레임 라벨 / 프레임 이미지)는 모두 이 게이트를
     *       갖는데 <b>저장 경로만 누락</b>돼 있었다. 신고는 "이 영상의 비식별이 잘못됐다"는 신호이므로
     *       그 구간에 PII 위치 좌표를 새로 적재하도록 두지 않는다. resolve('F'→'Y') 로 자동 해제.</li>
     *   <li>{@link #validateAndNormalizeType} — {@code lblTypeCd} allowlist(BBOX|POLYGON) 400</li>
     *   <li>{@link #validatePointCount} — 타입별 좌표 개수 상한 400 (CWE-770)</li>
     *   <li>{@link #normalizeAndSerialize} — 좌표 유한성 400 + <b>정규형 재직렬화</b>(적재값 확정)</li>
     * </ol>
     */
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
        accessGuard.requireNotUnderDeidentReport(req.sourceRawSn());

        String lblTypeCd = validateAndNormalizeType(req.lblTypeCd());
        // R17 이슈2 — @NotBlank 가 NULL/공백을 막더라도 빈 좌표 JSON('[]','[[]]')은 통과한다.
        // 좌표가 0개로 파싱되는 라벨은 거부 (빈 라벨 row 생성 차단 — fail-closed).
        List<List<Double>> points = parsePoints(req.points());
        if (points.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "points 좌표가 비어있습니다.");
        }
        validatePointCount(lblTypeCd, points.size());
        String pointCn = normalizeAndSerialize(points);
        String trackId = validateTrackId(req.trackId());
        Long labelId = resolveLabelMasterId(req.labelId());

        LsPortalUserLabel saved = userLabelRepository.save(
                LsPortalUserLabel.create(actor.sub(), req.sourceRawSn(), req.sourceSrcSn(),
                        lblTypeCd, req.label(), pointCn,
                        labelId, trackId));  // req JSON 키(sourceRawSn/sourceSrcSn/label/points)는 FE 계약 유지
        // 토큰 sub 는 서명 검증을 통과한 값이지만 로그 라인 위조(CWE-117) 방어는 형제 경로
        // (PortalUploadLabelService)와 동일하게 LogSanitizer 로 통일한다. 좌표(points)는 PII 위치
        // 정보라 로그에 남기지 않는다(CWE-359).
        log.info("[Portal] user label saved userId={} rawSn={} srcSn={} type={}",
                LogSanitizer.sanitize(actor.sub()), req.sourceRawSn(), req.sourceSrcSn(), lblTypeCd);
        return PortalUserLabelResponse.from(saved);
    }

    /**
     * 활성 라벨 마스터에 <b>실재하는 참조만</b> 통과시킨다 — 없으면 그 값만 비우고 <b>저장은 성공</b>한다.
     *
     * <p>거부하지 않는 이유(확정): 포털 사용자는 마스터 비활성화를 볼 수 없다. 운영자가 마스터를
     * 내리면 그 라벨을 이미 화면에 띄워 둔 사용자의 <b>작업 저장이 통째로 막힌다</b>. 분류 연결
     * 하나를 잃는 것과 작업을 잃는 것 중 후자가 더 나쁘다.
     *
     * <p>반대로 <b>요청값을 그대로 믿지도 않는다</b> — 믿으면 임의 정수가 산출 어노테이션의 분류
     * 식별자로 그대로 나가 존재하지 않는 분류를 가리키게 된다(FE 요청을 신뢰하지 않고 마스터와
     * 교집합만 취하는 {@code AutolabelOnlineService.resolveDetectClasses} 와 같은 원칙).
     *
     * <p>라벨명으로 유추해 채우지 않는다 — 라벨명에 유일성 제약이 없어 다른 분류로 이어질 수 있다.
     *
     * @return 실재 확인된 마스터 식별자, 미실재·미지정이면 {@code null}
     * @design API-082
     */
    private Long resolveLabelMasterId(Long requested) {
        if (requested == null) {
            return null;
        }
        List<LsLabel> found =
                labelMasterRepository.findByLabelIdInAndUseYn(List.of(requested), USE_YN_ACTIVE);
        if (found.isEmpty()) {
            // 요청값(정수)은 PII 가 아니라 그대로 남겨 운영 추적을 가능하게 한다.
            log.warn("[Portal] user label save — labelId dropped (not an active label master) labelId={}",
                    requested);
            return null;
        }
        return found.get(0).getLabelId();
    }

    /**
     * {@code trackId} 길이 상한을 <b>입구에서</b> 강제한다.
     *
     * <p>DTO {@code @Size} 와 이중이지만 대체하지 않는다 — 이 검사가 없으면 서비스를 직접 부르는
     * 경로에서 초과 문자열이 적재 시점까지 흘러가 <b>DB 오류(500)</b>가 된다. 컬럼 폭을 넘는 입력은
     * 서버 오류가 아니라 잘못된 요청이다.
     *
     * @return 그대로 통과한 값(공백만 있으면 {@code null})
     */
    private static String validateTrackId(String trackId) {
        if (trackId == null || trackId.isBlank()) {
            return null;
        }
        if (trackId.length() > LsPortalUserLabel.TRACK_ID_MAX_LENGTH) {
            // 사용자 입력 원문은 로그에 남기지 않는다(CWE-117) — 거부 사실만.
            log.warn("[Portal] user label save denied — trackId too long");
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "trackId 는 " + LsPortalUserLabel.TRACK_ID_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return trackId;
    }

    /**
     * {@code lblTypeCd} allowlist — <b>BBOX|POLYGON 만</b>(fail-closed, CWE-20).
     *
     * <p>정책 근거: CLAUDE.md 포털 절 — 포털 라벨링은 BBOX/POLYGON 만이다(ADR-013 예외). 구
     * "Phase 9 — 포털 키포인트(SKELETON) 허용"은 폐기됐다. 그전까지 이 경로에는 allowlist 자체가
     * 없어 {@code @Size(max=16)} 만 통과하면 <b>임의 문자열이 그대로 LBL_TYPE_CD 에 적재</b>됐고,
     * SKELETON 은 저장→로드 round-trip 까지 성립해 FE 에서만 숨겨졌을 뿐 서버 기능이 살아 있었다.
     * 형제 경로({@link PortalUploadLabelService})와 동일 규칙·동일 상수를 쓴다.
     *
     * @return 대문자 정규화된 확정 타입(적재값)
     */
    private String validateAndNormalizeType(String rawType) {
        String type = rawType == null ? "" : rawType.toUpperCase(Locale.ROOT);
        if (!LsDataLbl.TYPE_BBOX.equals(type) && !LsDataLbl.TYPE_POLYGON.equals(type)) {
            // 사용자 입력 원문은 로그에 남기지 않는다(CWE-117) — 거부 사실만 기록.
            log.warn("[Portal] user label save denied — lblTypeCd not allowed");
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 lblTypeCd 입니다. 허용: BBOX, POLYGON");
        }
        return type;
    }

    /**
     * 타입별 좌표 개수 상한 (CWE-770) — 형제 {@link PortalUploadLabelService} 상수를 그대로 참조한다
     * (사본 금지 — 같은 포털 라벨 계약이므로 값이 갈라지면 두 경로의 수용 범위가 달라진다).
     *
     * <p>{@code points} 는 문자열 필드라 {@code @Size} 길이 상한만 있었고 <b>좌표 개수 상한이
     * 없었다</b> — 64KB 안에서 수천 점짜리 폴리곤이 그대로 적재·렌더된다.
     */
    private void validatePointCount(String lblTypeCd, int pointCount) {
        if (LsDataLbl.TYPE_BBOX.equals(lblTypeCd)
                && pointCount != PortalUploadLabelService.BBOX_POINT_COUNT) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "BBOX 는 정확히 " + PortalUploadLabelService.BBOX_POINT_COUNT + " 점이어야 합니다.");
        }
        if (LsDataLbl.TYPE_POLYGON.equals(lblTypeCd)
                && (pointCount < PortalUploadLabelService.POLYGON_MIN_POINTS
                        || pointCount > PortalUploadLabelService.POLYGON_MAX_POINTS)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "POLYGON 은 " + PortalUploadLabelService.POLYGON_MIN_POINTS + "~"
                            + PortalUploadLabelService.POLYGON_MAX_POINTS + " 점이어야 합니다.");
        }
    }

    /**
     * 파싱·검증된 좌표를 <b>정규형 JSON({@code [[x,y], ...]})으로 재직렬화</b>하여 적재값을 확정한다
     * — 형제 {@link PortalUploadLabelService#validateAndPrepare} 와 동일 규약(사본 금지, 두 포털
     * 라벨 경로의 적재 포맷·수용 범위를 일치시킨다).
     *
     * <p><b>왜 요청 원문을 그대로 적재하면 안 되는가 (CWE-770)</b>: 좌표 개수 상한
     * ({@link #validatePointCount})은 <b>파싱된 개수</b>만 보는데 적재값은 {@code req.points()}
     * 원문 문자열이었다. 그래서 {@code [[1, <60KB 공백> 2],[3,4]]} 같은 <b>유효 JSON</b>은
     * BBOX 2점으로 개수 캡을 통과한 뒤 {@code POINT_CN}(TEXT)에 64KB 그대로 적재됐다
     * (고정밀 소수로도 200점 이내에서 동일 도달). 즉 개수 캡이 저장 자원 방어로 성립하지 않았다.
     * 검증한 값과 적재하는 값이 <b>같아야</b> 상한이 실효를 갖는다.
     *
     * <p><b>유한성 검증 (CWE-20)</b>: {@code 1e400} 은 유효 JSON 숫자 리터럴이지만 double 로는
     * {@code Infinity} 이고, 그대로 적재되면 조회·렌더 경로로 그 값이 흘러간다. 형제 경로는
     * {@code Double.isFinite} 로 거부하는데 이 경로만 통과시키던 비대칭을 정합한다.
     *
     * @return 적재할 정규형 좌표 JSON
     */
    private String normalizeAndSerialize(List<List<Double>> points) {
        List<List<Double>> normalized = new ArrayList<>(points.size());
        for (List<Double> p : points) {
            // parsePoints 는 항상 2-튜플 non-null 을 산출하므로 형식 검사는 불필요하고 유한성만 본다.
            double x = p.get(0);
            double y = p.get(1);
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                // 좌표 원문은 PII 위치 정보이자 사용자 입력이라 로그에 남기지 않는다(CWE-359/117).
                log.warn("[Portal] user label save denied — non-finite coordinate");
                throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 유한한 숫자여야 합니다.");
            }
            normalized.add(List.of(x, y));
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (IOException e) {
            // 유한 double 만 남은 시점이라 도달하지 않는 경로지만, 내부 원인 비노출로 400 마감(CWE-209).
            log.warn("[Portal] user label points serialize failed reason={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT, "좌표 직렬화에 실패했습니다.");
        }
    }

    /**
     * V2.0 — 본인 작업 라벨 조회 (IDOR: portalUserNo = token sub).
     *
     * <p><b>게이트 2종은 형제 경로({@link #loadDatamartLabels} / {@link #loadFrameLabels})와 동일
     * 순서·동일 컴포넌트다</b> — 3차 QA HIGH(CWE-862/359): 이 경로만 무게이트라 신고 구간
     * ({@code DE_IDNTF_YN='F'})에 진입해도 <b>동일 좌표가 다른 URL 로 200 으로 계속 나갔다</b>.
     * "본인이 저장한 사본"이라는 사실은 완화 사유가 되지 않는다 — 좌표는 원본과 같은 PII 위치
     * 특정 정보이고, 저장 시점에 데이터마트 원본이 초기값으로 실려 있을 수 있다.
     * 판정은 여기서 재구현하지 않고 {@link #isExposedToDatamart} /
     * {@link LabelAccessGuard#requireNotUnderDeidentReport} 를 그대로 재사용한다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<PortalUserLabelResponse> listMyLabels(Long rawSn, TokenClaims actor) {
        requireActor(actor);
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        if (!isExposedToDatamart(rawSn)) {
            log.warn("[Portal] user labels denied — video not approved rawSn={}", rawSn);
            throw new CustomException(ErrorCode.FORBIDDEN, "데이터마트에 노출되지 않은 영상입니다.");
        }
        accessGuard.requireNotUnderDeidentReport(rawSn);

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

        // 병합 판정은 아래 단일 지점에 위임한다 — 데이터마트 원본은 <b>본인 저장분이 없을 때만</b>
        // 조회되도록 Supplier 로 늦춘다(기존 동작 보존: 본인 저장분이 있으면 원본 쿼리가 나가지 않는다).
        List<PortalFrameLabelsResponse.Item> items =
                mergeFrameItems(mine, () -> lblRepository.findBySrcSn(srcSn));

        return new PortalFrameLabelsResponse(Math.toIntExact(frame.getFrameNo()), srcSn, rawSn, siblings, items);
    }

    /**
     * 프레임 단위 라벨 <b>병합 규칙의 단일 판정 지점</b> — 본인 저장분이 (좌표가 있는 행으로) 1건이라도
     * 있으면 <b>본인 저장분만</b>, 없으면 데이터마트 원본을 반환한다. @design AC-035
     *
     * <h3>왜 별도 메서드로 뽑았는가</h3>
     * <p>{@link #loadFrameLabels} 외에 <b>데이터마트 ZIP 다운로드</b>(API-203)가 같은 규칙을 프레임
     * 전체에 적용해야 한다. 규칙을 그쪽에 복제하면 두 경로의 "본인 저장분 우선"이 갈라질 수 있고,
     * 그 갈라짐은 곧 <b>타 사용자 저장분 노출</b>(AC-035 위반)로 이어진다. 반대로 다운로드가
     * {@link #loadFrameLabels} 를 프레임마다 부르면 형제 프레임 목록(siblings) 쿼리가 프레임 수만큼
     * 반복돼 N&sup2; 행을 읽는다. 그래서 <b>판정만</b> 공유하고 조회는 각자 자기 입도로 한다.
     *
     * <p>R17 이슈2 — points 가 비어있는(NULL/공백/빈 좌표) user-label 행은 제외한다(로드 방어).
     * 검증 우회로 생성된 stale row(point_cn NULL)가 빈 라벨로 반환되어 FE 렌더 크래시를 유발하던
     * 회귀를 차단한다. 포털은 2-튜플 좌표(BBOX/POLYGON) 전용이므로 레거시 삼중값(SKELETON)은
     * 2-튜플 파서에서 형식 위반으로 걸러져 빈 좌표가 되고 항목 자체가 제외된다.
     *
     * @param mine           본인 저장 라벨(그 프레임) — 최신순
     * @param datamartLabels 데이터마트 원본 라벨 공급자 — <b>본인 저장분이 없을 때만</b> 호출된다
     */
    public List<PortalFrameLabelsResponse.Item> mergeFrameItems(
            List<LsPortalUserLabel> mine,
            java.util.function.Supplier<List<LsDataLbl>> datamartLabels) {
        FrameLabelSelection selection = selectFrameLabels(mine, datamartLabels);
        if (!selection.mine().isEmpty()) {
            return selection.mine().stream()
                    .map(u -> new PortalFrameLabelsResponse.Item(
                            u.getUserLblSn(), u.getLblTypeCd(), u.getLabelNm(),
                            parsePoints(u.getPointCn()), u.getLabelId(), u.getTrackId()))
                    .toList();
        }
        return selection.datamart().stream()
                .map(l -> new PortalFrameLabelsResponse.Item(
                        l.getLblSn(), l.getLblTypeCd(), l.getLabelNm(),
                        parsePoints(l.getPointCn()), l.getLabelId(), l.getTrackId()))
                .toList();
    }

    /**
     * 한 프레임에서 <b>어느 저장소가 이긴 라벨인가</b>의 판정 결과 — 둘 중 최대 하나만 비어 있지 않다.
     *
     * <p>좌표가 없는 행은 <b>이미 걸러진 상태</b>다(로드 방어 — 검증 우회로 생긴 stale row 나
     * 레거시 삼중값(SKELETON)은 2-튜플 파서에서 빈 좌표가 되어 제외된다).
     */
    public record FrameLabelSelection(List<LsPortalUserLabel> mine, List<LsDataLbl> datamart) {}

    /**
     * 프레임 단위 병합 <b>판정 그 자체</b> — 표현(응답 항목 / 산출 어노테이션)과 분리된 단일 지점이다.
     *
     * <h3>왜 판정과 표현을 갈랐는가</h3>
     * <p>같은 규칙을 두 소비자가 쓰는데 <b>원하는 결과 형태가 다르다</b>: 프레임 라벨 조회는 좌표를
     * 파싱한 응답 항목을, ZIP 다운로드는 산출 어노테이션 입력을 만든다. 둘 중 한쪽이 판정을 복제하면
     * "본인 저장분 우선"이 갈라질 수 있고, 그 갈라짐은 곧 <b>타 사용자 저장분 노출</b>(AC-035 위반)이다.
     * 그래서 판정만 여기서 하고 형태 변환은 각 소비자가 한다.
     *
     * @param mine           본인 저장 라벨(그 프레임) — 최신순
     * @param datamartLabels 데이터마트 원본 라벨 공급자 — <b>본인 저장분이 없을 때만</b> 호출된다
     * @design AC-035
     */
    public FrameLabelSelection selectFrameLabels(
            List<LsPortalUserLabel> mine,
            java.util.function.Supplier<List<LsDataLbl>> datamartLabels) {
        List<LsPortalUserLabel> mineWithPoints = (mine == null ? List.<LsPortalUserLabel>of() : mine).stream()
                .filter(u -> !parsePoints(u.getPointCn()).isEmpty())
                .toList();
        if (!mineWithPoints.isEmpty()) {
            return new FrameLabelSelection(mineWithPoints, List.of());
        }
        List<LsDataLbl> datamartWithPoints = datamartLabels.get().stream()
                .filter(l -> !parsePoints(l.getPointCn()).isEmpty())
                .toList();
        return new FrameLabelSelection(List.of(), datamartWithPoints);
    }

    /**
     * 좌표 JSON 문자열 → [[x,y],...] 중첩 리스트 (2-튜플 전용). 파싱 실패 시 빈 리스트(fail-secure).
     *
     * <p>포털은 BBOX/POLYGON 전용이므로 <b>2-튜플 파서 하나만</b> 쓴다. 레거시 SKELETON 삼중값이
     * 유입되면 {@code LabelPointSerializer} 가 예외를 던지므로 방어적으로 catch 하여 빈 리스트를
     * 반환하고(500 차단), 호출부가 빈 좌표 항목을 제외한다.
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
