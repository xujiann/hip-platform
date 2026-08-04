package cn.hip.platform.integration.mllp;

import cn.hip.platform.integration.hl7.Hl7V2Message;
import cn.hip.platform.integration.service.OruProcessingService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MLLP/TCP 监听：LIS 设备/中间件的标准 HL7 传输承载。
 * 帧格式：0x0B <报文> 0x1C 0x0D；应答 ACK（MSA|AA/AE）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MllpServer {

    private static final byte SB = 0x0B;
    private static final byte EB = 0x1C;
    private static final byte CR = 0x0D;

    private final OruProcessingService oruProcessingService;

    @Value("${hip.integration.mllp-enabled:true}")
    private boolean enabled;

    @Value("${hip.integration.mllp-port:2575}")
    private int port;

    private ServerSocket serverSocket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            log.info("MLLP 监听未启用");
            return;
        }
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            pool.submit(this::acceptLoop);
            log.info("MLLP 监听已启动，端口 {}", port);
        } catch (IOException e) {
            log.error("MLLP 监听启动失败，端口 {}: {}", port, e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handle(socket));
            } catch (IOException e) {
                if (running) log.warn("MLLP accept 异常: {}", e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket; InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(30000);
            String raw;
            while ((raw = readFrame(in)) != null) {
                var result = oruProcessingService.process(raw);
                out.write(frame(buildAck(raw, result.ok(), result.message())));
                out.flush();
            }
        } catch (IOException e) {
            log.warn("MLLP 连接异常: {}", e.getMessage());
        }
    }

    /** 读取一帧；流结束返回 null */
    private String readFrame(InputStream in) throws IOException {
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
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            buf.write(b);
            prev = b;
        }
        return null;
    }

    private byte[] frame(String message) {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
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
