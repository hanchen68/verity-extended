package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.util.Silent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 枚举本机可用摄像头，供配置界面做「使用哪一个摄像头」的下拉选择。
 *
 * <p>为什么要单独枚举、而不是让玩家手打设备名：WinRT 的默认设备是「系统认为的默认」，
 * 机器上装了手机投屏 / OBS 虚拟摄像头 / 组合摄像头之后，默认很容易落到一个
 * **永远输出纯黑画面**的设备上。玩家看到的现象是「Verity 说画面全黑」，
 * 但从代码到日志全都显示「采集成功」——因为确实拍到了一张 640x480 的全黑 JPEG。
 * 唯一能让玩家自己解决的入口，就是把设备列表摆出来让他选。
 *
 * <p>枚举走 WinRT 的 {@code DeviceInformation.FindAllAsync(VideoCapture)}，
 * 和采集用的是同一套 API，所以列表里的名字一定能被采集脚本按名字选中。
 *
 * <p>🔴 枚举要起一个 PowerShell 进程（约 1 秒），**绝不能在渲染线程同步调用**，
 * 否则打开配置界面会卡一下。这里只提供异步刷新，结果缓存在静态字段里。
 */
@OnlyIn(Dist.CLIENT)
public final class CameraDeviceService {

    private CameraDeviceService() {
    }

    /** 上一次枚举到的设备名；空列表表示还没枚举过或枚举失败。 */
    private static volatile List<String> cached = List.of();
    private static volatile boolean loading;
    /** 是否已经尝试过枚举（不管成功与否）。避免界面每次重建都重新起进程。 */
    private static volatile boolean attempted;

    /** 设备列表（只读快照）。 */
    public static List<String> devices() {
        return cached;
    }

    /** 是否正在枚举，界面可以用它显示「检测中…」。 */
    public static boolean isLoading() {
        return loading;
    }

    /**
     * 只在「从没枚举过」时触发一次，用于打开界面时自动填好列表。
     *
     * <p>界面每次重建都会调它，所以必须有 {@code attempted} 兜底：
     * 枚举失败（没有摄像头 / 非 Windows）时不至于每次重建都重起一个 PowerShell 进程。
     */
    public static void requestOnce(Runnable onDone) {
        if (attempted || loading) return;
        attempted = true;
        refreshAsync(onDone);
    }

    /**
     * 后台枚举一次设备，完成后在客户端线程回调。
     *
     * @param onDone 回调；参数为最新设备列表。可为 {@code null}
     */
    public static void refreshAsync(Runnable onDone) {
        if (loading) return;
        loading = true;
        attempted = true;
        Thread worker = new Thread(() -> {
            List<String> found = List.of();
            try {
                found = enumerate();
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.warn("枚举摄像头失败: {}", error.toString());
            } finally {
                loading = false;
            }
            cached = found;
            OllamaProxyMod.LOGGER.info("检测到 {} 个摄像头: {}", found.size(), found);
            if (onDone != null) {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    minecraft.execute(onDone);
                }
            }
        }, "Verity-Camera-Enum");
        worker.setDaemon(true);
        worker.start();
    }

    /** 真正去枚举；非 Windows 直接返回空列表。 */
    private static List<String> enumerate() throws Exception {
        if (!isWindows()) return List.of();
        String encoded = Base64.getEncoder()
                .encodeToString(LIST_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encoded);
        builder.redirectErrorStream(true);
        String output = run(builder, 8000);
        List<String> names = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            // 只收脚本标记过的行，避免把 PowerShell 的杂项输出当设备名
            if (trimmed.startsWith(DEVICE_PREFIX)) {
                String name = trimmed.substring(DEVICE_PREFIX.length()).trim();
                if (!name.isEmpty() && !names.contains(name)) names.add(name);
            }
        }
        return Collections.unmodifiableList(names);
    }

    private static String run(ProcessBuilder builder, int timeoutMs) throws Exception {
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread pump = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = is.read(chunk)) >= 0) {
                    if (buffer.size() < 32 * 1024) buffer.write(chunk, 0, read);
                }
            } catch (IOException ignored) {
                Silent.ignore("CameraDeviceService", ignored);
            }
        }, "Verity-Camera-Enum-Pump");
        pump.setDaemon(true);
        pump.start();

        boolean finished = process.waitFor(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("枚举摄像头超时（>" + timeoutMs + "ms）");
        }
        pump.join(1000);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** 脚本输出设备名的行前缀，Java 侧按它过滤。 */
    private static final String DEVICE_PREFIX = "VERITY_CAM:";

    /**
     * 列出 WinRT 视频采集设备。
     *
     * <p>只输出名字、不做任何采集，所以很快（约 1 秒）。
     * 名字前面加 {@code VERITY_CAM:} 前缀，方便 Java 侧从 PowerShell 的杂项输出里挑出来。
     */
    private static final String LIST_SCRIPT = String.join("\n",
            "$ErrorActionPreference = 'Stop'",
            "Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null",
            "$ext = [System.WindowsRuntimeSystemExtensions]",
            "$asTaskOp = ($ext.GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
            "function AwaitOp($op, $type) { $m = $asTaskOp.MakeGenericMethod($type); $t = $m.Invoke($null, @($op)); $t.Wait(-1) | Out-Null; return $t.Result }",
            "[Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime] | Out-Null",
            "[Windows.Devices.Enumeration.DeviceClass, Windows.Devices.Enumeration, ContentType = WindowsRuntime] | Out-Null",
            "$devs = AwaitOp ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync([Windows.Devices.Enumeration.DeviceClass]::VideoCapture)) ([Windows.Devices.Enumeration.DeviceInformationCollection])",
            "foreach ($d in $devs) { Write-Output ('" + DEVICE_PREFIX + "' + $d.Name) }");
}
