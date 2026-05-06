package kr.co.cudo.authoring.common.security;

import javax.crypto.SecretKey;

public interface JwtKeyResolver {
    SecretKey resolve();
}
