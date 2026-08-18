package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.portal.dto.PortalDatamartDownloadResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 포털 데이터마트 작업 데이터 ZIP 다운로드의 <b>DB 단계</b>(게이트 판정 + 조회 + labels.json 직렬화).
 * @design API-203, AC-034, AC-035
 *
 * <h3>왜 서비스가 둘로 갈라져 있는가 (트랜잭션 경계)</h3>
 * <p>산출물에 영상이 들어가면 응답이 GB 급이 될 수 있다. 커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아가
 * 나므로(이 저장소의 프레임 이미지 서빙이 이미 같은 규약을 강제한다), <b>조회·인가·게이트는 이 빈의
 * {@code readOnly} 트랜잭션 안에서 값 레코드만</b> 만들고 경로 검증·파일 open·ZIP 스트리밍은
 * {@link PortalDatamartDownloadService} 가 <b>트랜잭션 밖</b>에서 수행한다. 두 책임을 한 빈에 두면
 * 자기호출(self-invocation)로 프록시가 우회돼 경계가 사라지므로 빈 자체를 분리한다.
 *
 * <h3>판정 순서는 고정이다 (오라클 누출 방지)</h3>
 * <p>③데이터마트 노출(403) → ④비식별 누락 신고(412) → ⑤본인 저장 라벨 0건(410) 순이며,
 * <b>③이 ④보다 먼저</b>다. 뒤집으면 데이터마트에 노출되지도 않은 영상의 <b>신고 상태가 응답으로
 * 새어나간다</b>(CWE-209). ①인증은 SecurityConfig({@code /v1/portal/**}) + 컨트롤러,
 * ②속도 제한은 컨트롤러가 담당한다.
 *
 * <h3>④는 {@code resolveDeidPath} 와 무관하게 독립 평가한다 (Critical)</h3>
 * <p>{@link VideoStreamService#resolveDeidPath}는 <b>신고 중({@code 'F'})</b>과 <b>비식별 이력 없음</b>을
 * 둘 다 {@code null} 로 돌려준다. 그래서 신고 판정을 그 호출 결과로 대신하면 신고 영상이 412 가 아니라
 * <b>"영상 없는 200 ZIP"</b> 으로 조용히 나가고 AC-034 와 API-203 의 412 규정이 동시에 깨진다.
 * 신고 판정은 반드시 그 호출 <b>이전에</b> 독립 게이트로 끝낸다.
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
@RequiredArgsConstructor
public class PortalDatamartDownloadTxService {

    /** 데이터마트 노출(검수 완료 APPROVED) 판정 <b>단일 원천</b> — 사설 복제 금지. */
    private final ReviewApprovalGate approvalGate;
    /** 비식별 누락 신고 구간 게이트 <b>단일 원천</b>({@code DeidentReportGate} 위임). */
    private final LabelAccessGuard accessGuard;
    /** 라벨 병합(본인 저장분 우선) 판정 <b>단일 원천</b>. */
    private final PortalLabelService portalLabelService;
    /** 비식별 영상 경로 조달 <b>단일 원천</b> — 적재값을 읽으며 파일명을 조합·추측하지 않는다. */
    private final VideoStreamService videoStreamService;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsPortalUserLabelRepository userLabelRepository;
    private final ObjectMapper objectMapper;

    /**
     * ZIP 을 구성하는 데 필요한 <b>값만</b> 담은 계획서 — 엔티티·영속성 컨텍스트를 트랜잭션 밖으로
     * 흘리지 않는다.
     *
     * @param rawSn        영상 PK(ZIP 최상위 디렉터리명)
     * @param labelsJson   {@code labels.json} 바이트
     * @param frames       프레임 엔트리(비식별 경로가 있는 것만)
     * @param deidVideoPath 비식별 <b>영상</b> 경로 — 없으면 {@code null}(원본 폴백 금지, AC-034)
     * @param rawFilePathNm 비식별 영상 허용 base 도출용 원본 경로(co-locate 축) — 파일 내용원이 아니다
     */
    public record DownloadPlan(
            long rawSn,
            byte[] labelsJson,
            List<FrameEntry> frames,
            String deidVideoPath,
            String rawFilePathNm
    ) {}

    /** 프레임 1건 — 파일명 기준값({@code FRM_NO})과 비식별 이미지 경로. */
    public record FrameEntry(long frameNo, String deidImagePath) {}

    /**
     * 게이트 3종을 순서대로 평가하고 ZIP 계획서를 만든다.
     *
     * @throws CustomException 403(미노출) / 412(신고 구간) / 410(본인 저장 라벨 0건)
     */
    public DownloadPlan plan(Long rawSn, TokenClaims actor) {
        if (actor == null || actor.sub() == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }

        // ③ 데이터마트 노출(검수 완료 APPROVED). 미존재 rawSn 도 동일하게 403 — 존재 여부 오라클 차단.
        if (!approvalGate.isApproved(rawSn)) {
            log.warn("[PortalDownload] denied — video not approved rawSn={}", rawSn);
            throw new CustomException(ErrorCode.FORBIDDEN, "데이터마트에 노출되지 않은 영상입니다.");
        }

        // ④ 비식별 누락 신고 구간이면 412. resolveDeidPath 호출 <b>이전</b>에 독립 평가한다(클래스 주석).
        accessGuard.requireNotUnderDeidentReport(rawSn);

        // ⑤ 본인 저장 라벨 0건이면 410 — 신규 미작업 또는 보존기간 만료 삭제.
        //    판정 축은 <b>행 존재</b>다(보존기간 삭제 배치의 대상 조건과 같은 사실을 가리켜야 한다 — AC-032).
        List<LsPortalUserLabel> myLabels =
                userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(actor.sub(), rawSn);
        if (myLabels.isEmpty()) {
            log.info("[PortalDownload] gone — no saved label rawSn={} user={}",
                    rawSn, LogSanitizer.sanitize(actor.sub()));
            throw new CustomException(ErrorCode.GONE, "다운로드할 작업 데이터가 없습니다.");
        }

        // ⑥ 본문 구성. 프레임 수만큼 쿼리를 반복하지 않도록 세 목록을 각 1회 조회 후 프레임별로 나눈다.
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        Map<Long, List<LsPortalUserLabel>> mineBySrc = groupMine(myLabels);
        Map<Long, List<LsDataLbl>> datamartBySrc = groupDatamart(lblRepository.findAllByRawSn(rawSn));

        List<PortalDatamartDownloadResponse.Frame> frameDtos = new ArrayList<>(frames.size());
        List<FrameEntry> frameEntries = new ArrayList<>(frames.size());
        for (LsDataSrc frame : frames) {
            Long srcSn = frame.getSrcSn();
            frameDtos.add(new PortalDatamartDownloadResponse.Frame(
                    srcSn, frame.getFrameNo(),
                    // 병합 규칙은 재유도하지 않는다 — 본인 저장분이 없을 때만 원본을 본다(Supplier 로 지연).
                    portalLabelService.mergeFrameItems(
                            mineBySrc.getOrDefault(srcSn, List.of()),
                            () -> datamartBySrc.getOrDefault(srcSn, List.of()))));

            // 비식별 프레임 경로만 싣는다 — 원본(SRC_FILE_PATH_NM) 폴백 금지(AC-034 불변 규칙).
            String deid = frame.getDeidFilePath();
            if (deid != null && !deid.isBlank() && frame.getFrameNo() != null && frame.getFrameNo() >= 0) {
                frameEntries.add(new FrameEntry(frame.getFrameNo(), deid));
            }
        }

        byte[] labelsJson = serialize(
                new PortalDatamartDownloadResponse(rawSn, frameDtos), rawSn);

        // 비식별 영상 — 없으면 null 그대로 둔다(ZIP 에 video.* 엔트리를 만들지 않는다).
        String deidVideoPath = videoStreamService.resolveDeidPath(rawSn);
        String rawFilePathNm = videoRepository.findById(rawSn)
                .map(LsDataRaw::getRawFilePathNm)
                .orElse(null);

        log.info("[PortalDownload] planned rawSn={} user={} frames={} images={} video={}",
                rawSn, LogSanitizer.sanitize(actor.sub()), frameDtos.size(), frameEntries.size(),
                deidVideoPath != null);
        return new DownloadPlan(rawSn, labelsJson, List.copyOf(frameEntries), deidVideoPath, rawFilePathNm);
    }

    /** 본인 저장 라벨을 프레임별로 나눈다(입력 순서 = 최신순 유지). */
    private Map<Long, List<LsPortalUserLabel>> groupMine(List<LsPortalUserLabel> myLabels) {
        Map<Long, List<LsPortalUserLabel>> map = new LinkedHashMap<>();
        for (LsPortalUserLabel l : myLabels) {
            if (l.getSrcDataSrcSn() == null) continue;
            map.computeIfAbsent(l.getSrcDataSrcSn(), k -> new ArrayList<>()).add(l);
        }
        return map;
    }

    /** 데이터마트 원본 라벨을 프레임별로 나눈다. */
    private Map<Long, List<LsDataLbl>> groupDatamart(List<LsDataLbl> labels) {
        Map<Long, List<LsDataLbl>> map = new LinkedHashMap<>();
        for (LsDataLbl l : labels) {
            if (l.getSrcSn() == null) continue;
            map.computeIfAbsent(l.getSrcSn(), k -> new ArrayList<>()).add(l);
        }
        return map;
    }

    private byte[] serialize(PortalDatamartDownloadResponse body, Long rawSn) {
        try {
            // 사용자가 내려받는 산출 파일이므로 pretty 로 쓴다(형제 export 경로와 동일 규약).
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        } catch (IOException e) {
            log.error("[PortalDownload] labels.json serialize failed rawSn={} causeType={}",
                    rawSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "다운로드 자료를 만들지 못했습니다.");
        }
    }
}
