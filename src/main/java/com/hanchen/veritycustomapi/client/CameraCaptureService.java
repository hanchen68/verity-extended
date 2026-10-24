package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 抓一帧画面成 JPEG、转 base64 data URI，给视觉模型用。
 *
 * 后端按 powershell(WinRT，免安装默认)、ffmpeg(DirectShow，兼容最好)、
 * http(直连返回 JPEG 的地址) 的顺序试。采集是同步阻塞的，调用方自己放到工作线程；
 * 同一时刻只允许一路采集，所以加了锁。
 */
public final class CameraCaptureService {

    public enum ImageSource {
        CAMERA,
        SCREEN
    }

    /** 一次采集的结果。base64Jpeg 是主画面（来源看 primarySource），screenBase64Jpeg 是附带的第二张截屏。 */
    public record CaptureResult(String base64Jpeg, String mimeType, String backend, String error,
                                ImageSource primarySource, String screenBase64Jpeg, String screenMimeType) {

        public boolean ok() {
            return base64Jpeg != null && !base64Jpeg.isBlank();
        }

        public boolean hasCamera() {
            return ok() && primarySource == ImageSource.CAMERA;
        }

        /** 主画面或附带画面中是否包含游戏内截屏。 */
        public boolean hasScreen() {
            return (ok() && primarySource == ImageSource.SCREEN) || hasAttachedScreen();
        }

        /** 是否有作为第二张图片发送的游戏内截屏。 */
        public boolean hasAttachedScreen() {
            return screenBase64Jpeg != null && !screenBase64Jpeg.isBlank();
        }

        public String dataUri() {
            return ok() ? "data:" + mimeType + ";base64," + base64Jpeg : null;
        }

        public String screenDataUri() {
            return hasAttachedScreen() ? "data:" + screenMimeType + ";base64," + screenBase64Jpeg : null;
        }

        /** 附加一张游戏内画面。 */
        public CaptureResult withScreen(String base64, String screenMime) {
            if (base64 == null || base64.isBlank()) {
                return this;
            }
            return new CaptureResult(base64Jpeg, mimeType, backend, error, primarySource, base64, screenMime);
        }

        public static CaptureResult cameraJpeg(String base64, String backend) {
            return new CaptureResult(base64, "image/jpeg", backend, null, ImageSource.CAMERA, null, null);
        }

        public static CaptureResult screenJpeg(String base64) {
            return new CaptureResult(base64, "image/jpeg", "screen", null, ImageSource.SCREEN, null, null);
        }

        public static CaptureResult failure(String backend, String error) {
            return new CaptureResult(null, "image/jpeg", backend, error, null, null, null);
        }
    }

    private static final Object CAPTURE_LOCK = new Object();

    private CameraCaptureService() {
    }

    /**
     * 按配置采一帧，工作线程调用，可能卡几秒。来源看 camera_vision.source：
     * 真实摄像头、游戏屏幕，或两者都要（这时第二张图会随结果返回）。
     */
    public static CaptureResult capture() {
        if (!ConfigManager.isCameraEnabled()) {
            return CaptureResult.failure("disabled", "视觉功能未启用（配置 → 摄像头视觉）");
        }
        synchronized (CAPTURE_LOCK) {
            boolean wantCamera = ConfigManager.isCameraSourceCamera();
            boolean wantScreen = ConfigManager.isCameraSourceScreen();
            CaptureResult camera = wantCamera ? captureCamera() : null;
            CaptureResult screen = wantScreen ? GameScreenCaptureService.captureScreen() : null;
            return combine(camera, screen);
        }
    }

    /** 走原有后端链采集真实摄像头画面。 */
    private static CaptureResult captureCamera() {
        String backend = normalizeBackend(ConfigManager.getCameraBackend());
        if (!"auto".equals(backend)) {
            return captureWith(backend);
        }
        // auto：依次尝试，收集每个后端的失败原因，返回最有用的一条
        StringBuilder attempts = new StringBuilder();
        for (String candidate : new String[]{"powershell", "ffmpeg", "http"}) {
            CaptureResult result = captureWith(candidate);
            if (result.ok()) {
                if (attempts.length() > 0) {
                    OllamaProxyMod.LOGGER.info("摄像头自动后端选用 {}（前序失败：{}）", candidate, attempts);
                }
                return result;
            }
            if (attempts.length() > 0) attempts.append(" | ");
            attempts.append(candidate).append(": ").append(result.error());
        }
        return CaptureResult.failure("auto", attempts.toString());
    }

    /**
     * 合并两个来源的结果：都有就以摄像头为主、游戏画面作附带；
     * 只有一个可用就直接用它；都失败就把两边的失败原因拼一起。
     */
    private static CaptureResult combine(CaptureResult camera, CaptureResult screen) {
        boolean cameraOk = camera != null && camera.ok();
        boolean screenOk = screen != null && screen.ok();
        if (cameraOk && screenOk) {
            return camera.withScreen(screen.base64Jpeg(), screen.mimeType());
        }
        if (cameraOk) {
            return camera;
        }
        if (screenOk) {
            return screen;
        }
        if (camera == null && screen == null) {
            return CaptureResult.failure("none", "没有启用任何画面来源");
        }
        StringBuilder reason = new StringBuilder();
        if (camera != null) reason.append("摄像头: ").append(camera.error());
        if (screen != null) {
            if (reason.length() > 0) reason.append(" | ");
            reason.append("游戏画面: ").append(screen.error());
        }
        return CaptureResult.failure(camera != null ? camera.backend() : "screen", reason.toString());
    }

    /** 供配置界面"测试"按钮使用：跑一次采集并把结果写进 report。 */
    public static boolean testCapture(StringBuilder report) {
        CaptureResult result = capture();
        if (result.ok()) {
            report.append("成功：后端=").append(result.backend())
                    .append("，主画面来源=").append(result.hasCamera() ? "摄像头" : "游戏画面")
                    .append("，约 ").append(estimateBytes(result.base64Jpeg())).append(" 字节");
            if (result.hasAttachedScreen()) {
                report.append("，附带游戏画面约 ").append(estimateBytes(result.screenBase64Jpeg())).append(" 字节");
            }
            return true;
        }
        report.append("失败：").append(result.error());
        return false;
    }

    private static String normalizeBackend(String raw) {
        if (raw == null || raw.isBlank()) return "auto";
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "ffmpeg", "powershell", "http", "auto" -> value;
            default -> "auto";
        };
    }

    private static CaptureResult captureWith(String backend) {
        try {
            return switch (backend) {
                case "powershell" -> captureByPowerShell();
                case "ffmpeg" -> captureByFfmpeg();
                case "http" -> captureByHttp();
                default -> CaptureResult.failure(backend, "未知后端: " + backend);
            };
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return CaptureResult.failure(backend, message);
        }
    }

    // ==================== PowerShell / WinRT 后端 ====================

    /**
     * 用 Windows 自带的 WinRT MediaCapture 采集。
     * 脚本按 UTF-16LE 转 base64 再传，省得跟命令行引号转义较劲。
     */
    private static CaptureResult captureByPowerShell() throws Exception {
        if (!isWindows()) {
            return CaptureResult.failure("powershell", "PowerShell 后端仅支持 Windows");
        }
        int width = ConfigManager.getCameraWidth();
        int height = ConfigManager.getCameraHeight();
        int timeoutMs = ConfigManager.getCameraCaptureTimeoutMs();
        Path outFile = Files.createTempFile("verity-cam-", ".jpg");
        try {
            // 参数直接内联进脚本：-EncodedCommand 下额外 argv 是否进 $args 各版本行为不一致，内联最稳。
            // outFile 是我们自己生成的临时路径，width/height 是整数，无注入风险。
            String script = POWERSHELL_CAPTURE_SCRIPT
                    .replace("__OUT__", escapePowerShellLiteral(outFile.toAbsolutePath().toString()))
                    .replace("__W__", String.valueOf(width))
                    .replace("__H__", String.valueOf(height))
                    .replace("__DEVICE__", escapePowerShellLiteral(
                            ConfigManager.getCameraDeviceName()));
            // -EncodedCommand 需要 UTF-16LE + base64
            String encoded = Base64.getEncoder()
                    .encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded);
            builder.redirectErrorStream(true);

            String output = runProcess(builder, timeoutMs + 2000);
            byte[] jpeg = readIfNonEmpty(outFile);
            if (jpeg != null) {
                logChosenDevice(output);
                return success(jpeg, "powershell");
            }
            return CaptureResult.failure("powershell",
                    "未产出画面。" + firstLines(output, 3)
                            + "（请确认：设置→隐私和安全性→相机 已允许桌面应用访问摄像头）");
        } finally {
            try { Files.deleteIfExists(outFile); } catch (Throwable ignored) { Silent.ignore("CameraCaptureService", ignored); }
        }
    }

    private static final String POWERSHELL_CAPTURE_SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "$out = '__OUT__'",
            "$w = __W__",
            "$h = __H__",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null",
            "$ext = [System.WindowsRuntimeSystemExtensions]",
            "$asTaskOp = ($ext.GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
            "$asTaskAct = ($ext.GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' })[0]",
            "function AwaitOp($op, $type) { $m = $asTaskOp.MakeGenericMethod($type); $t = $m.Invoke($null, @($op)); $t.Wait(-1) | Out-Null; return $t.Result }",
            "function AwaitAct($act) { $t = $asTaskAct.Invoke($null, @($act)); $t.Wait(-1) | Out-Null }",
            "[Windows.Media.Capture.MediaCapture, Windows.Media, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Media.Capture.MediaCaptureInitializationSettings, Windows.Media, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Media.MediaProperties.ImageEncodingProperties, Windows.Media, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Media.Capture.StreamingCaptureMode, Windows.Media, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Storage.Streams.DataReader, Windows.Storage, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType=WindowsRuntime] | Out-Null",
            "[Windows.Devices.Enumeration.DeviceClass, Windows.Devices.Enumeration, ContentType=WindowsRuntime] | Out-Null",
            // 🔴 必须显式选设备：WinRT 不给 VideoDeviceId 时用「系统默认」，
            // 而装了手机投屏 / OBS 虚拟摄像头 / 组合摄像头之后，默认很容易落到一个
            // 永远输出纯黑的设备上——日志显示采集成功，AI 却只能看到一片黑。
            "$want = '__DEVICE__'",
            "$chosen = $null",
            "$devs = $null",
            "try { $devs = AwaitOp ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync([Windows.Devices.Enumeration.DeviceClass]::VideoCapture)) ([Windows.Devices.Enumeration.DeviceInformationCollection]) } catch { }",
            "if ($want -ne '' -and $devs -ne $null) { $chosen = $devs | Where-Object { $_.Name -eq $want } | Select-Object -First 1 }",
            "if ($chosen -eq $null -and $devs -ne $null) {",
            "  $cands = @($devs | Where-Object { $_.Name -notmatch 'virtual|obs|vcam|manycam|snap' })",
            "  if ($cands.Count -eq 0) { $cands = @($devs) }",
            "  if ($cands.Count -gt 1) {",
            // 自动模式：逐台试拍一张、量亮度，取第一台不是纯黑的。
            // 手机上投屏出来的「前置/后置/组合」摄像头在手机没连着时永远是纯黑，
            // 只看名字没法分辨，只能实拍验证。最多试 3 台，避免拖过采集超时。
            "    $tried = 0",
            "    foreach ($d in $cands) {",
            "      if ($tried -ge 3) { break }",
            "      $tried++",
            "      try {",
            "        $ps = New-Object Windows.Media.Capture.MediaCaptureInitializationSettings",
            "        $ps.StreamingCaptureMode = [Windows.Media.Capture.StreamingCaptureMode]::Video",
            "        $ps.MediaCategory = [Windows.Media.Capture.MediaCategory]::Other",
            "        $ps.VideoDeviceId = $d.Id",
            "        $pc = New-Object Windows.Media.Capture.MediaCapture",
            "        AwaitAct ($pc.InitializeAsync($ps))",
            "        $pp = [Windows.Media.MediaProperties.ImageEncodingProperties]::CreateJpeg()",
            "        $pp.Width = [uint32]320",
            "        $pp.Height = [uint32]240",
            "        $pst = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream",
            "        AwaitAct ($pc.CapturePhotoToStreamAsync($pp, $pst))",
            "        $psz = [uint32]$pst.Size",
            "        $pst.Seek(0)",
            "        $pr = New-Object Windows.Storage.Streams.DataReader($pst.GetInputStreamAt(0))",
            "        AwaitOp ($pr.LoadAsync($psz)) ([uint32]) | Out-Null",
            "        $pb = New-Object byte[] $psz",
            "        $pr.ReadBytes($pb)",
            "        $pr.Dispose(); $pst.Dispose(); $pc.Dispose()",
            // 纯黑 JPEG 的体积有下限但内容是零；用「字节里有没有非零亮度」粗判即可：
            // JPEG 头尾都是固定字段，纯黑图的中段几乎全零。
            "        $nonzero = 0",
            "        foreach ($v in $pb) { if ($v -ne 0) { $nonzero++ } }",
            "        if ($nonzero -gt ($pb.Length * 0.12)) { $chosen = $d; break }",
            "      } catch { }",
            "    }",
            "  }",
            "  if ($chosen -eq $null) { $chosen = $cands[0] }",
            "}",
            "$settings = New-Object Windows.Media.Capture.MediaCaptureInitializationSettings",
            "$settings.StreamingCaptureMode = [Windows.Media.Capture.StreamingCaptureMode]::Video",
            "$settings.MediaCategory = [Windows.Media.Capture.MediaCategory]::Other",
            "if ($chosen -ne $null) { $settings.VideoDeviceId = $chosen.Id }",
            "$capture = New-Object Windows.Media.Capture.MediaCapture",
            "AwaitAct ($capture.InitializeAsync($settings))",
            // 🔴 必须等相机曝光稳定再拍：WinRT 初始化后立刻 CapturePhoto 常常拿到一张
            // 严重欠曝的首帧。实测同一台相机：立刻拍 24989 字节、等 600ms 拍 76812 字节，
            // 前者暗到 AI 只能回答「画面里全是黑的」。这 600ms 换来的是「能不能看见」。
            "Start-Sleep -Milliseconds 600",
            "$props = [Windows.Media.MediaProperties.ImageEncodingProperties]::CreateJpeg()",
            "$props.Width = [uint32]$w",
            "$props.Height = [uint32]$h",
            "$stream = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream",
            "AwaitAct ($capture.CapturePhotoToStreamAsync($props, $stream))",
            "$capture.Dispose()",
            "$size = [uint32]$stream.Size",
            "$stream.Seek(0)",
            "$reader = New-Object Windows.Storage.Streams.DataReader($stream.GetInputStreamAt(0))",
            "AwaitOp ($reader.LoadAsync($size)) ([uint32]) | Out-Null",
            "$bytes = New-Object byte[] $size",
            "$reader.ReadBytes($bytes)",
            "$reader.Dispose()",
            "$stream.Dispose()",
            "[System.IO.File]::WriteAllBytes($out, $bytes)",
            // 回报实际用了哪个设备：出黑图时能直接从日志判断是不是选错了摄像头
            "Write-Output ('DEVICE ' + $(if ($chosen -ne $null) { $chosen.Name } else { 'default' }))",
            "Write-Output ('OK ' + $bytes.Length)");

    // ==================== ffmpeg / DirectShow 后端 ====================

    private static CaptureResult captureByFfmpeg() throws Exception {
        String ffmpeg = resolveFfmpeg();
        if (ffmpeg == null) {
            return CaptureResult.failure("ffmpeg",
                    "找不到 ffmpeg。请在配置里填写 ffmpegPath，或把 ffmpeg 加入 PATH");
        }
        String device = ConfigManager.getCameraDeviceName();
        if (device == null || device.isBlank()) {
            device = autoDetectDshowDevice(ffmpeg);
            if (device == null) {
                return CaptureResult.failure("ffmpeg",
                        "未检测到 DirectShow 视频设备。请在配置里手动填写 deviceName");
            }
        }
        int width = ConfigManager.getCameraWidth();
        int height = ConfigManager.getCameraHeight();
        int quality = ConfigManager.getCameraJpegQuality();
        int timeoutMs = ConfigManager.getCameraCaptureTimeoutMs();

        Path outFile = Files.createTempFile("verity-cam-ffmpeg-", ".jpg");
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    ffmpeg,
                    "-hide_banner", "-loglevel", "error",
                    "-f", "dshow",
                    "-i", "video=" + device,
                    "-frames:v", "1",
                    "-vf", "scale=" + width + ":" + height,
                    "-q:v", String.valueOf(Math.max(2, Math.min(31, 31 - (quality / 4)))),
                    "-f", "image2",
                    "-y",
                    outFile.toAbsolutePath().toString());
            builder.redirectErrorStream(true);
            String output = runProcess(builder, timeoutMs);
            byte[] jpeg = readIfNonEmpty(outFile);
            if (jpeg != null) {
                return success(jpeg, "ffmpeg");
            }
            return CaptureResult.failure("ffmpeg", "ffmpeg 未产出画面。" + firstLines(output, 4));
        } finally {
            try { Files.deleteIfExists(outFile); } catch (Throwable ignored) { Silent.ignore("CameraCaptureService", ignored); }
        }
    }

    private static String resolveFfmpeg() {
        String configured = ConfigManager.getCameraFfmpegPath();
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim());
            if (Files.isRegularFile(path)) return path.toAbsolutePath().toString();
        }
        // 退回 PATH 探测
        for (String candidate : new String[]{"ffmpeg", "ffmpeg.exe"}) {
            try {
                ProcessBuilder probe = new ProcessBuilder(candidate, "-version");
                probe.redirectErrorStream(true);
                Process process = probe.start();
                boolean finished = process.waitFor(3, TimeUnit.SECONDS);
                if (finished && process.exitValue() == 0) {
                    try (InputStream is = process.getInputStream()) { is.readAllBytes(); }
                    return candidate;
                }
                process.destroyForcibly();
            } catch (Throwable ignored) { Silent.ignore("CameraCaptureService", ignored); }
        }
        return null;
    }

    /** 通过 {@code ffmpeg -list_devices} 找到第一个 video 设备名。 */
    private static String autoDetectDshowDevice(String ffmpeg) {
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
            builder.redirectErrorStream(true);
            String output = runProcess(builder, 5000);
            // 形如：[dshow @ ...] "Integrated Camera" (video)
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"([^\"]+)\"\\s*\\((video|audio)\\)")
                    .matcher(output);
            while (matcher.find()) {
                if ("video".equalsIgnoreCase(matcher.group(2))) {
                    return matcher.group(1);
                }
            }
        } catch (Throwable ignored) { Silent.ignore("CameraCaptureService", ignored); }
        return null;
    }

    // ==================== HTTP 快照后端 ====================

    private static CaptureResult captureByHttp() {
        String url = ConfigManager.getCameraHttpUrl();
        if (url == null || url.isBlank()) {
            return CaptureResult.failure("http", "未配置 httpSnapshotUrl");
        }
        int timeoutMs = ConfigManager.getCameraCaptureTimeoutMs();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url.trim()).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "image/jpeg,image/*");
            int status = conn.getResponseCode();
            if (status >= 400) {
                return CaptureResult.failure("http", "HTTP " + status);
            }
            byte[] body;
            try (InputStream is = conn.getInputStream()) {
                body = is.readAllBytes();
            }
            if (body.length < 128) {
                return CaptureResult.failure("http", "返回内容过小（" + body.length + " 字节），可能不是图片");
            }
            // 简单校验 JPEG magic
            if (!(body[0] == (byte) 0xFF && body[1] == (byte) 0xD8)) {
                return CaptureResult.failure("http", "返回内容不是 JPEG（缺少 FFD8 头）");
            }
            return success(body, "http");
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return CaptureResult.failure("http", message);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== 公共工具 ====================

    private static CaptureResult success(byte[] jpeg, String backend) {
        return CaptureResult.cameraJpeg(Base64.getEncoder().encodeToString(jpeg), backend);
    }

    private static byte[] readIfNonEmpty(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            long size = Files.size(file);
            if (size < 128) return null;
            return Files.readAllBytes(file);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String runProcess(ProcessBuilder builder, int timeoutMs) throws Exception {
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = is.read(chunk)) >= 0) {
                    if (buffer.size() < 64 * 1024) buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
            }
        }, "Verity-Camera-Pump");
        pump.setDaemon(true);
        pump.start();

        boolean finished = process.waitFor(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("采集超时（>" + timeoutMs + "ms）");
        }
        pump.join(500);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String firstLines(String text, int maxLines) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.replace('\r', '\n').split("\n");
        StringBuilder builder = new StringBuilder();
        int taken = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            if (builder.length() > 0) builder.append(" / ");
            builder.append(line.trim());
            if (++taken >= maxLines) break;
        }
        return builder.toString();
    }

    private static int estimateBytes(String base64) {
        if (base64 == null) return 0;
        return (int) (base64.length() * 3L / 4L);
    }

    /** 把路径里的单引号转义成 PowerShell 单引号字面量可用的形式。 */
    private static String escapePowerShellLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    /** 上一次记录过的采集设备，只在变化时打一条 INFO，避免每次采集都刷屏。 */
    private static volatile String lastLoggedDevice;

    /** 从脚本输出里挑出实际使用的摄像头名并记一条日志。 */
    private static void logChosenDevice(String output) {
        if (output == null) return;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("DEVICE ")) continue;
            String device = trimmed.substring("DEVICE ".length()).trim();
            if (device.isEmpty() || device.equals(lastLoggedDevice)) return;
            lastLoggedDevice = device;
            OllamaProxyMod.LOGGER.info("摄像头采集使用设备: {}", device);
            return;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}