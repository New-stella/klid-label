package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 관리자 단기 유효창 토큰 — <b>무상태 서명 토큰</b> (R11).
 *
 * <h3>왜 무상태 서명인가 (2노드 Active-Active)</h3>
 * <p>세션을 발급 노드의 메모리에 담으면 <b>다음 요청이 다른 노드로 가는 순간 403</b> 이다(로드밸런서가
 * 스티키가 아니어도 되도록 만드는 것이 이 배포 형상의 전제다). 공유 저장소(DB)에 담는 방법도 있지만
 * 새 테이블이 필요하고, 이번 설계는 <b>새 테이블 없음</b>이 확정 사항이다.
 *
 * <p>서명 토큰은 두 제약을 동시에 만족한다 — <b>어느 노드에서 검증해도 같은 결과</b>이고
 * <b>저장소가 필요 없다</b>. 유효성 판정은 여전히 <b>서버가 소유</b>한다: 만료 시각이 서명 대상 안에
 * 들어 있어 클라이언트가 늘릴 수 없고, 서버는 <b>매 저장 요청마다</b> 서명과 만료를 다시 본다.
 *
 * <h3>토큰 형식</h3>
 * <pre>base64url(payload) + "." + base64url(HMAC-SHA256(derivedKey, payload))</pre>
 * payload = {@code v1|subject|expiryEpochSeconds}
 *
 * <h3>인증 토큰으로 오인될 수 없다</h3>
 * <p>서명 키는 JWT 서명 키를 <b>그대로 쓰지 않고</b> 고정 라벨로 HMAC 파생한 별도 키다
 * ({@link #KEY_DERIVATION_LABEL}). 그래서 이 토큰을 {@code Authorization: Bearer} 로 제시해도 인증
 * 필터의 서명 검증을 통과하지 못한다. 반대로 인증 토큰을 이 자리에 넣어도 통과하지 못한다.
 * 두 축이 키를 공유하면 한쪽 토큰이 다른 쪽에서 통용될 여지가 생긴다.
 *
 * <h3>권한을 올리지 않는다</h3>
 * <p>토큰에는 <b>역할 클레임이 없다</b>. subject 만 실려 있고, 소비처는 연동 주소 키 저장 한 곳뿐이다.
 * 즉 이 토큰이 있어도 REVIEWER 가 아닌 사람은 여전히 그 API 에 들어오지 못한다(인가는 기존
 * {@code @PreAuthorize} + 서비스 이중 검증이 그대로 담당하며, 이 토큰은 <b>그 위에 더해지는</b> 조건이다).
 *
 * <h3>subject 결박</h3>
 * <p>토큰은 발급받은 사용자에게만 유효하다. 결박하지 않으면 한 사람이 받은 토큰을 다른 사람이
 * 그대로 써서 유효창이 사실상 공유된다.
 */
@Slf4j
@Component
public class AdminSessionTokenService {

    /** 파생 키 라벨 — 인증 JWT 서명 키와 이 토큰의 서명 키를 분리한다. */
    private static final String KEY_DERIVATION_LABEL = "klid-admin-session/v1";

    private static final String HMAC_ALG = "HmacSHA256";

    /** 페이로드 버전 — 형식이 바뀌면 올려 과거 토큰을 자동 무효화한다. */
    private static final String PAYLOAD_VERSION = "v1";

    /**
     * 유효기간 상한 — <b>설정으로도 넘을 수 없다</b>. 단기 유효창이라는 성질 자체가 이 기능의
     * 방어선이므로, 설정 실수로 몇 시간짜리 창이 열리면 방어가 사라진다.
     */
    public static final Duration MAX_TTL = Duration.ofMinutes(30);

    /** 기본 유효기간. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final JwtKeyResolver keyResolver;
    private final Duration ttl;

    public AdminSessionTokenService(
            JwtKeyResolver keyResolver,
            @Value("${authoring.auth.admin-session.ttl-minutes:10}") long ttlMinutes) {
        this.keyResolver = keyResolver;
        this.ttl = clampTtl(ttlMinutes);
    }

    /**
     * 설정 TTL 을 상한 안으로 강제한다 — <b>기동 시점</b>에 한 번, 그리고 그 결과만 사용한다.
     *
     * <p>기동을 실패시키지 않고 상한으로 낮추는 쪽을 택했다: 이 값이 잘못됐다고 앱 전체가 못 뜨면
     * 무관한 기능까지 함께 막히는데, 상한으로 낮추면 <b>안전한 방향</b>으로만 어긋나기 때문이다.
     * 0 이하도 마찬가지로 기본값으로 되돌린다(유효창이 0 이면 기능이 즉시 죽는다).
     */
    private static Duration clampTtl(long ttlMinutes) {
        if (ttlMinutes <= 0) {
            log.warn("[AdminSession] ttl-minutes 가 0 이하입니다 — 기본값 {}분으로 되돌립니다.",
                    DEFAULT_TTL.toMinutes());
            return DEFAULT_TTL;
        }
        Duration requested = Duration.ofMinutes(ttlMinutes);
        if (requested.compareTo(MAX_TTL) > 0) {
            log.warn("[AdminSession] ttl-minutes({}) 가 상한 {}분을 초과합니다 — 상한으로 제한합니다.",
                    ttlMinutes, MAX_TTL.toMinutes());
            return MAX_TTL;
        }
        return requested;
    }

    /** 적용 중인 유효기간. */
    public Duration ttl() {
        return ttl;
    }

    /**
     * 토큰을 발급한다.
     *
     * @param subject 발급 대상(JWT sub). 토큰은 이 사용자에게만 유효하다.
     * @return 토큰과 만료 시각
     */
    public Issued issue(String subject, Instant now) {
        if (subject == null || subject.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Instant expiresAt = now.plus(ttl);
        String payload = PAYLOAD_VERSION + '|' + subject + '|' + expiresAt.getEpochSecond();
        String token = encode(payload) + '.' + encode(sign(payload));
        return new Issued(token, expiresAt);
    }

    /**
     * 토큰을 검증한다 — 서명·만료·subject 결박을 <b>매번</b> 다시 본다.
     *
     * <p>실패는 모두 {@link ErrorCode#FORBIDDEN}(403) 이며 사유를 구분해 알리지 않는다
     * (위조·만료·타인 토큰을 응답으로 구분해 주면 그 자체가 정보다 — CWE-209).
     *
     * @throws CustomException 토큰이 없거나 유효하지 않을 때
     */
    public void verify(String token, String subject, Instant now) {
        if (token == null || token.isBlank() || subject == null || subject.isBlank()) {
            throw expired();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw expired();
        }
        String payload;
        byte[] providedSignature;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)),
                    StandardCharsets.UTF_8);
            providedSignature = Base64.getUrlDecoder().decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw expired();
        }
        // CWE-203 — 서명 비교는 상수시간.
        if (!MessageDigest.isEqual(sign(payload), providedSignature)) {
            throw expired();
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 3 || !PAYLOAD_VERSION.equals(parts[0]) || !parts[1].equals(subject)) {
            throw expired();
        }
        long expiryEpochSeconds;
        try {
            expiryEpochSeconds = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw expired();
        }
        if (!now.isBefore(Instant.ofEpochSecond(expiryEpochSeconds))) {
            throw expired();
        }
    }

    private static CustomException expired() {
        return new CustomException(ErrorCode.FORBIDDEN,
                "관리자 인증이 필요합니다. 「관리자 설정」으로 다시 인증해 주세요.");
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(derivedKey());
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("관리자 세션 토큰 서명에 실패했습니다.", e);
        }
    }

    /** JWT 서명 키에서 <b>별도 키</b>를 파생한다 — 두 축이 키를 공유하지 않게. */
    private SecretKey derivedKey() {
        try {
            SecretKey base = keyResolver.resolve();
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(base);
            byte[] derived = mac.doFinal(KEY_DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(derived, HMAC_ALG);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("관리자 세션 서명 키 파생에 실패했습니다.", e);
        }
    }

    private static String encode(String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** 발급 결과 — 토큰과 만료 시각. */
    public record Issued(String token, Instant expiresAt) {
    }
}
