package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 포털 저장소 경로 판정의 <b>단일 지점</b> (CWE-22/59/367). @design AC-037, DFEAT-055
 *
 * <p>같은 판정을 두 곳이 각자 구현하면 한쪽만 강화되어 조용히 어긋난다. 그래서 서빙·사용자 삭제
 * ({@link PortalUploadService})와 보존기간 만료 삭제 배치({@code PortalRetentionSweepJob})가
 * <b>이 클래스 한 벌</b>을 공유한다 — AC-037 이 "{@code deleteUpload} 와 같은 판정기 공유"를
 * 요구하는 지점이다.
 *
 * <h3>판정과 정책을 분리한다</h3>
 * <p>판정 결과는 <b>예외가 아니라 값</b>({@link RealPathCheck})으로 돌려준다. 같은 판정이라도
 * 호출자마다 옳은 처리가 다르기 때문이다 — 서빙은 예외(404/403)가 맞고, 사용자 삭제는 예외 +
 * <b>DB 행 보존</b>이 맞으며, 배치는 예외가 아니라 <b>그 건만 건너뛰고 WARN</b> 이 맞다(한 건 때문에
 * 회차 전체가 멈추면 안 된다). 판정기가 스스로 던지면 그 선택지가 호출자에게 남지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalStoragePathGuard {

    private final PortalUploadProperties properties;

    /** 포털 저장소 루트(절대·정규화). */
    public Path baseDir() {
        return Paths.get(properties.storagePath()).toAbsolutePath().normalize();
    }

    /** CWE-22 Path Traversal 가드 — baseDir 외부 경로 거부. 사용자 요청 경로에서 쓴다. */
    public Path resolveSafe(Path baseDir, Path candidate) {
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).normalize();
        if (!resolved.startsWith(baseDir)) {
            log.warn("[PortalPathGuard] path traversal blocked");
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 경로입니다.");
        }
        return resolved;
    }

    /**
     * DB 적재 경로 문자열 1건을 <b>예외 없이</b> 판정한다 — 배치 전용 진입점. @design AC-037
     *
     * <p>{@link #resolveSafe} 는 lexical 이탈을 예외로 던지므로 배치가 그대로 쓰면 자산 한 건 때문에
     * 회차 전체가 중단된다. 여기서는 lexical 이탈도 {@link Verdict#ESCAPED} 값으로 돌려 호출자가
     * "그 건만 건너뛴다"를 고를 수 있게 한다.
     *
     * @param pathStr DB 에 적재된 경로 문자열(null·공백이면 {@link Verdict#ABSENT})
     */
    public RealPathCheck checkStoredPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            // 경로가 애초에 없다 = 지울 파일이 없다. 배치에서는 정상 통과다.
            return RealPathCheck.of(Verdict.ABSENT);
        }
        Path baseDir = baseDir();
        Path candidate = Paths.get(pathStr);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).normalize();
        if (!resolved.startsWith(baseDir)) {
            return RealPathCheck.of(Verdict.ESCAPED);
        }
        return checkRealWithinBase(baseDir, resolved);
    }

    /**
     * 실경로가 base 하위인지 판정하고 <b>그 실경로</b>를 함께 돌려준다 (CWE-59/22/367). @design AC-037
     *
     * <p>lexical 검증({@link #resolveSafe})을 통과한 경로라도 <b>경로 중간의 디렉터리 심링크</b>는
     * 그대로 따라가므로 base 밖 파일이 된다. 특히 삭제에서 위험하다 — {@code Files.delete} 는 최종
     * 요소가 링크면 링크만 지우지만 <b>중간 디렉터리 링크는 투명하게 따라가</b> 실제 대상을 지운다.
     * 그래서 leaf 링크로 만든 회귀 테스트는 이 결함을 재현하지 못한다.
     *
     * <p>최종 요소가 심링크인 경우도 {@link Verdict#ESCAPED} 로 막는다. 우리는 저장 시 UUID 이름의
     * 일반 파일만 쓰므로 링크는 애초에 우리 형상이 아니고, 실경로로 지우면 <b>링크가 아니라 그 대상
     * (다른 자산의 파일)</b>이 지워진다.
     *
     * <p>부재를 예외가 아니라 판정값으로 돌려주는 것도 의도다 — {@code toRealPath()} 는 대상이 없으면
     * 예외를 던지므로({@code deleteIfExists} 와 다르다) 그대로 쓰면 <b>이미 지워진 파일의 재삭제가
     * 깨진다</b>. 로그·응답에 경로 원문은 남기지 않는다(CWE-209).
     */
    public RealPathCheck checkRealWithinBase(Path baseDir, Path resolved) {
        Path realBase;
        try {
            realBase = baseDir.toRealPath();
        } catch (IOException e) {
            // 저장 루트 자체를 확정할 수 없으면 어떤 판정도 신뢰할 수 없다(fail-closed).
            return RealPathCheck.of(Verdict.UNRESOLVABLE);
        }
        // NOFOLLOW — 끊어진 링크를 "없음"으로 보면 삭제가 링크를 남긴 채 조용히 넘어간다.
        if (Files.notExists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return RealPathCheck.of(Verdict.ABSENT);
        }
        if (Files.isSymbolicLink(resolved)) {
            return RealPathCheck.of(Verdict.ESCAPED);
        }
        try {
            Path real = resolved.toRealPath();
            return real.startsWith(realBase)
                    ? new RealPathCheck(Verdict.OK, real)
                    : RealPathCheck.of(Verdict.ESCAPED);
        } catch (IOException e) {
            return RealPathCheck.of(Verdict.UNRESOLVABLE);
        }
    }

    /**
     * 실경로 판정 결과.
     *
     * @param verdict  판정
     * @param realPath {@link Verdict#OK} 일 때만 non-null — <b>이 경로로 열고 이 경로로 지운다</b>
     */
    public record RealPathCheck(Verdict verdict, Path realPath) {

        public static RealPathCheck of(Verdict verdict) {
            return new RealPathCheck(verdict, null);
        }
    }

    /** {@link RealPathCheck} 판정값. */
    public enum Verdict {
        /** base 하위의 실재하는 대상 — {@code realPath} 사용 가능. */
        OK,
        /** 대상이 없다. 삭제에서는 <b>정상(멱등)</b>, 서빙에서는 404. */
        ABSENT,
        /** 실경로가 base 밖이거나 최종 경로 요소가 심링크다 — 우리가 쓴 적 없는 형상. */
        ESCAPED,
        /** 실경로를 확정할 수 없다(I/O 오류·끊어진 링크 등). 어느 호출자에서도 진행 금지. */
        UNRESOLVABLE
    }
}
