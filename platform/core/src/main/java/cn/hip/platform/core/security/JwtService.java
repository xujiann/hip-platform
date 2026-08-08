package cn.hip.platform.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/** JWT 签发与校验 */
@Service
public class JwtService {

    /** application.yml 中的开发占位值：生产/试点若未注入 HIP_JWT_SECRET 就会回落到它 */
    static final String DEV_PLACEHOLDER = "dev-only-secret-change-me-hip-platform-0123456789";
    private static final List<String> GUARDED_PROFILES = List.of("pilot", "prod");

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(@Value("${hip.security.jwt-secret}") String secret,
                      @Value("${hip.security.jwt-expiry-hours:12}") long expiryHours,
                      Environment environment) {
        // 试点/生产必须注入真实密钥：占位值可被任何人用于伪造 admin 令牌，故直接阻断启动
        boolean guarded = Arrays.stream(environment.getActiveProfiles()).anyMatch(GUARDED_PROFILES::contains);
        if (guarded && (DEV_PLACEHOLDER.equals(secret) || secret == null || secret.length() < 32)) {
            throw new IllegalStateException(
                    "拒绝启动：pilot/prod profile 必须注入 HIP_JWT_SECRET（≥32 位随机串，"
                            + "生成方式见部署手册；当前为开发占位值或长度不足）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofHours(expiryHours);
    }

    public String issue(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /** 校验并返回用户名；无效或过期抛出 JwtException */
    public String verify(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }
}
