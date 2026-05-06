package kr.co.cudo.authoring.export.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.export.entity.LsDataSet;
import kr.co.cudo.authoring.export.repository.LsDataSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 10 — 학습데이터셋 내보내기 실행기.
 *
 * <p>책임:
 * <ol>
 *   <li>LS_DATA_SET 의 status 를 IN_PROGRESS 로 갱신</li>
 *   <li>프로젝트의 ACCEPTED 라벨 + 프레임 조회 (LS_PJT_DATA_STTS=APPROVED 인 RAW_SN 만)</li>
 *   <li>format 에 따라 YOLO/COCO 직렬화 후 NAS 에 atomic write</li>
 *   <li>성공: status=COMPLETED + nasPath 갱신 / 실패: status=FAILED + errorMessage</li>
 * </ol>
 *
 * <p>본 클래스는 Quartz 의존성 없음 — Quartz Job 이 본 빈을 호출하는 wrapper 역할.
 * 단위 테스트는 본 빈을 직접 호출.
 *
 * <p>보안:
 * <ul>
 *   <li>CWE-22 (Path Manipulation): nasPath 는 사용자 입력 X, exportSn 기반으로 생성</li>
 *   <li>CWE-770 (Resource Exhaustion): 라벨 페이징 — 본 V1 은 한 프로젝트 단위 일괄 로드,
 *       프레임 수가 매우 크면 후속 phase 에서 Page 처리 필요</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportRunner {

    private final LsDataSetRepository exportRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final ApprovedFrameLookup approvedFrameLookup;
    private final NasStorageWriter nasWriter;
    private final YoloExportWriter yoloWriter;
    private final CocoExportWriter cocoWriter;
    private final ObjectMapper objectMapper;

    /**
     * Quartz Job 또는 단위 테스트가 호출. 트랜잭션 단위는 본 메서드.
     */
    @Transactional("controlTransactionManager")
    public void run(Long exportSn) {
        LsDataSet job = exportRepository.findById(exportSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "export 작업을 찾을 수 없습니다: " + exportSn));

        try {
            job.markInProgress();

            // 1. 프로젝트의 ACCEPTED RAW_SN 목록 (검수 승인된 영상만)
            List<Long> approvedRawSns = approvedFrameLookup.findApprovedRawSns(job.getPjtId());
            log.info("[Export] start exportSn={} pjtId={} format={} approvedRaws={}",
                    exportSn, job.getPjtId(), job.getExportFormat(), approvedRawSns.size());

            // 2. 프레임 + 라벨 수집 (ACCEPTED 라벨만 대상은 정책상 LS_PJT_DATA_STTS=APPROVED 영상의 라벨 전체)
            List<FramePayload> frames = collectFrames(approvedRawSns);

            // 3. 포맷별 직렬화 → NAS 저장
            String relativePath = "export/" + exportSn + "/" + job.getExportFormat().toLowerCase()
                    + (LsDataSet.FORMAT_YOLO.equals(job.getExportFormat()) ? ".txt" : ".json");
            String content;
            if (LsDataSet.FORMAT_YOLO.equals(job.getExportFormat())) {
                content = yoloWriter.write(frames);
            } else {
                content = cocoWriter.write(frames, objectMapper);
            }

            Path written = nasWriter.writeText(relativePath, content);
            job.markCompleted(written.toString());
            log.info("[Export] completed exportSn={} nasPath={}", exportSn, written);
        } catch (RuntimeException e) {
            log.error("[Export] failed exportSn={} err={}", exportSn, e.getMessage(), e);
            job.markFailed(e.getMessage());
            // 트랜잭션은 status FAILED 갱신을 위해 commit. 예외는 다시 throw 하지 않음.
        }
    }

    private List<FramePayload> collectFrames(List<Long> rawSns) {
        List<FramePayload> result = new ArrayList<>();
        // category 매핑 — 라벨명 → 0-based id (출현 순서)
        Map<String, Integer> categoryMap = new LinkedHashMap<>();
        for (Long rawSn : rawSns) {
            List<LsDataSrc> srcs = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
            for (LsDataSrc src : srcs) {
                List<LsDataLbl> labels = labelRepository.findBySrcSn(src.getSrcSn());
                if (labels.isEmpty()) {
                    continue;
                }
                List<LabelPayload> payloads = new ArrayList<>(labels.size());
                for (LsDataLbl lbl : labels) {
                    int catIdx = categoryMap.computeIfAbsent(lbl.getLabel(), k -> categoryMap.size());
                    List<Point> pts;
                    try {
                        pts = LabelPointSerializer.fromJson(lbl.getPointsJson(), objectMapper);
                    } catch (RuntimeException e) {
                        log.warn("[Export] label points parse failed lblSn={} err={}", lbl.getLblSn(), e.getMessage());
                        continue;
                    }
                    if (pts.isEmpty()) {
                        continue;
                    }
                    payloads.add(new LabelPayload(lbl.getLblSn(), lbl.getLabel(), catIdx, pts));
                }
                if (!payloads.isEmpty()) {
                    result.add(new FramePayload(src, payloads));
                }
            }
        }
        return result;
    }

    /**
     * 프레임 + 라벨 묶음 (Writer 인터페이스용).
     */
    public record FramePayload(LsDataSrc src, List<LabelPayload> labels) {
    }

    public record LabelPayload(Long lblSn, String labelName, int categoryIndex, List<Point> points) {
    }
}
