package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 내부 업로드 인입 행의 <b>종결 판정 단일 통로</b> (DEV_FIX H2-b / M2).
 *
 * <h3>왜 별도 빈인가 — "회수"와 "행 종결"은 한 짝이다</h3>
 * <p>인입 행이 세션 생성 시점에 만들어지므로, 업로드가 끝나지 못한 행(취소·만료)은 누군가 종결해야
 * 한다. 그런데 종결 조건을 <b>호출처마다</b> 판단하면 반드시 갈라진다 — 실제로 취소 경로에만 배선돼
 * 있었고 TTL 만료 정리(= 브라우저를 닫고 떠나는 <b>TUS 의 주 시나리오</b>)에는 없었다.
 *
 * <h3>판정 기준: 파일 실재가 세션 플래그보다 신뢰도 높은 진실원이다</h3>
 * <p>인입 행이 가리키는 경로에 <b>파일이 이미 있으면 종결하지 않는다.</b> 그 파일은 매직바이트·
 * ffprobe 검증을 통과한 뒤 원자 rename 으로만 그 자리에 놓이므로, 실재한다는 것은 곧 <b>완성된
 * 업로드가 도착했다</b>는 뜻이다. 여기서 행만 죽이면 <b>행은 죽고 파일은 남는</b> 상태가 되고, 그
 * 파일은 <b>아무도 지우지 않는 비식별 전 원본</b>(PII)이 된다.
 *
 * <p>반대로 파일이 없으면 그 행은 영영 도착하지 않을 대기분이므로 즉시 사유를 남기고 종결한다
 * (방치하면 미도착 대기 상한(기본 24시간) 동안 매 주기 폴링 후보로 남는다).
 *
 * <p>세션 상태(취소·만료 플래그)는 <b>보조 신호</b>일 뿐이다 — 완료 트랜잭션이 파일 이동 뒤 롤백되면
 * 세션은 {@code IN_PROGRESS} 인데 파일은 도착해 있다.
 */
@Slf4j
@Component
public class InternalUploadIngestTerminator {

    /** 종결 시도 결과 — 호출자가 로그·후속 보상을 결정할 수 있게 사유를 구분해 돌려준다. */
    public enum Outcome {
        /** 사유를 남기고 {@code FAILED} 로 종결했다. */
        TERMINATED,
        /** 파일이 이미 도착해 있어 종결하지 않았다(정상 적재 대기분). */
        KEPT_FILE_ARRIVED,
        /** 인입 행을 찾지 못했다(클립 ID 미보유 세션 포함). */
        ROW_NOT_FOUND,
        /** 그 사이 폴링이 집었거나 이미 종결됐다({@code PENDING} 아님). */
        NOT_PENDING,
        /** 종결 UPDATE 자체가 실패했다(best-effort — 미도착 대기 상한이 대신 종결시킨다). */
        ERROR
    }

    private final LsDataIngestRepository ingestRepository;
    /** 도착 판정(경로 검증 포함)의 단일 통로 — {@link InternalUploadPathResolver#arrivedRegularFile}. */
    private final InternalUploadPathResolver pathResolver;

    public InternalUploadIngestTerminator(LsDataIngestRepository ingestRepository,
                                          InternalUploadPathResolver pathResolver) {
        this.ingestRepository = ingestRepository;
        this.pathResolver = pathResolver;
    }

    /**
     * 인입 행을 사유와 함께 종결한다 — <b>파일이 아직 도착하지 않은 경우에만</b>.
     *
     * <p>실패해도 예외를 던지지 않는다(호출 흐름은 취소 204 / 만료 정리다). 종결이 안 되면 그 행은
     * 미도착 대기 상한이 대신 종결시키므로 영구 좀비가 되지는 않는다(늦어질 뿐이다).
     *
     * @param vmsClipId 세션이 보유한 클립 ID({@code VMS_CLIP_ID} 는 UK 라 최대 1행)
     * @param reason    종결 사유 — 절대경로·PII 미포함 요약(CWE-359)
     * @param logKey    로그 식별자(uploadId 등)
     */
    public Outcome terminateIfFileAbsent(String vmsClipId, String reason, Object logKey) {
        if (!StringUtils.hasText(vmsClipId)) {
            return Outcome.ROW_NOT_FOUND;
        }
        LsDataIngest row = ingestRepository.findByVmsClipId(vmsClipId).orElse(null);
        if (row == null) {
            log.warn("[Tus] ingest row not found for termination uploadId={}", logKey);
            return Outcome.ROW_NOT_FOUND;
        }
        if (fileArrived(row.getRawFilePathNm())) {
            // ★행만 죽이면 그 파일은 참조자 없는 비식별 전 원본으로 남는다(H2-b).
            log.warn("[Tus] ingest row kept — 파일이 이미 인입 영역에 도착해 있다(정상 적재 대기)"
                    + " uploadId={} rcptnSn={}", logKey, row.getRcptnSn());
            return Outcome.KEPT_FILE_ARRIVED;
        }
        try {
            int rows = ingestRepository.terminatePendingUpload(
                    row.getRcptnSn(), reason, java.time.LocalDateTime.now());
            log.info("[Tus] ingest row terminated uploadId={} rcptnSn={} rows={}",
                    logKey, row.getRcptnSn(), rows);
            return rows == 1 ? Outcome.TERMINATED : Outcome.NOT_PENDING;
        } catch (RuntimeException e) {
            log.warn("[Tus] ingest row terminate failed uploadId={} rcptnSn={} causeType={}",
                    logKey, row.getRcptnSn(), e.getClass().getSimpleName());
            return Outcome.ERROR;
        }
    }

    /**
     * 그 경로에 파일이 <b>실제로</b> 있는가 — 종결 판정의 진실원.
     *
     * <p>여기서 알고 싶은 것은 "우리가 이 행을 죽여도 고아 파일이 생기지 않는가" 하나다 —
     * 0바이트 잔여물이라도 <b>파일이 있으면</b> 종결 대신 그대로 두고 로그로 드러내는 편이 낫다
     * (종결하면 그 잔여물을 아무도 회수하지 않는다).
     *
     * <p><b>DEV_FIX 2차 [D] — {@code Files.exists} 단독 판정 폐기.</b> 구 구현은 존재 여부만 봐서
     * <b>임의 대상 심링크</b>에 속았다: 그 경로가 허용 루트 밖 아무 파일이나 가리켜도 "도착"이 되어
     * ①행 종결이 보류되고 ②그 clipId 되살리기가 막혔다(가용성 방해). 판정은 적재 측
     * {@code TrainingVideoIngestTx#verifyPath} 와 같은 규약을 쓰는
     * {@link InternalUploadPathResolver#arrivedRegularFile}(allowlist 재판정 + 실경로 + 일반 파일)에
     * 위임한다 — 판정을 호출처마다 재구현하면 반드시 갈라진다.
     */
    public boolean fileArrived(String rawFilePathNm) {
        if (!StringUtils.hasText(rawFilePathNm)) {
            return false;
        }
        return pathResolver.arrivedRegularFile(rawFilePathNm);
    }
}
