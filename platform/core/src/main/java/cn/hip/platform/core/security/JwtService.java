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
import java.util.Date;

/** JWT 签发与校验 */
@Service
public class JwtService {

    /** application.yml 中的开发占位值：生产/试点若未注入 HIP_JWT_SECRET 就会回落到它 */
    public static final String DEV_PLACEHOLDER = "dev-only-secret-change-me-hip-platform-0123456789";

    private final SecretKey key;
    private final Duration expiry;

    public JwtService(@Value("${hip.security.jwt-secret}") String secret,
                      @Value("${hip.security.jwt-expiry-hours:12}") long expiryHours,
                      Environment environment) {
        // 试点/生产必须注入真实密钥：占位值可被任何人用于伪造 admin 令牌，故直接阻断启动
        if (cn.hip.platform.core.config.HipProfiles.isProduction(environment) && (DEV_PLACEHOLDER.equals(secret) || secret == null || secret.length() < 32)) {
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

    /**
     * 带口令戳的签发（v27-A）：claim "pwd" 记录签发时刻的 passwordUpdatedAt 秒值。
     * 改密后库里的戳变了，旧 token 的 pwd 对不上即失效——JWT 无状态撤销的最小代价方案。
     * 不改 issue(String) 的签名：它有 16 处测试调用 + 患者端 PortalController 在用。
     */
    public String issue(String username, long passwordStamp) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("pwd", passwordStamp)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(key)
                .compact();
    }

    /** 校验并返回用户名；无效或过期抛出 JwtException */
    public String verify(String token) {
        return verifyClaims(token).getSubject();
    }

    /** 校验并返回全部 claims（过滤器需要读 pwd 口令戳）；无效或过期抛出 JwtException */
    public Claims verifyClaims(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
