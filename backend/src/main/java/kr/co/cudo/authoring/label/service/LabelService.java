package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.version.service.VersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 6 — 라벨 CRUD 서비스.
 *
 * 보안 (Critical):
 *  - IDOR (CWE-639): WORKER 는 본인 배정 영상의 프레임에만 라벨 편집 가능 (LabelAccessGuard 위임).
 *  - REVIEWER 는 모든 프레임 접근 가능 (검수 책임).
 *  - 좌표 검증 (CWE-20): 음수 좌표 차단, polygon 최대 1000 점 (CWE-770 DoS 방어).
 *  - Mass Assignment (CWE-915): autoLblYn 은 요청 DTO 에서 무시 (정책: 자동 라벨 수정 시에도 'Y' 유지).
 *
 * Phase 8 에서 Gitea 자동 커밋 훅 추가 예정.
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
public class LabelService {

    /** CWE-770 DoS — 단일 라벨 좌표 점 최대 개수. */
    public static final int MAX_POINTS_PER_LABEL = 1000;

    private final LsDataLblRepository labelRepository;
    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;
    private final ObjectMapper objectMapper;
    /**
     * Phase 8 — Gitea 자동 커밋 훅.
     * label ⇄ version 순환 의존 (LabelService → VersionService, VersionService → LabelAccessGuard)
     * 해소를 위해 {@link Lazy} 적용 — 생성자 주입 유지 (보안 정책: 필드/세터 주입 금지).
     */
    private final VersionService versionService;

    public LabelService(LsDataLblRepository labelRepository,
                        LsDataSrcRepository srcRepository,
                        LabelAccessGuard accessGuard,
                        ObjectMapper objectMapper,
                        @Lazy VersionService versionService) {
        this.labelRepository = labelRepository;
        this.srcRepository = srcRepository;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
        this.versionService = versionService;
    }

    /**
     * 프레임 라벨 조회 — WORKER 는 본인 배정 프레임만, REVIEWER 는 모두.
     *
     * <p>응답에 영상(rawSn=videoId) 및 동일 영상의 형제 프레임 (siblings) 메타를 함께 반환.
     * N+1 회피: 권한 검사 시점에 LsDataSrc 1회 조회 + siblings 조회 1회 = SELECT 2회.
     */
    public LabelResponse getByFrame(Long srcSn, TokenClaims actor) {
        LsDataSrc current = accessGuard.verifyAndGet(srcSn, actor);
        List<LsDataLbl> labels = labelRepository.findBySrcSn(srcSn);
        List<LsDataSrc> siblings = srcRepository.findByRawSnOrderByFrameNoAsc(current.getRawSn());
        return LabelResponse.of(current, siblings, labels, objectMapper);
    }

    /**
     * 프레임 라벨 bulk upsert.
     *  - id == null : 신규 INSERT (AUTO_LBL_YN='N')
     *  - id != null : 기존 UPDATE (AUTO_LBL_YN 유지 — 자동 라벨이라도 'Y' 그대로)
     *  - 요청에 누락된 기존 라벨은 보존 (이번 Phase 정책 — 명시적 DELETE 엔드포인트 별도)
     */
    @Transactional("controlTransactionManager")
    public LabelResponse bulkUpsert(Long srcSn, LabelBulkUpsertRequest req, TokenClaims actor) {
        LsDataSrc current = accessGuard.verifyAndGet(srcSn, actor);
        Long actorNo = accessGuard.parseUserNo(actor.sub());

        // 좌표 사전 검증 (트랜잭션 내부에서 한꺼번에 실패해도 롤백 — 여기선 명시적으로 미리 차단)
        for (LabelItemDto item : req.items()) {
            validatePoints(item.points());
        }

        // 기존 라벨 인덱싱 (id 기반 수정용)
        List<LsDataLbl> existing = labelRepository.findBySrcSn(srcSn);
        Map<Long, LsDataLbl> idIndex = new HashMap<>();
        for (LsDataLbl e : existing) {
            idIndex.put(e.getLblSn(), e);
        }

        List<LsDataLbl> result = new ArrayList<>();
        for (LabelItemDto item : req.items()) {
            String pointsJson = LabelPointSerializer.toJson(toPoints(item.points()), objectMapper);
            if (item.id() != null && idIndex.containsKey(item.id())) {
                LsDataLbl found = idIndex.get(item.id());
                if (!found.getSrcSn().equals(srcSn)) {
                    // IDOR 추가 방어 — id 가 다른 프레임의 라벨이면 차단.
                    throw new CustomException(ErrorCode.FORBIDDEN, "다른 프레임의 라벨 ID 입니다.");
                }
                found.updateUserContent(item.lblTypeCd(), item.label(), pointsJson);
                result.add(found);
            } else {
                LsDataLbl created = labelRepository.save(
                        LsDataLbl.createManual(srcSn, item.lblTypeCd(), item.label(), pointsJson, actorNo));
                result.add(created);
            }
        }
        log.info("[Label] bulkUpsert srcSn={} actor={} count={}", srcSn, actorNo, result.size());

        List<LsDataSrc> siblings = srcRepository.findByRawSnOrderByFrameNoAsc(current.getRawSn());

        // Phase 8 — Gitea 자동 커밋 (PORTAL 채널은 버전관리 미제공 → skip).
        if (VersionService.isCommittable(actor)) {
            LabelResponse responseSnapshot = LabelResponse.of(current, siblings, result, objectMapper);
            String labelsJson;
            try {
                labelsJson = objectMapper.writeValueAsString(responseSnapshot);
            } catch (Exception e) {
                // 직렬화 실패는 심각한 내부 오류 — 라벨 저장 자체는 성공이므로 commit 만 skip + 경고.
                log.error("[Label] labels JSON serialize failed srcSn={}", srcSn, e);
                labelsJson = null;
            }
            if (labelsJson != null) {
                versionService.commit(srcSn, labelsJson, actor);
            }
        }

        return LabelResponse.of(current, siblings, result, objectMapper);
    }

    /** 좌표 검증 — 음수 차단 + 점 개수 상한. */
    private void validatePoints(List<List<Double>> points) {
        if (points == null || points.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "points 가 비어있습니다.");
        }
        if (points.size() > MAX_POINTS_PER_LABEL) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "라벨당 좌표 개수 초과 (최대 " + MAX_POINTS_PER_LABEL + " 점)");
        }
        for (List<Double> pair : points) {
            if (pair == null || pair.size() != 2) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
            }
            double x = pair.get(0);
            double y = pair.get(1);
            if (x < 0 || y < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "좌표는 0 이상이어야 합니다 (x=" + x + ", y=" + y + ")");
            }
        }
    }

    private static List<Point> toPoints(List<List<Double>> nested) {
        List<Point> out = new ArrayList<>(nested.size());
        for (List<Double> pair : nested) {
            out.add(new Point(pair.get(0), pair.get(1)));
        }
        return out;
    }
}
