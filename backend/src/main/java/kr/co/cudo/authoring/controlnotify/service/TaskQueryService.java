package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.dto.TaskLabelsResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskMetaResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskSummaryResponse;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관제서버 조회 API 비즈니스 로직.
 *
 * <p>조회 전용 서비스 — 상태 변경 없음.
 * <ul>
 *   <li>CWE-89: 모든 쿼리는 JPA 파라미터 바인딩만 사용.</li>
 *   <li>CWE-770 (D-ISSUE-45): 요약은 전량 적재 없이 COUNT 집계로, 라벨 목록은 <b>페이징</b>으로
 *       반환한다. 페이지 크기는 {@link #MAX_PAGE_SIZE} 로 클램프한다.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class TaskQueryService {

    /** 페이지 크기 상한 (rules/api-design.md — 기본 20, 최대 100). */
    public static final int MAX_PAGE_SIZE = 100;

    /** frameIds 필터 최대 길이 — 무제한 IN 절 방지(CWE-770). */
    public static final int MAX_FRAME_ID_FILTER = 100;

    /** 페이징 미지정 시 기본 페이지 크기. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsDataMetaRepository metaRepository;
    private final LsTaskEventLogRepository taskEventLogRepository;
    private final UserRepository userRepository;

    /**
     * 영상별 요약: 프레임 수, 라벨 수(전체/라벨 보유 프레임 수), 메타 수, 상태, 검수자, 최종 수정일.
     *
     * <p>전부 COUNT 집계 — 프레임/라벨 엔티티를 메모리에 적재하지 않는다(D-ISSUE-45).
     */
    public TaskSummaryResponse getSummary(Long rawSn) {
        LsDataRaw raw = findRawOrThrow(rawSn);

        long totalFrames = srcRepository.countByRawSn(rawSn);
        long labeledFrames = lblRepository.countLabeledFramesByRawSn(rawSn);
        long totalLabels = lblRepository.countByRawSn(rawSn);
        long totalMeta = metaRepository.countByRawSn(rawSn);

        // lastModifiedAt: mdfcnDt 우선, 없으면 regDt
        var lastModified = raw.getMdfcnDt() != null ? raw.getMdfcnDt() : raw.getRegDt();
        var lastModifiedInstant = lastModified != null
                ? lastModified.atZone(ZoneId.systemDefault()).toInstant()
                : null;

        return new TaskSummaryResponse(
                raw.getRawSn(),
                raw.getDataSttsCd(),
                totalFrames,
                labeledFrames,
                totalLabels,
                totalMeta,
                resolveReviewerName(rawSn),
                lastModifiedInstant
        );
    }

    /**
     * 영상별 라벨 목록 (프레임 단위 페이징 + 선택적 프레임 필터).
     *
     * @param rawSn    영상 PK
     * @param frameIds 필터할 프레임 srcSn 목록. null/빈 리스트이면 전체 프레임 대상.
     * @param pageable 프레임 단위 페이징 (size 는 {@link #MAX_PAGE_SIZE} 로 클램프)
     */
    public Page<TaskLabelsResponse> getLabels(Long rawSn, List<Long> frameIds, Pageable pageable) {
        findRawOrThrow(rawSn);

        Pageable capped = capped(pageable);
        // B-2: frameIds 는 페이징 <b>전에</b> 쿼리 조건으로 내린다. 페이지를 먼저 자르고 메모리에서
        // 거르면 지정 프레임이 첫 페이지 밖일 때 빈 결과가 나오고 totalElements 와도 모순된다.
        Set<Long> filter = normalizeFrameIds(frameIds);
        Page<LsDataSrc> framePage = filter.isEmpty()
                ? srcRepository.findByRawSnOrderByFrameNoAsc(rawSn, capped)
                : srcRepository.findByRawSnAndSrcSnInOrderByFrameNoAsc(rawSn, filter, capped);
        List<LsDataSrc> frames = framePage.getContent();
        long total = framePage.getTotalElements();

        // srcSn 목록으로 라벨 일괄 조회 (N+1 방지) — 조회 범위는 현재 페이지 프레임으로 한정.
        Set<Long> srcSns = frames.stream()
                .map(LsDataSrc::getSrcSn)
                .collect(Collectors.toSet());

        List<LsDataLbl> labels = srcSns.isEmpty()
                ? List.of()
                : lblRepository.findBySrcSnIn(srcSns);

        Map<Long, List<LsDataLbl>> bySrc = labels.stream()
                .collect(Collectors.groupingBy(LsDataLbl::getSrcSn));

        List<TaskLabelsResponse> content = frames.stream()
                .map(f -> toLabelsResponse(f, bySrc.getOrDefault(f.getSrcSn(), List.of())))
                .toList();

        return new PageImpl<>(content, capped, total);
    }

    /**
     * 영상별 메타데이터 (페이징).
     *
     * <p>B-4 / CWE-770: 전량 적재 금지. VLM 콜백 1회당 최대 500 세그먼트 · {@code META_VL} 최대 2000자라
     * 누적 시 단일 응답이 수 MB 가 된다 — 라벨 조회와 동일하게 기본 20 / 최대 100 으로 페이징한다.
     *
     * @param pageable 메타 단위 페이징 (size 는 {@link #MAX_PAGE_SIZE} 로 클램프)
     */
    public TaskMetaResponse getMeta(Long rawSn, Pageable pageable) {
        findRawOrThrow(rawSn);

        Pageable capped = cappedMeta(pageable);
        Page<LsDataMeta> metaPage = metaRepository.findByRawSn(rawSn, capped);

        List<TaskMetaResponse.MetaItem> items = metaPage.getContent().stream()
                .map(m -> new TaskMetaResponse.MetaItem(
                        m.getMetaSn(),
                        m.getMetaKey(),
                        m.getMetaVl()
                ))
                .toList();

        return new TaskMetaResponse(
                rawSn,
                items,
                capped.getPageNumber(),
                capped.getPageSize(),
                metaPage.getTotalElements(),
                metaPage.getTotalPages());
    }

    // ==================== Private Helpers ====================

    /**
     * frameIds 정규화 — null 제거 + 중복 제거 + 서비스 레벨 상한 검증.
     *
     * <p>컨트롤러 {@code @Size(max=100)} 에만 의존하지 않는다 — 다른 호출부(배치·내부 재사용)가 붙어도
     * 무제한 IN 절이 만들어지지 않도록 서비스에서도 방어한다(CWE-770).
     */
    private Set<Long> normalizeFrameIds(List<Long> frameIds) {
        if (frameIds == null || frameIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> filter = new HashSet<>(frameIds);
        filter.remove(null);
        if (filter.size() > MAX_FRAME_ID_FILTER) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "frameIds 는 최대 " + MAX_FRAME_ID_FILTER + "개까지 지정할 수 있습니다.");
        }
        return filter;
    }

    /** 메타 페이지 크기 클램프 — 정렬은 PK(metaSn) 오름차순 고정으로 페이지 간 안정성을 보장한다. */
    private Pageable cappedMeta(Pageable pageable) {
        Sort byMetaSn = Sort.by(Sort.Order.asc("metaSn"));
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, byMetaSn);
        }
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, byMetaSn);
    }

    /** 페이지 크기 클램프 — 요청이 10000 을 넣어도 100 을 넘지 않는다(CWE-770). */
    private Pageable capped(Pageable pageable) {
        Sort byFrameNo = Sort.by(Sort.Order.asc("frameNo"));
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, byFrameNo);
        }
        int size = Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        return PageRequest.of(pageable.getPageNumber(), size, byFrameNo);
    }

    /**
     * 마지막 승인 이벤트의 검수자명 실측 조회(D-ISSUE-41 — null 하드코딩 제거).
     * 승인 이력이 없거나 사용자 마스터에 없으면 값을 지어내지 않고 null 을 반환한다.
     */
    private String resolveReviewerName(Long rawSn) {
        return taskEventLogRepository
                .findFirstByRawDataIdAndEventTypeCdOrderByOcrnDtDescEventSeqDesc(
                        rawSn, LsTaskEventLog.EVENT_APPROVE)
                .map(LsTaskEventLog::getActorUserNo)
                .flatMap(userRepository::findByUserNo)
                .map(LsAcntUser::getUserNm)
                .orElse(null);
    }

    private LsDataRaw findRawOrThrow(Long rawSn) {
        return videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
    }

    /**
     * 프레임 + 라벨 목록을 응답 DTO로 변환.
     * <p>CWE-359 Privacy: 파일 경로 필드(filePath, deIdntfSrcFilePath) 미포함 — record 구조로 강제.
     */
    private TaskLabelsResponse toLabelsResponse(LsDataSrc frame, List<LsDataLbl> labels) {
        List<TaskLabelsResponse.LabelItem> items = labels.stream()
                .map(l -> new TaskLabelsResponse.LabelItem(
                        l.getLblSn(),
                        l.getLblTypeCd(),
                        l.getLabelNm(),
                        l.getPointCn()
                ))
                .toList();

        return new TaskLabelsResponse(frame.getSrcSn(), Math.toIntExact(frame.getFrameNo()), items);
    }
}
