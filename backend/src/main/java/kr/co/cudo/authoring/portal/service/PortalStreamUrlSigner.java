package kr.co.cudo.authoring.portal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 포털 업로드 영상 재생용 <b>단기 서명</b> 발급·검증기.
 *
 * <h3>왜 필요한가</h3>
 * <p>재생 요소는 요청에 인증 헤더를 실을 수 없다. 그래서 인증이 걸린 재생 창구를 주소만으로 재생할
 * 수 없고, 짧은 시간만 유효한 서명이 붙은 주소를 따로 발급받아야 한다. 이 제약은 브라우저 재생
 * 요소의 성질이라 채널과 무관하다.
 *
 * <h3>★ 관제 채널 서명기와 <b>별개</b>다 — 두 가지 이유</h3>
 * <ol>
 *   <li><b>도메인 분리.</b> 흡수(ADR-058) 이후 포털 자산 식별자와 관제 영상 식별자는 <b>같은 원장의
 *       같은 번호 공간</b>이다. 서명 입력의 모양이 같으면 한 채널에서 받은 서명이 다른 채널 창구에서도
 *       성립할 수 있으므로, 이 서명기는 입력 앞에 채널 표식을 붙여 두 서명이 절대 겹치지 않게 한다.</li>
 *   <li><b>클라이언트 바인딩 수단이 아직 정해지지 않았다.</b> 관제 서명기는 주소에 싣지 않는 값(쿠키
 *       nonce)을 <b>필수</b>로 요구하는데, 그 값이 포털 채널의 실행 환경에서 요청에 함께 실리는지가
 *       확인되지 않았다(API-239 가 그 수단을 정하지 않은 이유). 없는 수단을 임의로 끌어다 쓰면 확인되지
 *       않은 전제 위에 보안을 세우게 된다.</li>
 * </ol>
 *
 * <h3>남는 위험과 그 처리(인지·수용)</h3>
 * <p>주소가 새어 나가면 <b>만료 전까지는</b> 그 주소로 재생될 수 있다. 유효 시간을 짧게 두는 것이
 * 그 노출 창을 좁히는 장치다. 클라이언트를 한 번 더 묶는 수단은 이 채널의 실행 환경이 확인된 뒤
 * 더한다.
 *
 * <h3>보안 가드</h3>
 * <ul>
 *   <li><b>키 분리</b>: 토큰 시크릿을 재사용하지 않는다. 스트림 전용 설정을 쓴다.</li>
 *   <li><b>키 강도</b>: 256bit(32B) 이상. 부족하면 부팅 차단(fail-closed).</li>
 *   <li><b>미설정 fail-closed</b>: 시크릿이 없으면 발급·검증 모두 거부한다 — 서명 없이 열면 아무나
 *       재생할 수 있는 주소가 나간다.</li>
 *   <li><b>상수시간 비교</b>(CWE-208).</li>
 * </ul>
 *
 * @design API-239
 */
@Slf4j
@Component
public class PortalStreamUrlSigner {

    /** 서명 시크릿 최소 길이 — HS256 권장 256bit = 32B. */
    public static final int MIN_SECRET_BYTES = 32;

    /** 채널 표식 — 서명 입력 앞에 붙어 다른 채널 서명과 절대 겹치지 않게 한다. */
    private static final String DOMAIN = "portal-upload";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secretBytes;
    private final boolean configured;
    private final long ttlSeconds;

    public PortalStreamUrlSigner(
            @Value("${authoring.stream.sign-secret:}") String signSecret,
            @Value("${authoring.stream.url-ttl-seconds:60}") long ttlSeconds) {
        if (signSecret == null || signSecret.isBlank()) {
            this.secretBytes = new byte[0];
            this.configured = false;
        } else {
            byte[] bytes = signSecret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_SECRET_BYTES) {
                throw new BeanInitializationException(
                        "authoring.stream.sign-secret 길이가 " + bytes.length + "B 입니다. 최소 "
                                + MIN_SECRET_BYTES + "B(256bit) 이상이어야 합니다.");
            }
            this.secretBytes = bytes;
            this.configured = true;
        }
        // 한 편을 끝까지 볼 만큼 길지 않게 둔다 — 재발급을 전제로 한 단기 서명이다.
        this.ttlSeconds = Math.min(600L, Math.max(5L, ttlSeconds));
    }

    /** 시크릿이 설정되어 발급·검증이 가능한지. */
    public boolean isConfigured() {
        return configured;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * 서명 발급 — 만료 시각과 서명을 돌려준다.
     *
     * @param uldSn  재생할 자산 식별자
     * @param userNo 발급을 요청한 본인 — 서명이 이 값을 덮으므로 다른 사람의 것으로는 쓰이지 않는다
     */
    public SignedParams sign(long uldSn, String userNo) {
        if (!configured) {
            throw new IllegalStateException("stream sign-secret 미설정 — 서명 URL 발급 불가 (fail-closed)");
        }
        long exp = Instant.now().plus(Duration.ofSeconds(ttlSeconds)).getEpochSecond();
        return new SignedParams(exp, computeHex(uldSn, exp, userNo), ttlSeconds);
    }

    /**
     * 서명 검증 — 미만료 + 서명 일치. 미설정·만료·형식오류·불일치는 모두 {@code false}(fail-closed).
     */
    public boolean verify(long uldSn, String expRaw, String sigRaw, String userNo) {
        if (!configured) {
            return false;
        }
        if (expRaw == null || expRaw.isBlank() || sigRaw == null || sigRaw.isBlank()) {
            return false;
        }
        if (userNo == null || userNo.isBlank()) {
            // 소유자에 묶이지 않은 서명은 인정하지 않는다 — 재생 인가가 소유자 판정이기 때문이다.
            return false;
        }
        long exp;
        try {
            exp = Long.parseLong(expRaw.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (exp < Instant.now().getEpochSecond()) {
            return false;
        }
        byte[] expected = computeHex(uldSn, exp, userNo).getBytes(StandardCharsets.UTF_8);
        byte[] provided = sigRaw.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private String computeHex(long uldSn, long exp, String userNo) {
        String canonical = DOMAIN + "." + uldSn + "." + exp + "." + (userNo == null ? "" : userNo);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("stream 서명 계산 실패", e);
        }
    }

    /** 서명 발급 결과. */
    public record SignedParams(long exp, String sig, long ttlSeconds) {
    }
}
