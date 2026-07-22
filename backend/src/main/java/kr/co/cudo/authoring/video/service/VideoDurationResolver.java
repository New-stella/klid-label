package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.service.VideoDurationDbReader.DurationSource;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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
 * transient 하게 해석해 값만 반환한다.
 *
 * <p><b>리소스 관리(MEDIUM-1 해소 — ffprobe 를 커넥션 밖에서):</b> 3단계 폴백의 마지막(직접 프로브)은
 * ffprobe 서브프로세스를 실행한다(최대 수십 초). 과거에는 이 리졸버가 호출자({@code MarkingService.create})의
 * <b>쓰기 트랜잭션</b>에 join 하여 프로브 동안 DB 커넥션을 점유했다(HikariCP 풀 고갈 위험). 이제 본 메서드는
 * <b>쓰기 트랜잭션 진입 전</b>({@code MarkingService} 의 비트랜잭션 오케스트레이션, AUTO 분기)에 호출되고,
 * 내부적으로 다음을 보장한다:
 * <ul>
 *   <li>값싼 DB read(①②)는 {@link VideoDurationDbReader} 의 짧은 {@code REQUIRES_NEW}(readOnly)
 *       트랜잭션으로 수행되어 리턴 즉시(커밋 시) 커넥션을 풀에 반납한다.</li>
 *   <li>현재 유일한 호출부(비트랜잭션 오케스트레이션)는 <b>ambient 트랜잭션 없이</b> 이 메서드를 호출하므로,
 *       프로브(③) 시점에는 이 요청이 어떤 DB 커넥션도 보유하지 않는다(위 DB read 커넥션은 이미 반납됨).
 *       <b>이 "커넥션 미보유"는 호출부가 트랜잭션 밖이라는 사실에서 성립한다.</b></li>
 *   <li>{@code NOT_SUPPORTED} 선언은 <b>2차 방어</b>다 — 혹시 활성 트랜잭션 안에서 호출되더라도 그 트랜잭션의
 *       동기화를 <b>일시 정지</b>시켜 프로브가 활성 트랜잭션 동기화 없이 실행되게 한다. 다만 <b>tx 정지는 곧
 *       커넥션 반납이 아니다</b> — 정지된 트랜잭션이 이미 획득한 커넥션은 checked-out 상태로 유지되므로,
 *       {@code NOT_SUPPORTED} <b>단독으로는</b> 커넥션을 반납시키지 못한다(커넥션 미보유는 위처럼 호출부가
 *       tx 밖일 때 성립).</li>
 * </ul>
 * 적재 파이프라인이 durationSec/메타를 채우면 프로브 경로는 아예 타지 않는다.
 *
 * <p><b>보안(CWE-209):</b> 로그에 원본 경로/PII 원문을 직접 출력하지 않는다({@code BrampVideoProbe} 는
 * 경로를 hash 마스킹하며, 본 리졸버는 경로를 로그에 찍지 않고 rawSn·예외 클래스명만 남긴다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoDurationResolver {

    private final VideoDurationDbReader dbReader;
    private final VideoProbe videoProbe;

    /**
     * rawSn 영상의 재생 길이(초)를 다단 폴백으로 해석한다. <b>쓰기 트랜잭션 밖에서</b> 호출해야 하며,
     * ffprobe 프로브는 어떤 DB 커넥션도 보유하지 않은 채 실행된다(위 클래스 Javadoc "리소스 관리" 참조).
     *
     * @param rawSn 영상 PK (LS_DATA_RAW.RAW_SN). null 이거나 영상 미존재면 {@code null}.
     * @return 해석된 재생 길이(초, ≥1). 어떤 경로로도 해석 실패 시 {@code null}.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.NOT_SUPPORTED)
    public Integer resolveDurationSec(Long rawSn) {
        // 값싼 DB read(①②)는 짧은 REQUIRES_NEW 로 수행 후 커넥션 즉시 반납 (프로브 전에 커넥션 미보유).
        DurationSource source = dbReader.read(rawSn);
        if (source == null) {
            return null;
        }
        // 1. 적재 시 기록된 VDO_LEN_SEC 최우선 (기존 동작 보존 — 무회귀).
        if (source.durationSec() != null && source.durationSec() > 0) {
            return source.durationSec();
        }
        // 2. 적재 시 ffprobe 로 이미 적재된 video.duration_ms 메타 (재프로브 회피).
        Integer fromMeta = msStringToSecondsOrNull(source.durationMsMeta());
        if (fromMeta != null) {
            return fromMeta;
        }
        // 3. 직접 프로브 (원본 경로) — 활성 트랜잭션/커넥션 없이 실행. 비식별본과 원본은 재생 길이가 동일.
        Integer fromProbe = probeDurationSec(rawSn, source.rawFilePathNm());
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
    private Integer probeDurationSec(Long rawSn, String path) {
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
