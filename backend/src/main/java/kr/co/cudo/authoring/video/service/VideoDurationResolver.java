package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;

/**
 * 영상 재생 길이(초) 해석기 — 자동 마킹 실패 결함(FIX A) 수정용.
 *
 * <p><b>배경:</b> 자동 마킹({@code MarkingService})은 {@code durationSec} 이 있어야 프레임 간격 marks 를
 * 산출한다. 그런데 관제 학습용 적재({@code TrainingVideoIngest})가 {@code LS_DATA_RAW.VDO_LEN_SEC}(=
 * {@code durationSec})를 채우지 못한 영상은 이 값이 null 이 되어 <b>자동 마킹만 INVALID_INPUT 으로 실패</b>
 * 하고(수동 마킹은 durationSec 을 쓰지 않아 정상), "자동만 안 됨" 증상이 발생했다. 마킹 시점에 영상 길이를
 * 다단 폴백으로 해석해 이 실패를 제거한다.
 *
 * <p><b>해석 우선순위 (재프로브 회피 우선):</b>
 * <ol>
 *   <li>{@code LS_DATA_RAW.VDO_LEN_SEC}(durationSec) 가 존재하고 &gt;0 이면 그 값.</li>
 *   <li>적재 시 ffprobe 로 이미 적재된 {@code LS_DATA_META.video.duration_ms}(=
 *       {@link VideoMetaService#KEY_DURATION_MS}) 를 조회해 초로 환산(대개 여기서 해결 → 재프로브 회피).</li>
 *   <li>그래도 없으면 원본 파일을 직접 {@link VideoProbe#probe(Path)} 프로브. 비식별본과 원본은 재생
 *       길이가 동일하므로 원본 경로 프로브로 충분하다.</li>
 *   <li>전부 실패 시 {@code null} — 호출자(AUTO backstop)가 명확한 메시지로 거부한다.</li>
 * </ol>
 *
 * <p><b>무결성(fail-safe):</b> {@link VideoFpsResolver} 와 동일하게 예외를 던지지 않는다. 프로브 실패/
 * 예외는 삼켜 {@code null} 을 반환해 마킹 자체를 깨뜨리지 않는다. 되쓰기(persist) 하지 않으며(스코프 최소화),
 * 이번 트랜잭션에서 transient 하게 해석해 사용만 한다.
 *
 * <p><b>ffprobe 커넥션 점유(MEDIUM-1, 문서화된 한정):</b> 3단계 폴백의 마지막(직접 프로브)은 ffprobe
 * 서브프로세스를 실행한다. 본 리졸버는 호출자({@code MarkingService.create})의 <b>쓰기 트랜잭션</b>에
 * join 하므로, 프로브 경로가 실행되면 프로브가 끝날 때까지 DB 커넥션을 점유한다. 완전한 트랜잭션-외
 * 격리는 {@code @Transactional} 통합 테스트의 시드 가시성 계약을 깨뜨려 채택하지 않았고, 대신 리스크를
 * 다음으로 한정한다: ① 프로브는 값싼 1·2단계(엔티티 필드·메타 DB read)가 <b>모두 실패</b>한 드문
 * 코호트에서만 실행되고, ② {@link VideoProbe} 구현({@code BrampVideoProbe})의 <b>30초 하드 타임아웃</b>으로
 * 점유 시간이 상한된다(무한 블로킹 없음). 적재 파이프라인이 durationSec/메타를 채우면 프로브는 타지 않는다.
 *
 * <p><b>보안(CWE-209):</b> 로그에 원본 경로/PII 원문을 직접 출력하지 않는다({@code BrampVideoProbe} 는
 * 경로를 hash 마스킹하며, 본 리졸버는 경로를 로그에 찍지 않고 rawSn·예외 클래스명만 남긴다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoDurationResolver {

    private final LsDataMetaRepository metaRepository;
    private final VideoProbe videoProbe;

    /**
     * rawSn 영상의 재생 길이(초)를 다단 폴백으로 해석한다.
     *
     * @param rawSn 영상 PK (LS_DATA_RAW.RAW_SN). null 이면 raw.durationSec 만 시도.
     * @param raw   영상 엔티티(durationSec·원본 경로 소스). null 이면 메타 조회만 시도.
     * @return 해석된 재생 길이(초, ≥1). 어떤 경로로도 해석 실패 시 {@code null}.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Integer resolveDurationSec(Long rawSn, LsDataRaw raw) {
        // 1. 적재 시 기록된 VDO_LEN_SEC 최우선 (기존 동작 보존 — 무회귀).
        if (raw != null && raw.getDurationSec() != null && raw.getDurationSec() > 0) {
            return raw.getDurationSec();
        }
        // 2. 적재 시 ffprobe 로 이미 적재된 video.duration_ms 메타 (재프로브 회피).
        if (rawSn != null) {
            Integer fromMeta = metaRepository.findByRawSnAndMetaKey(rawSn, VideoMetaService.KEY_DURATION_MS)
                    .map(LsDataMeta::getMetaVl)
                    .map(this::msStringToSecondsOrNull)
                    .orElse(null);
            if (fromMeta != null) {
                return fromMeta;
            }
        }
        // 3. 직접 프로브 (원본 경로) — 비식별본과 원본은 재생 길이가 동일하므로 원본 프로브로 충분.
        Integer fromProbe = probeDurationSec(rawSn, raw);
        if (fromProbe != null) {
            return fromProbe;
        }
        // 4. 전부 실패 → null (AUTO backstop 이 명확한 메시지로 거부).
        return null;
    }

    /**
     * 원본 파일을 직접 프로브해 재생 길이(초)를 얻는다. 실패/예외는 삼켜 {@code null} 반환(마킹 비파괴).
     * 경로/PII 는 로그에 미노출(CWE-209) — rawSn·예외 클래스명만 기록.
     */
    private Integer probeDurationSec(Long rawSn, LsDataRaw raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.getRawFilePathNm();
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            VideoMeta meta = videoProbe.probe(Path.of(path));
            if (meta == null || meta.durationMs() == null) {
                return null;
            }
            return msToSecondsOrNull(meta.durationMs());
        } catch (RuntimeException e) {
            log.warn("[VideoDuration] probe failed rawSn={} err={}", rawSn, e.getClass().getSimpleName());
            return null;
        }
    }

    /** ms 문자열(video.duration_ms 저장값)을 초로 환산. blank/파싱불가/&lt;1초는 null. */
    private Integer msStringToSecondsOrNull(String storedMs) {
        if (storedMs == null || storedMs.isBlank()) {
            return null;
        }
        try {
            return msToSecondsOrNull(Long.parseLong(storedMs.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ms(Long)를 초로 반올림 환산. 1초 미만(0 이하 포함)은 null(자동 마킹 불가 신호). */
    private Integer msToSecondsOrNull(Long ms) {
        if (ms == null) {
            return null;
        }
        long sec = Math.round(ms / 1000.0);
        return sec >= 1 ? (int) sec : null;
    }
}
