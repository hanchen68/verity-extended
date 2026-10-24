package com.hanchen.veritycustomapi.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 摄像头采集的守卫自检。
 *
 * <p>起因是 2026-10-25 一次真实排查：日志显示「采集成功 17919 字节」，
 * AI 却回答「画面里全是黑的」。逐台设备实拍测亮度后才看清真相：
 * <ol>
 *   <li><b>系统里有 6 个「摄像头」</b>——XiaoMi Webcam（真实）+ Redmi K60/K80 前后置 +
 *       组合摄像头。后 5 个在手机没连着时**永远输出纯黑**，而 WinRT 不给
 *       {@code VideoDeviceId} 时用的正是这个黑设备；</li>
 *   <li><b>即使选对设备，初始化后立刻拍也是欠曝的</b>：同一台小米相机
 *       立刻拍 24989 字节（平均亮度 6.2），等 600ms 拍 76812 字节（平均亮度 62.5）。</li>
 * </ol>
 * 这两条都是「日志全绿、画面全黑」的静默失败，编译期和单元测试都发现不了，
 * 所以用源码级断言钉住。
 */
public final class CameraDeviceTest {

    private CameraDeviceTest() {
    }

    private static final Path ROOT = Path.of("src", "main", "java", "com", "hanchen",
            "veritycustomapi");

    public static void main(String[] args) throws Exception {
        captureScriptSelectsDevice();
        captureScriptWaitsForExposure();
        captureScriptProbesWhenAuto();
        deviceServiceEnumerates();
        configUiExposesPicker();

        System.out.println("CameraDevice checks passed");
    }

    /** 采集脚本必须显式设置 VideoDeviceId，否则会落到「系统默认」那个黑设备上。 */
    private static void captureScriptSelectsDevice() throws Exception {
        String src = read("client/CameraCaptureService.java");
        require(src.contains("VideoDeviceId"),
                "采集脚本没有设置 VideoDeviceId：会落到系统默认设备，"
                        + "装了虚拟摄像头时那台永远输出纯黑");
        require(src.contains("__DEVICE__"),
                "采集脚本没有接收配置里的设备名（__DEVICE__ 占位符缺失）");
    }

    /** 初始化后必须等一会儿再拍，否则拿到欠曝首帧。 */
    private static void captureScriptWaitsForExposure() throws Exception {
        String src = read("client/CameraCaptureService.java");
        int init = src.indexOf("InitializeAsync($settings)");
        require(init > 0, "找不到 InitializeAsync");
        int shot = src.indexOf("CapturePhotoToStreamAsync", init);
        require(shot > init, "找不到 CapturePhotoToStreamAsync");
        String between = src.substring(init, shot);
        require(between.contains("Start-Sleep"),
                "初始化后立刻拍照：WinRT 首帧常常严重欠曝（实测亮度只有正常值的 1/10）");
    }

    /** 自动模式下必须实拍探测，不能只看设备名。 */
    private static void captureScriptProbesWhenAuto() throws Exception {
        String src = read("client/CameraCaptureService.java");
        require(src.contains("$nonzero"),
                "自动模式没有实拍探测：手机投屏出来的「前置/后置/组合」摄像头"
                        + "名字看不出是黑的，只能实拍验证");
    }

    /** 设备枚举必须用 VideoCapture 类别。 */
    private static void deviceServiceEnumerates() throws Exception {
        String src = read("client/CameraDeviceService.java");
        require(src.contains("DeviceClass]::VideoCapture"),
                "设备枚举没有用 VideoCapture 类别");
        require(src.contains("refreshAsync"),
                "CameraDeviceService 没有异步刷新入口（同步枚举会卡住渲染线程）");
    }

    /** 配置界面必须能选摄像头。 */
    private static void configUiExposesPicker() throws Exception {
        String src = read("config/NativeOllamaProxyConfigScreen.java");
        require(src.contains("cameraDevicePicker"),
                "配置界面没有摄像头设备选择器");
        require(src.contains("使用的摄像头"),
                "配置界面没有「使用的摄像头」这一项");
        require(src.contains("CameraDeviceService"),
                "配置界面没有接上 CameraDeviceService 的设备列表");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
