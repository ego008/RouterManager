package com.example.routermanager;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NulFilterProxy {
    private final int localPort;
    private final String routerHost;
    private final int routerPort;
    private ServerSocket serverSocket;
    private boolean running = false;

    public NulFilterProxy(int localPort, String routerHost, int routerPort) {
        this.localPort = localPort;
        this.routerHost = routerHost;
        this.routerPort = routerPort;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(localPort);
                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handleClient(clientSocket)).start();
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }).start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();

            HttpRequest request = parseRequest(clientIn);
            if (request == null) {
                clientSocket.close();
                return;
            }

            // 连接真实路由器
            Socket routerSocket = new Socket(routerHost, routerPort);
            OutputStream routerOut = routerSocket.getOutputStream();
            InputStream routerIn = routerSocket.getInputStream();

            // 转发请求
            String forwardRequest = buildForwardRequest(request);
            routerOut.write(forwardRequest.getBytes(StandardCharsets.UTF_8));
            if (request.body != null) {
                routerOut.write(request.body);
            }
            routerOut.flush();

            // 过滤响应并返回给客户端
            filterAndRelay(routerIn, clientOut);

            routerSocket.close();
            clientSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    // 解析 HTTP 请求
    private HttpRequest parseRequest(InputStream in) throws IOException {
        // 简单按行读取
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        // 读取请求行
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) return null;

        HttpRequest req = new HttpRequest();
        req.method = parts[0];
        req.path = parts[1];
        req.protocol = parts[2];

        // 读取头部
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                req.headers.put(key, value);
            }
        }

        // 读取 body（如果有 Content-Length）
        String contentLen = req.headers.get("Content-Length");
        if (contentLen != null) {
            int len = Integer.parseInt(contentLen);
            req.body = new byte[len];
            int read = 0;
            while (read < len) {
                int n = in.read(req.body, read, len - read);
                if (n == -1) break;
                read += n;
            }
        }
        return req;
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                // 去掉最后的 \r\n，返回字符串
                byte[] bytes = baos.toByteArray();
                if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
                    return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
                }
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
            baos.write(c);
            prev = c;
        }
        return null;
    }

    // 构建转发请求行和头部，替换 Host 为真实路由器
    private String buildForwardRequest(HttpRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.method).append(" ").append(request.path).append(" ").append(request.protocol).append("\r\n");
        for (Map.Entry<String, String> entry : request.headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.equalsIgnoreCase("Host")) {
                sb.append("Host: ").append(routerHost);
                if (routerPort != 80) {
                    sb.append(":").append(routerPort);
                }
                sb.append("\r\n");
            } else {
                sb.append(key).append(": ").append(value).append("\r\n");
            }
        }
        sb.append("\r\n");
        return sb.toString();
    }

    // 过滤 NUL 并修正 Content-Length，还要替换重定向中的路由器地址为代理地址
    private void filterAndRelay(InputStream routerIn, OutputStream clientOut) throws IOException {
        ByteArrayOutputStream rawBuf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = routerIn.read(tmp)) != -1) {
            rawBuf.write(tmp, 0, n);
        }
        byte[] rawResponse = rawBuf.toByteArray();

        // 删除所有 0x00 字节
        ByteArrayOutputStream cleanBuf = new ByteArrayOutputStream();
        for (byte b : rawResponse) {
            if (b != 0) cleanBuf.write(b);
        }
        byte[] cleanResponse = cleanBuf.toByteArray();

        // 查找 \r\n\r\n 分隔头部和主体
        byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        int headerEndIdx = indexOf(cleanResponse, headerEnd);
        if (headerEndIdx == -1) {
            // 没有找到，直接原样返回（异常情况）
            clientOut.write(rawResponse);
            clientOut.flush();
            return;
        }

        byte[] headerBytes = Arrays.copyOfRange(cleanResponse, 0, headerEndIdx);
        byte[] bodyBytes = Arrays.copyOfRange(cleanResponse, headerEndIdx + 4, cleanResponse.length);

        String headerStr = new String(headerBytes, StandardCharsets.ISO_8859_1);

        // 修正 Content-Length 为实际 body 长度
        headerStr = headerStr.replaceAll("(?i)Content-Length: .*", "Content-Length: " + bodyBytes.length);

        // 替换 Location 头中的路由器 IP 为代理 IP
        String routerOrigin = "http://" + routerHost;
        if (routerPort != 80) routerOrigin += ":" + routerPort;
        String proxyOrigin = "http://127.0.0.1:" + localPort;
        headerStr = headerStr.replace(routerOrigin, proxyOrigin);
        // 也处理不带端口的情况
        if (routerPort == 80) {
            headerStr = headerStr.replace("http://" + routerHost + "/", proxyOrigin + "/");
        }

        // 重新组合并写回客户端
        clientOut.write(headerStr.getBytes(StandardCharsets.ISO_8859_1));
        clientOut.write("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        clientOut.write(bodyBytes);
        clientOut.flush();
    }

    private int indexOf(byte[] array, byte[] target) {
        outer:
        for (int i = 0; i <= array.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // 简单的请求数据类
    static class HttpRequest {
        String method;
        String path;
        String protocol;
        Map<String, String> headers = new HashMap<>();
        byte[] body;
    }
}