package com.genersoft.iot.vmp.conf.security;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.security.dto.JwtUser;
import com.genersoft.iot.vmp.service.IUserApiKeyService;
import com.genersoft.iot.vmp.service.IUserService;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import com.genersoft.iot.vmp.storager.dao.dto.UserApiKey;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.jose4j.jwt.consumer.ErrorCodes;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.HmacKey;
import org.jose4j.lang.JoseException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

/**
 * 登录 Token：HS256 短 JWT（约 120 字符），避免 4096 位 RS256 超长 Header 被防火墙 RST。
 */
@Slf4j
@Component
public class JwtUtils implements InitializingBean {

    public static final String HEADER = "access-token";

    public static final String API_KEY_HEADER = "api-key";

    /**
     * token过期时间(分钟)
     */
    public static final long EXPIRATION_TIME = 30;

    private static Key hmacKey;

    private static IUserService userService;

    private static IUserApiKeyService userApiKeyService;

    private static UserSetting userSetting;

    public static String getApiKeyHeader() {
        return API_KEY_HEADER;
    }

    @Resource
    public void setUserService(IUserService userService) {
        JwtUtils.userService = userService;
    }

    @Resource
    public void setUserApiKeyService(IUserApiKeyService userApiKeyService) {
        JwtUtils.userApiKeyService = userApiKeyService;
    }

    @Resource
    public void setUserSetting(UserSetting userSetting) {
        JwtUtils.userSetting = userSetting;
    }

    @Override
    public void afterPropertiesSet() {
        hmacKey = loadOrCreateHmacKey();
        log.info("[API AUTH] 已启用 HS256 短 Token");
    }

    private Key loadOrCreateHmacKey() {
        String configured = userSetting != null ? userSetting.getJwtSecret() : null;
        if (configured != null && !configured.isBlank()) {
            return toHmacKey(configured.trim());
        }
        Path persistPath = Paths.get("config", "jwt.secret");
        try {
            if (Files.exists(persistPath)) {
                String saved = Files.readString(persistPath, StandardCharsets.UTF_8).trim();
                if (!saved.isEmpty()) {
                    log.info("[API AUTH] 从 {} 读取 HS256 密钥", persistPath.toAbsolutePath());
                    return toHmacKey(saved);
                }
            }
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String generated = HexFormat.of().formatHex(bytes);
            Files.createDirectories(persistPath.getParent());
            Files.writeString(persistPath, generated, StandardCharsets.UTF_8);
            log.warn("[API AUTH] 未配置 user-settings.jwt-secret，已生成密钥并保存到 {}", persistPath.toAbsolutePath());
            return toHmacKey(generated);
        } catch (IOException e) {
            log.error("[API AUTH] 读写 HS256 密钥失败，本次使用内存随机密钥（重启后登录会失效）", e);
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            return new HmacKey(bytes);
        }
    }

    private Key toHmacKey(String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            try {
                keyBytes = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 不可用", e);
            }
        }
        return new HmacKey(keyBytes);
    }

    public static String createToken(String username, Long expirationTime, Map<String, Object> extra) {
        try {
            JwtClaims claims = new JwtClaims();
            if (expirationTime != null) {
                claims.setExpirationTimeMinutesInTheFuture(expirationTime);
            }
            claims.setClaim("userName", username);
            if (extra != null) {
                extra.forEach(claims::setClaim);
            }
            JsonWebSignature jws = new JsonWebSignature();
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256);
            jws.setPayload(claims.toJson());
            jws.setKey(hmacKey);
            return jws.getCompactSerialization();
        } catch (JoseException e) {
            log.error("[Token生成失败]： {}", e.getMessage());
        }
        return null;
    }

    public static String createToken(String username, Long expirationTime) {
        return createToken(username, expirationTime, null);
    }

    public static String createToken(String username) {
        return createToken(username, userSetting.getLoginTimeout());
    }

    public static String getHeader() {
        return HEADER;
    }

    public static JwtUser verifyToken(String token) {
        JwtUser jwtUser = new JwtUser();
        try {
            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setAllowedClockSkewInSeconds(30)
                    .setSkipDefaultAudienceValidation()
                    .setVerificationKey(hmacKey)
                    .setRelaxVerificationKeyValidation()
                    .build();

            JwtClaims claims = consumer.processToClaims(token);
            NumericDate expirationTime = claims.getExpirationTime();
            if (expirationTime != null) {
                long timeRemaining = expirationTime.getValue() - LocalDateTime.now().toEpochSecond(ZoneOffset.ofHours(8));
                if (timeRemaining < 5 * 60) {
                    jwtUser.setStatus(JwtUser.TokenStatus.EXPIRING_SOON);
                } else {
                    jwtUser.setStatus(JwtUser.TokenStatus.NORMAL);
                }
            } else {
                jwtUser.setStatus(JwtUser.TokenStatus.NORMAL);
            }

            Long apiKeyId = claims.getClaimValue("apiKeyId", Long.class);
            if (apiKeyId != null) {
                UserApiKey userApiKey = userApiKeyService.getUserApiKeyById(apiKeyId.intValue());
                if (userApiKey == null || !userApiKey.isEnable()) {
                    jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
                }
            }

            String username = (String) claims.getClaimValue("userName");
            User user = userService.getUserByUsername(username);

            jwtUser.setUserName(username);
            jwtUser.setPassword(user.getPassword());
            jwtUser.setRoleId(user.getRole().getId());
            jwtUser.setUserId(user.getId());

            return jwtUser;
        } catch (InvalidJwtException e) {
            if (e.hasErrorCode(ErrorCodes.EXPIRED)) {
                jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
            } else {
                jwtUser.setStatus(JwtUser.TokenStatus.EXCEPTION);
            }
            return jwtUser;
        } catch (Exception e) {
            log.error("[Token解析失败]： {}", e.getMessage());
            jwtUser.setStatus(JwtUser.TokenStatus.EXPIRED);
            return jwtUser;
        }
    }
}
