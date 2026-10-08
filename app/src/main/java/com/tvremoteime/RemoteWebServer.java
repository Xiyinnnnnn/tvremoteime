package com.tvremoteime;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import android.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny LAN HTTP + WebSocket server. No cloud service, no account, no phone APK.
 * The browser stays connected and can send the complete input field contents.
 */
final class RemoteWebServer {
    static final int PORT = 9978;
    private static volatile RemoteWebServer instance;

    private final Context context;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Socket> webSockets = new CopyOnWriteArrayList<>();
    private volatile Listener listener;
    private volatile ServerSocket server;

    interface Listener {
        void onText(String text);
        void onAction(String action);
        void onClientCountChanged(int count);
    }

    static synchronized void ensureStarted(Context c) {
        if (instance == null) {
            instance = new RemoteWebServer(c.getApplicationContext());
            instance.start();
        }
    }

    static String getUrl(Context c) {
        return "http://" + NetUtil.localIpv4() + ":" + PORT + "/";
    }

    static void setListener(Listener l) {
        if (instance != null) instance.listener = l;
    }

    static void clearListener(Listener l) {
        if (instance != null && instance.listener == l) instance.listener = null;
    }

    static int clientCount() {
        return instance == null ? 0 : instance.webSockets.size();
    }

    private RemoteWebServer(Context c) {
        context = c;
    }

    private void start() {
        clients.execute(() -> {
            try {
                server = new ServerSocket(PORT, 24, InetAddress.getByName("0.0.0.0"));
                while (!server.isClosed()) {
                    final Socket socket = server.accept();
                    clients.execute(() -> handle(socket));
                }
            } catch (IOException ignored) {
                // Server can be recreated after the process restarts.
            }
        });
    }

    private void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) {
                closeQuietly(socket);
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }

            if ("websocket".equalsIgnoreCase(headers.get("upgrade"))
                    && requestLine.startsWith("GET /ws")) {
                upgradeWebSocket(socket, in, headers.get("sec-websocket-key"));
                return;
            }

            if (requestLine.startsWith("GET / ") || requestLine.startsWith("GET /index.html")) {
                byte[] body = html().getBytes(StandardCharsets.UTF_8);
                writeHttp(socket, 200, "text/html; charset=utf-8", body);
                return;
            }

            writeHttp(socket, 404, "text/plain; charset=utf-8",
                    "404".getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            closeQuietly(socket);
        }
    }

    private void upgradeWebSocket(Socket socket, InputStream in, String key) throws Exception {
        if (key == null || key.isEmpty()) {
            closeQuietly(socket);
            return;
        }
        String accept = Base64.encodeToString(sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        OutputStream out = socket.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        webSockets.add(socket);
        notifyClientCount();
        try {
            readWebSocket(socket, in);
        } finally {
            webSockets.remove(socket);
            notifyClientCount();
            closeQuietly(socket);
        }
    }

    private void readWebSocket(Socket socket, InputStream in) throws IOException {
        ByteArrayOutputStream fragmented = null;
        int fragmentedOpcode = 0;

        while (!socket.isClosed()) {
            int b1 = in.read();
            if (b1 < 0) return;
            int b2 = in.read();
            if (b2 < 0) return;

            int fin = b1 & 0x80;
            int opcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            long length = b2 & 0x7F;

            if (length == 126) {
                length = ((in.read() & 0xFFL) << 8) | (in.read() & 0xFFL);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | (in.read() & 0xFFL);
            }
            if (length > 256 * 1024) return;

            byte[] mask = masked ? readExactly(in, 4) : null;
            byte[] payload = readExactly(in, (int) length);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }

            if (opcode == 8) {
                sendFrame(socket, 8, new byte[0]);
                return;
            }
            if (opcode == 9) {
                sendFrame(socket, 10, payload);
                continue;
            }
            if (opcode == 10) continue;

            if (opcode == 1) {
                if (fin != 0) {
                    handleTextPayload(payload);
                } else {
                    fragmentedOpcode = 1;
                    fragmented = new ByteArrayOutputStream();
                    fragmented.write(payload);
                }
                continue;
            }

            if (opcode == 0 && fragmented != null) {
                fragmented.write(payload);
                if (fin != 0 && fragmentedOpcode == 1) {
                    handleTextPayload(fragmented.toByteArray());
                    fragmented = null;
                    fragmentedOpcode = 0;
                }
            }
        }
    }

    private void handleTextPayload(byte[] payload) {
        String message = new String(payload, StandardCharsets.UTF_8);
        String type = jsonString(message, "type");
        Listener l = listener;
        if (l == null) return;
        if ("text".equals(type)) {
            String text = jsonString(message, "text");
            if (text != null) l.onText(text);
        } else if ("action".equals(type)) {
            String action = jsonString(message, "action");
            if (action != null) l.onAction(action);
        }
    }

    static void sendTextState(String text) {
        if (instance == null) return;
        String safe = jsonEscape(text == null ? "" : text);
        instance.sendToAll("{\"type\":\"text\",\"text\":\"" + safe + "\"}");
    }

    static void sendToAll(String json) {
        if (instance == null) return;
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        for (Socket socket : instance.webSockets) {
            try {
                sendFrame(socket, 1, data);
            } catch (IOException e) {
                instance.webSockets.remove(socket);
                closeQuietly(socket);
            }
        }
    }

    private void notifyClientCount() {
        Listener l = listener;
        if (l != null) l.onClientCountChanged(webSockets.size());
    }

    private static void sendFrame(Socket socket, int opcode, byte[] data) throws IOException {
        OutputStream out = socket.getOutputStream();
        int n = data.length;
        out.write(0x80 | (opcode & 0x0F));
        if (n < 126) {
            out.write(n);
        } else if (n <= 0xFFFF) {
            out.write(126);
            out.write((n >> 8) & 0xFF);
            out.write(n & 0xFF);
        } else {
            out.write(127);
            long len = n;
            for (int i = 7; i >= 0; i--) out.write((int) (len >>> (8 * i)) & 0xFF);
        }
        out.write(data);
        out.flush();
    }

    private static void writeHttp(Socket socket, int code, String type, byte[] body) throws IOException {
        OutputStream out = socket.getOutputStream();
        String reason = code == 200 ? "OK" : "Not Found";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
        closeQuietly(socket);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("HTTP header too large");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) throw new IOException("Unexpected EOF");
            off += n;
        }
        return data;
    }

    private static byte[] sha1(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-1").digest(data);
    }

    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int start = 0;
        while ((start = json.indexOf(needle, start)) >= 0) {
            int colon = json.indexOf(':', start + needle.length());
            if (colon < 0) return null;
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) != '"') return null;

            StringBuilder out = new StringBuilder();
            for (i++; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (ch == '"') return out.toString();
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (++i >= json.length()) return null;
                char esc = json.charAt(i);
                switch (esc) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 >= json.length()) return null;
                        try {
                            int code = Integer.parseInt(json.substring(i + 1, i + 5), 16);
                            out.append((char) code);
                            i += 4;
                        } catch (NumberFormatException e) {
                            return null;
                        }
                        break;
                    default: return null;
                }
            }
            return null;
        }
        return null;
    }

    static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private String html() {
        return "<!doctype html><html lang='zh-CN'><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<meta name='theme-color' content='#111'>"
                + "<meta name='mobile-web-app-capable' content='yes'>"
                + "<title>TVRemoteIME Lite</title>"
                + "<style>"
                + "*{box-sizing:border-box}body{font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;"
                + "background:#111;color:#eee;margin:0;padding:20px;max-width:720px;margin-inline:auto}"
                + ".top{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:12px}"
                + "h2{font-size:22px;margin:0}.state{font-size:13px;color:#8dd5a7}.state.bad{color:#ff9b9b}"
                + "textarea{width:100%;height:140px;resize:none;background:#1d1d1d;color:#fff;border:1px solid #444;"
                + "border-radius:14px;padding:16px;font-size:22px;line-height:1.4;outline:none}"
                + "textarea:focus{border-color:#888}.row{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}"
                + "button{flex:1 1 100px;padding:13px 12px;border:0;border-radius:12px;font-size:17px;background:#2a2a2a;color:#fff}"
                + ".hint{font-size:13px;color:#999;line-height:1.5;margin-top:12px}.pill{padding:6px 10px;border-radius:999px;background:#222;font-size:12px;color:#aaa}"
                + "</style></head><body>"
                + "<div class='top'><h2>TVRemoteIME Lite</h2><div id='state' class='state'>连接中…</div></div>"
                + "<div class='pill'>局域网直连 · 不经过云端</div>"
                + "<textarea id='text' inputmode='text' autocomplete='off' autocapitalize='none' spellcheck='false' "
                + "placeholder='直接用手机输入中文、英文、数字…'></textarea>"
                + "<div class='row'><button onclick=\"action('enter')\">确定 / 搜索</button></div>"
                + "<div class='hint'>这个页面可以一直开着。电视重连 Wi‑Fi 后，页面会自动尝试重连；无需重复安装手机 App。</div>"
                + "<script>"
                + "const box=document.getElementById('text'),state=document.getElementById('state');let ws=null;let timer=0;let applying=false;"
                + "function setState(ok){state.textContent=ok?'已连接电视':'已断开，自动重连…';state.className='state'+(ok?'':' bad')}"
                + "function sendText(){if(applying||!ws||ws.readyState!==1)return;ws.send(JSON.stringify({type:'text',text:box.value}))}"
                + "function connect(){clearTimeout(timer);try{ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws')}catch(e){timer=setTimeout(connect,900);return}"
                + "ws.onopen=()=>{setState(true);box.focus();sendText()};ws.onclose=()=>{setState(false);timer=setTimeout(connect,900)};ws.onerror=()=>{try{ws.close()}catch(e){}};"
                + "ws.onmessage=e=>{try{const m=JSON.parse(e.data);if(m.type==='text'){applying=true;box.value=m.text||'';applying=false}}catch(_){} }}"
                + "box.addEventListener('input',sendText);box.addEventListener('compositionupdate',sendText);"
                + "function action(a){if(ws&&ws.readyState===1)ws.send(JSON.stringify({type:'action',action:a}))}"
                + "connect();"
                + "</script></body></html>";
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) {}
    }
}
