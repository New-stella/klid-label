package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.controlnotify.dto.TaskCompletedPayload;
import kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import kr.co.cudo.authoring.observability.metrics.ControlNotifyMetrics;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngExLocalGov;
import kr.co.cudo.authoring.video.repository.MngExLocalGovRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 관제 통지 페이로드를 <b>DB 실측값</b>으로 조립하는 팩토리.
 *
 * <p>D-ISSUE-41 대응 — 상수(0/0/null) self-fill 을 금지하고 모든 값을 조회로 채운다. 조회 실패는
 * 0/빈값으로 대체하지 않고 예외를 던져 상위(ControlNotifyService)가 <b>폴백 큐로 보내게</b> 한다
 * (fail-closed).
 *
 * <h3>조달처</h3>
 * <table>
 *   <tr><th>필드</th><th>출처</th></tr>
 *   <tr><td>{@code job_id}</td><td>{@code LS_DATA_RAW.RAW_SN} 문자열 변환</td></tr>
 *   <tr><td>{@code event_type_cd}</td><td>{@code LS_DATA_RAW.EVNT_TYPE_CD} ({@link #toControlEventTypeCd} 1곳 매핑)</td></tr>
 *   <tr><td>{@code lclgv_cd}</td><td>{@code LS_DATA_RAW.LCLGV_CD}</td></tr>
 *   <tr><td>{@code lclgv_nm}</td><td>{@code MNG_EX_LOCAL_GOV.SIDO_NM + ' ' + SGG_NM} (미조인 시 null)</td></tr>
 *   <tr><td>{@code duration_sec}</td><td>{@code LS_DATA_RAW.VDO_LEN_SEC}</td></tr>
 *   <tr><td>{@code image_count}</td><td>{@code COUNT(LS_DATA_SRC WHERE RAW_SN=?)}</td></tr>
 * </table>
 *
 * <p><b>image_count 를 {@code LS_DATASET_EXPORT.FRAME_CNT} 로 조달하면 안 된다</b>(N-6): export 와
 * 통지가 같은 {@code ReviewApprovedEvent} 를 AFTER_COMMIT 소비하고 export 는 {@code @Async} 라
 * 통지 시점에 export 행이 아직 없다 — 항상 0/누락이 실린다.
 *
 * <p><b>알려진 한계 — image_count 와 실제 산출 파일 수의 불일치</b>: 위 구조적 제약(통지 시점에 export
 * 미완료) 때문에 {@code image_count} 는 <b>원천 프레임 행 수</b>({@code COUNT(LS_DATA_SRC)})다. 원천
 * 이미지가 없는 프레임은 export writer 가 skip 하므로(부분성공), 그런 프레임이 있으면 통지값이 실제
 * 산출 파일 수보다 클 수 있다. 관제는 파일 존재를 기준으로 소비해야 하며 이 값은 상한(기대치)으로만 쓴다.
 *
 * <p><b>{@code changed_items} 에는 같은 논리를 적용하지 않는다</b>(B-1): 개수가 아니라 <b>명시적 파일
 * 목록</b>이라 없는 파일명을 실으면 관제가 그대로 픽업해 404 를 맞는다. 따라서 원천 이미지 경로를
 * 어느 벌에서도 보유하지 않은 프레임은 목록에서 제외한다
 * ({@link LsDataSrcRepository#findExportableFrameNosByRawSn}).
 */
@Component
@ConditionalOnProperty(name = "authoring.control-notify.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ControlNotifyPayloadFactory {

    /** 관제 {@code datasets.lclgv_nm} 이 varchar(100) — 시도명+시군구명 조합이 넘칠 수 있어 절단한다. */
    static final int LCLGV_NM_MAX_LENGTH = 100;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final MngExLocalGovRepository localGovRepository;
    private final ControlNotifyMetrics metrics;

    /**
     * 완료 통지 페이로드 — 전 필드 DB 실측.
     *
     * @throws IllegalStateException 영상 행이 없을 때(fail-closed — 폴백 큐로 유도)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public TaskCompletedPayload buildCompleted(Long rawSn) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new IllegalStateException("통지 대상 영상을 찾을 수 없습니다 rawSn=" + rawSn));

        long imageCount = srcRepository.countByRawSn(rawSn);

        return new TaskCompletedPayload(
                toJobId(rawSn),
                toControlEventTypeCd(raw.getEvntTypeCd()),
                raw.getLclgvCd(),
                resolveLocalGovName(raw.getLclgvCd()),
                raw.getDurationSec(),
                Math.toIntExact(imageCount));
    }

    /**
     * 수정 통지 페이로드 — 변경 프레임의 <b>파일명</b> 리스트.
     *
     * <p>라벨/메타 변경은 산출 JSON 만 바뀌므로 {@code jsons} 에만 싣는다. 프레임 이미지 자체가
     * 재생성되는 경로(재승인 전량 재산출)는 {@link #buildModifiedForAllFrames} 가 담당한다.
     *
     * @param rawSn        영상 PK
     * @param changedSrcSns 변경 프레임 SRC_SN 집합 (영상 단위 변경만 있으면 비어 있을 수 있다)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public TaskModifiedPayload buildModified(Long rawSn, Collection<Long> changedSrcSns) {
        List<Long> frameNos = resolveFrameNos(rawSn, changedSrcSns);
        List<String> jsons = frameNos.stream().map(ExportFileNaming::jsonFileName).toList();
        // 영상 단위 메타만 바뀐 경우 changed_items 는 비지만 통지 자체는 발송한다(D-ISSUE-43).
        return new TaskModifiedPayload(toJobId(rawSn),
                new TaskModifiedPayload.ChangedItems(List.of(), jsons));
    }

    /**
     * 재승인(이미 완료 통지된 job 의 재완료) 수정 통지 — 새 버전 폴더가 전량 재산출되므로
     * 영상의 모든 프레임 이미지·JSON 을 변경 항목으로 싣는다.
     *
     * <p><b>단, 실제로 산출될 수 있는 프레임만 싣는다</b>(B-1) — 원천 이미지 경로를 어느 벌에서도
     * 보유하지 않은 프레임은 writer 가 건너뛰므로 파일이 만들어지지 않는다. 필터 기준·근거는
     * {@link LsDataSrcRepository#findExportableFrameNosByRawSn} 참조.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public TaskModifiedPayload buildModifiedForAllFrames(Long rawSn) {
        List<Long> frameNos = srcRepository.findExportableFrameNosByRawSn(rawSn);
        return new TaskModifiedPayload(toJobId(rawSn), new TaskModifiedPayload.ChangedItems(
                frameNos.stream().map(ExportFileNaming::imageFileName).toList(),
                frameNos.stream().map(ExportFileNaming::jsonFileName).toList()));
    }

    /** 작업 ID — 관제 계약상 문자열(A-5). */
    public static String toJobId(Long rawSn) {
        return String.valueOf(rawSn);
    }

    /**
     * 저작도구 이벤트 유형 코드 → 관제 {@code event_type_cd} 매핑 <b>단일 지점</b>.
     *
     * <p>관제 8대 이벤트 코드값 목록이 아직 공유되지 않아(질의 C-5) 현재는 보유값을 그대로 싣는다.
     * 목록 수령 시 이 메서드 한 곳만 교체하면 전 경로에 반영된다 — 호출부에 매핑을 흩뿌리지 말 것.
     */
    private String toControlEventTypeCd(String authoringEventTypeCd) {
        return authoringEventTypeCd;
    }

    /**
     * 지자체명 조회 — 시도명 + 시군구명. 마스터에 없으면 null(값을 지어내지 않는다).
     * 관제 컬럼이 varchar(100) 이라 초과분은 절단한다(C-4).
     *
     * <p><b>활성({@code USE_YN='Y'}) 행만 사용한다</b> — 폐지된 지자체 코드의 이름을 그대로 실어 보내면
     * 관제가 폐지 명칭으로 데이터셋을 등록한다. 비활성 행은 이름을 지어내지 않고 null 로 둔다.
     */
    private String resolveLocalGovName(String lclgvCd) {
        if (lclgvCd == null || lclgvCd.isBlank()) {
            return null;
        }
        Optional<MngExLocalGov> found = localGovRepository.findById(lclgvCd);
        if (found.isEmpty()) {
            log.warn("[ControlNotify] local gov not found lclgvCd={}", lclgvCd);
            return null;
        }
        MngExLocalGov gov = found.get();
        if (!"Y".equalsIgnoreCase(gov.getUseYn())) {
            log.warn("[ControlNotify] local gov inactive lclgvCd={}", lclgvCd);
            return null;
        }
        String composed = String.join(" ",
                        gov.getSidoNm() == null ? "" : gov.getSidoNm(),
                        gov.getSggNm() == null ? "" : gov.getSggNm())
                .trim();
        if (composed.isEmpty()) {
            return null;
        }
        return composed.length() <= LCLGV_NM_MAX_LENGTH
                ? composed
                : composed.substring(0, LCLGV_NM_MAX_LENGTH);
    }

    /**
     * SRC_SN → FRM_NO 변환. 조회되지 않은 srcSn 은 <b>사일런트 드롭하지 않고</b> 경고 로그 +
     * 메트릭을 남기고 나머지는 정상 전송한다(S10).
     *
     * <p>미해석 사유는 두 가지다 — ①해당 rawSn 에 없는 srcSn ②원천 이미지 경로를 어느 벌에서도
     * 보유하지 않아 export 산출물이 없는 프레임(B-1). 두 경우 모두 파일명을 실으면 관제가 없는
     * 파일을 픽업하므로 제외하고 관측만 남긴다.
     */
    private List<Long> resolveFrameNos(Long rawSn, Collection<Long> changedSrcSns) {
        if (changedSrcSns == null || changedSrcSns.isEmpty()) {
            return List.of();
        }
        Set<Long> requested = new LinkedHashSet<>(changedSrcSns);
        requested.remove(null);
        if (requested.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> frameNoBySrcSn = new LinkedHashMap<>();
        for (Object[] row : srcRepository.findExportableFrameNoByRawSnAndSrcSnIn(rawSn, requested)) {
            frameNoBySrcSn.put((Long) row[0], (Long) row[1]);
        }

        List<Long> resolved = new ArrayList<>(requested.size());
        int unresolved = 0;
        for (Long srcSn : requested) {
            Long frameNo = frameNoBySrcSn.get(srcSn);
            if (frameNo == null) {
                unresolved++;
                continue;
            }
            resolved.add(frameNo);
        }
        if (unresolved > 0) {
            // 식별자만 출력 — 라벨/경로 등 본문은 남기지 않는다(CWE-359).
            log.warn("[ControlNotify] frame not resolved for changed items rawSn={} unresolved={} requested={}",
                    rawSn, unresolved, requested.size());
            metrics.incrementUnresolvedFrame(unresolved);
        }
        return resolved;
    }
}
