package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.storage.AllowedRootMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.UnaryOperator;

/**
 * 포털이 준 <b>소재 절대경로</b>를 우리가 열기 전에 재검증하는 단일 지점 (CWE-22/59/367).
 *
 * <h3>★ 받은 절대경로를 그대로 열지 않는다 (INT-014 「우리 쪽 규칙 1」)</h3>
 * <p>외부가 준 경로를 우리가 파일 시스템에서 여는 구조다. 그래서 <b>응답이 함께 준 저장소 루트
 * 하위인지 실경로로 재검증한 뒤 그 실경로로</b> 연다. 어긋나면 열지 않는다(fail-closed).
 *
 * <p>⚠ <b>「포털과 같은 저장소를 같은 경로로 마운트한다」는 이 검증의 면제 사유가 아니다.</b>
 * 마운트가 같아도 외부가 준 경로를 그대로 여는 것은 여전히 위험하다. 「마운트가 같으니 검증이
 * 불필요하다」로 읽지 말 것 — 규칙 1 은 그대로 살아 있다.
 *
 * <h3>★ 검증한 경로와 여는 경로가 갈리면 그 사이로 바꿔치기가 들어온다</h3>
 * <p>그래서 판정에 쓴 <b>실경로 그 자체</b>({@link Check#realPath()})를 돌려주고 호출부는 그 값으로만
 * 연다. lexical 경로를 돌려주면 소비측이 검증 이후 다시 심링크를 따라가므로 검증 대상과 사용 대상이
 * 달라진다(TOCTOU).
 *
 * <h3>세 단계를 합치지 않는다</h3>
 * <ol>
 *   <li><b>표기 단계</b> — {@link AllowedRootMatcher} 가 소유한다. 규칙을 여기 복제하지 않는다.
 *       상위 이동 표기({@code ..})가 여기서 걸린다.</li>
 *   <li><b>실경로 단계</b> — 루트와 후보를 각각 실경로로 접어 비교한다. 범위 밖을 가리키는
 *       심링크가 여기서 걸린다.</li>
 *   <li><b>대상 성질 단계</b> — 정규 파일인지 본다. 디렉터리·특수 파일을 열지 않는다.</li>
 * </ol>
 * <p>「일관성」을 이유로 합치면 그중 무엇이 사라졌는지 드러나지 않은 채 방어가 뚫린다.
 *
 * <h3>⚠ 실경로 해석기는 이 축 전용이다 — 공유하지 말 것</h3>
 * <p>{@link AllowedRootMatcher} 는 <b>규칙은 공유하고 해석기는 각자</b> 두라고 못박는다. 이 축은
 * <b>소재가 실재해야 읽을 수 있으므로</b> 해석 실패를 거부로 끝내는 <b>fail-secure</b> 쪽이다
 * (해석 실패를 통과로 다루는 축과 성질이 반대다).
 *
 * <h3>판정과 정책을 분리한다</h3>
 * <p>결과는 예외가 아니라 <b>값</b>으로 돌려준다 — 호출자마다 옳은 처리가 다르기 때문이다(조달은
 * 그 건을 실패로 마감하고, 목록 축은 그 건만 건너뛰는 편이 맞다). 판정기가 스스로 던지면 그
 * 선택지가 호출자에게 남지 않는다. 로그·응답에 <b>경로 원문을 남기지 않는다</b>(CWE-209).
 *
 * @design INT-014
 */
@Slf4j
@Component
public class PortalMaterialsPathGuard {

    /**
     * fail-secure 실경로 해석기 — 해석할 수 없으면 {@code null} 이라 표기 판정이 통과하지 않는다.
     *
     * <p>{@link AllowedRootMatcher} 는 {@code null} 을 「그 시작점은 인정하지 않는다」로 다루므로,
     * 해석 실패가 <b>범위를 넓히는 쪽으로</b> 기울지 않는다.
     */
    private static final UnaryOperator<Path> FAIL_SECURE_REAL = p -> {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    };

    /**
     * {@code localPath} 가 {@code repoRootDir} 하위의 실재하는 정규 파일인지 판정하고,
     * 통과하면 <b>그 실경로</b>를 함께 돌려준다.
     *
     * @param repoRootDir 포털 응답이 함께 준 저장소 루트(허용 범위의 시작점)
     * @param localPath   포털 응답의 소재 절대경로. 상대 경로면 루트 기준으로 해석한다
     */
    public Check resolveWithinRoot(String repoRootDir, String localPath) {
        if (isBlank(repoRootDir) || isBlank(localPath)) {
            return Check.of(Verdict.BLANK);
        }
        Path root;
        Path resolved;
        try {
            root = Paths.get(repoRootDir).toAbsolutePath().normalize();
            Path candidate = Paths.get(localPath);
            resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : root.resolve(candidate).normalize();
        } catch (RuntimeException e) {
            // 해석 불가 경로 = fail-secure. 원문을 로그에 남기지 않는다.
            return Check.of(Verdict.ESCAPED);
        }
        // ① 표기 단계 — 규칙은 AllowedRootMatcher 가 소유한다(복제 금지). `..` 가 여기서 걸린다.
        if (!AllowedRootMatcher.startsWithRoot(resolved, root, FAIL_SECURE_REAL)) {
            log.warn("[PortalMaterials] 소재 경로가 저장소 루트 표기 밖입니다 — 열지 않습니다.");
            return Check.of(Verdict.ESCAPED);
        }
        // NOFOLLOW — 끊어진 링크를 「없음」으로 덮어 두면 그 자리가 나중에 채워질 때 판정이 갈린다.
        if (Files.notExists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return Check.of(Verdict.ABSENT);
        }
        Path realRoot;
        Path real;
        try {
            realRoot = root.toRealPath();
            real = resolved.toRealPath();
        } catch (IOException | RuntimeException e) {
            // 루트나 대상을 확정할 수 없으면 어떤 판정도 신뢰할 수 없다(fail-closed).
            return Check.of(Verdict.UNRESOLVABLE);
        }
        // ② 실경로 단계 — 범위 밖을 가리키는 심링크가 여기서 걸린다.
        if (!real.startsWith(realRoot)) {
            log.warn("[PortalMaterials] 소재 경로의 실경로가 저장소 루트 밖입니다 — 열지 않습니다.");
            return Check.of(Verdict.ESCAPED);
        }
        // ③ 대상 성질 단계 — 실경로의 마지막 요소는 이미 링크가 아니므로 NOFOLLOW 가 판정을 바꾸지
        //    않는다. 그래도 명시해 「이 자리부터는 링크를 따라가지 않는다」를 코드로 남긴다.
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            return Check.of(Verdict.NOT_REGULAR_FILE);
        }
        return new Check(Verdict.OK, real);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 판정 결과.
     *
     * @param verdict  판정
     * @param realPath {@link Verdict#OK} 일 때만 non-null — <b>이 경로로만 연다</b>
     */
    public record Check(Verdict verdict, Path realPath) {

        public static Check of(Verdict verdict) {
            return new Check(verdict, null);
        }

        public boolean ok() {
            return verdict == Verdict.OK;
        }
    }

    /** {@link Check} 판정값. */
    public enum Verdict {
        /** 루트 하위의 실재하는 정규 파일 — {@code realPath} 사용 가능. */
        OK,
        /** 루트나 경로가 비어 있다. */
        BLANK,
        /** 대상이 없다. */
        ABSENT,
        /** 표기 또는 실경로가 저장소 루트 밖이다(상위 이동 표기·범위 밖 심링크 포함). */
        ESCAPED,
        /** 실경로를 확정할 수 없다(권한·끊어진 링크·I/O 오류). */
        UNRESOLVABLE,
        /** 정규 파일이 아니다(디렉터리·특수 파일). */
        NOT_REGULAR_FILE
    }
}
