package cn.hip.platform.integration.mllp;

import cn.hip.platform.core.config.HipProfiles;
import cn.hip.platform.integration.hl7.Hl7V2Message;
import cn.hip.platform.integration.service.OruProcessingService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * MLLP/TCP 监听：LIS 设备/中间件的标准 HL7 传输承载。
 * 帧格式：0x0B <报文> 0x1C 0x0D；应答 ACK（MSA|AA/AE）。
 *
 * <p>1.1.2 加固（审阅 A-2/A-3）：该端口写入的是检验结果并能触发危急值流程，
 * 而 MLLP 协议本身无鉴权——同功能的 HTTP 端点要求 ADMIN，此处却对任何能连通的主机开放。
 * 故：①来源 IP 白名单（回环恒放行）；②可配绑定地址（默认全网卡以兼容容器端口映射，
 * 生产应绑内网接口卡）；③pilot/prod 未配置白名单则拒绝启动监听（fail closed，与 Mock 门禁同理）；
 * ④帧长上限——readFrame 原本无限缓冲，一个不发结束符的连接即可持续吃堆；
 * ⑤固定线程池 + 有界队列——原 cachedThreadPool 每连接一线程无上限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MllpServer {

    private static final byte SB = 0x0B;
    private static final byte EB = 0x1C;
    private static final byte CR = 0x0D;

    private final OruProcessingService oruProcessingService;
    private final Environment environment;

    @Value("${hip.integration.mllp-enabled:true}")
    private boolean enabled;

    @Value("${hip.integration.mllp-port:2575}")
    private int port;

    /** 监听绑定地址：生产应绑内网接口卡；默认 0.0.0.0 以兼容容器端口映射（配合白名单使用） */
    @Value("${hip.integration.mllp-bind:0.0.0.0}")
    private String bindAddress;

    /** 允许来源（逗号分隔，支持精确 IP 与 CIDR 如 192.168.10.0/24）；回环地址恒放行 */
    @Value("${hip.integration.mllp-allow:}")
    private String allowList;

    /** 单帧上限（字节）：超限即回 AE 并断连 */
    @Value("${hip.integration.mllp-max-frame:1048576}")
    private int maxFrame;

    private ServerSocket serverSocket;
    private Thread acceptorThread;
    /** 业务处理池：核心 4 / 上限 8 / 队列 32，满即拒绝新连接——上限可估算，堆不可估算 */
    private final ExecutorService pool = new ThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32));
    private volatile boolean running;
    private List<long[]> allowedCidrs;   // {network, mask} 预解析

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("MLLP 监听未启用");
            return;
        }
        allowedCidrs = parseAllowList(allowList);
        if (HipProfiles.isProduction(environment) && allowedCidrs.isEmpty()) {
            // 与 Mock 适配器门禁同理：生产环境宁可拒绝服务，不可静默开放无鉴权写入
            log.error("拒绝启动 MLLP 监听：pilot/prod 必须配置 hip.integration.mllp-allow 来源白名单"
                    + "（或设 hip.integration.mllp-enabled=false 显式关闭）");
            return;
        }
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port));
            running = true;
            // accept 独占一个线程，不与业务处理争池——池满时 accept 仍在收，逐个拒绝
            acceptorThread = new Thread(this::acceptLoop, "mllp-acceptor");
            acceptorThread.setDaemon(true);
            acceptorThread.start();
            log.info("MLLP 监听已启动，绑定 {}:{}，白名单 {} 条{}", bindAddress, port, allowedCidrs.size(),
                    allowedCidrs.isEmpty() ? "（未配置：仅回环放行以外全部放行，生产不允许此状态）" : "");
        } catch (IOException e) {
            log.error("MLLP 监听启动失败，端口 {}: {}", port, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                if (!allowed(socket.getInetAddress())) {
                    log.warn("MLLP 拒绝来源 {}（不在白名单）", socket.getInetAddress().getHostAddress());
                    closeQuietly(socket);
                    continue;
                }
                try {
                    pool.submit(() -> handle(socket));
                } catch (RejectedExecutionException e) {
                    log.warn("MLLP 处理池已满，拒绝连接 {}", socket.getInetAddress().getHostAddress());
                    closeQuietly(socket);
                }
            } catch (IOException e) {
                if (running) log.warn("MLLP accept 异常: {}", e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(30000);
            while (true) {
                byte[] frameBytes;
                try {
                    frameBytes = readFrame(in);
                } catch (FrameTooLargeException e) {
                    // AE 应答必须在 socket 关闭前发出，故在循环内处理而非外层 catch
                    log.warn("MLLP 单帧超过上限 {} 字节，断开 {}", maxFrame,
                            socket.getInetAddress().getHostAddress());
                    out.write(frame(buildAck("", false, "报文超长"), StandardCharsets.UTF_8));
                    out.flush();
                    break;
                }
                if (frameBytes == null) break;
                // 字符集按 MSH-18 声明解码（国产 LIS 常发 GBK，硬编码 UTF-8 会让中文结果乱码入库）
                var charset = declaredCharset(frameBytes);
                String raw = new String(frameBytes, charset);
                // 业务异常不能逃逸：不回 ACK 会让 LIS 中间件反复重发同一条报文
                boolean ok;
                String message;
                try {
                    var result = oruProcessingService.process(raw);
                    ok = result.ok();
                    message = result.message();
                } catch (Exception e) {
                    log.error("MLLP 报文处理异常", e);
                    ok = false;
                    message = "内部处理异常";
                }
                out.write(frame(buildAck(raw, ok, message), charset));
                out.flush();
            }
        } catch (IOException e) {
            log.warn("MLLP 连接异常: {}", e.getMessage());
        }
    }

    private static final class FrameTooLargeException extends IOException {
    }

    /** 读取一帧原始字节；流结束返回 null；超过 maxFrame 抛 FrameTooLargeException（原实现无限缓冲） */
    private byte[] readFrame(InputStream in) throws IOException {
        int b;
        // 寻找帧头
        do {
            b = in.read();
            if (b == -1) return null;
        } while (b != SB);
        var buf = new ByteArrayOutputStream();
        int prev = -1;
        while ((b = in.read()) != -1) {
            if (prev == EB && b == CR) {
                byte[] bytes = buf.toByteArray();
                return java.util.Arrays.copyOf(bytes, bytes.length - 1);   // 去掉尾部 EB
            }
            if (buf.size() >= maxFrame) {
                throw new FrameTooLargeException();
            }
            buf.write(b);
            prev = b;
        }
        return null;
    }

    /**
     * 按 MSH-18 取字符集：MSH 段本身 ASCII 兼容，先以 ISO-8859-1 探测字段。
     * 国标检验线常用 GBK/GB18030；缺省按 HL7 惯例视为 UTF-8（本院内网约定）。
     */
    public static java.nio.charset.Charset declaredCharset(byte[] frame) {
        try {
            String probe = new String(frame, StandardCharsets.ISO_8859_1);
            String[] f = probe.split("\r", 2)[0].split("\\|", -1);
            String cs = f.length > 17 ? f[17].trim().toUpperCase() : "";
            return switch (cs) {
                case "GB18030", "GBK", "GB2312" -> java.nio.charset.Charset.forName("GB18030");
                default -> StandardCharsets.UTF_8;
            };
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    // ---- 来源白名单 ----

    private boolean allowed(InetAddress addr) {
        if (addr.isLoopbackAddress()) return true;
        if (allowedCidrs.isEmpty()) return true;   // 未配置=不启用白名单（生产在 start 已拦截）
        byte[] a = addr.getAddress();
        if (a.length != 4) return false;           // 白名单仅定义 IPv4；IPv6 来源一律拒绝
        long ip = ipToLong(a);
        for (long[] cidr : allowedCidrs) {
            if ((ip & cidr[1]) == cidr[0]) return true;
        }
        return false;
    }

    public static List<long[]> parseAllowList(String spec) {
        var out = new ArrayList<long[]>();
        if (spec == null || spec.isBlank()) return out;
        for (String item : spec.split(",")) {
            String s = item.trim();
            if (s.isEmpty()) continue;
            try {
                int bits = 32;
                if (s.contains("/")) {
                    String[] parts = s.split("/");
                    s = parts[0];
                    bits = Integer.parseInt(parts[1]);
                }
                long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
                long net = ipToLong(InetAddress.getByName(s).getAddress()) & mask;
                out.add(new long[]{net, mask});
            } catch (Exception e) {
                throw new IllegalArgumentException("hip.integration.mllp-allow 配置非法：" + item, e);
            }
        }
        return out;
    }

    private static long ipToLong(byte[] a) {
        return ((a[0] & 0xFFL) << 24) | ((a[1] & 0xFFL) << 16) | ((a[2] & 0xFFL) << 8) | (a[3] & 0xFFL);
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private byte[] frame(String message, java.nio.charset.Charset charset) {
        byte[] body = message.getBytes(charset);   // ACK 用与请求相同的字符集回包
        byte[] framed = new byte[body.length + 3];
        framed[0] = SB;
        System.arraycopy(body, 0, framed, 1, body.length);
        framed[framed.length - 2] = EB;
        framed[framed.length - 1] = CR;
        return framed;
    }

    private String buildAck(String raw, boolean ok, String message) {
        String controlId = "";
        try {
            var msh = Hl7V2Message.parse(raw).first("MSH");
            if (msh != null) controlId = msh.field(10);
        } catch (Exception ignored) {
            // 解析失败仍需应答 AE
        }
        String code = ok ? "AA" : "AE";
        return "MSH|^~\\&|HIP|HOSPITAL|LIS|LAB|" + System.currentTimeMillis()
                + "||ACK|" + controlId + "|P|2.5\r"
                + "MSA|" + code + "|" + controlId + (ok ? "" : "|" + message) + "\r";
    }

    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }
}
