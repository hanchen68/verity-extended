package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.stb.STBIWriteCallback;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 截当前屏幕（含 HUD / 打开着的界面）缩放编码成 JPEG，让视觉模型看看玩家在玩什么。
 *
 * 读帧必须在渲染线程，所以用 {@link Minecraft#execute(Runnable)} 调度、再拿
 * {@link CountDownLatch} 把像素传回来；缩放和编码在调用线程做。
 * 调用方本来就在主线程时是就地执行，不会自己等自己。
 */
public final class GameScreenCaptureService {

    /** 画面宽度上限，避免超宽屏产生过大的请求体。 */
    private static final int MAX_TARGET_WIDTH = 1280;
    private static final int MIN_TARGET_HEIGHT = 90;
    private static final int MAX_TARGET_HEIGHT = 1200;

    private GameScreenCaptureService() {
    }

    /**
     * 截取当前游戏画面。
     *
     * @return 采集结果；成功后 {@code base64Jpeg} 即为屏幕画面，后端标识为 {@code screen}
     */
    public static CameraCaptureService.CaptureResult captureScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return CameraCaptureService.CaptureResult.failure("screen", "游戏客户端尚未初始化");
        }
        int timeoutMs = Math.max(1000, ConfigManager.getCameraCaptureTimeoutMs());

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<int[]> pixels = new AtomicReference<>();
        final AtomicInteger width = new AtomicInteger();
        final AtomicInteger height = new AtomicInteger();
        final AtomicReference<String> failure = new AtomicReference<>();

        try {
            minecraft.execute(() -> {
                try {
                    RenderTarget target = minecraft.getMainRenderTarget();
                    if (target == null || target.width <= 0 || target.height <= 0) {
                        failure.set("主渲染目标不可用（游戏可能正在加载或已关闭）");
                        return;
                    }
                    NativeImage image = Screenshot.takeScreenshot(target);
                    try {
                        width.set(image.getWidth());
                        height.set(image.getHeight());
                        pixels.set(image.getPixelsRGBA());
                    } finally {
                        image.close();
                    }
                } catch (Throwable t) {
                    failure.set(describe(t));
                } finally {
                    latch.countDown();
                }
            });
        } catch (Throwable t) {
            return CameraCaptureService.CaptureResult.failure("screen", "无法调度截屏任务: " + describe(t));
        }

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return CameraCaptureService.CaptureResult.failure("screen",
                        "截屏超时（>" + timeoutMs + "ms），游戏主线程繁忙");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CameraCaptureService.CaptureResult.failure("screen", "截屏被中断");
        }

        if (failure.get() != null) {
            return CameraCaptureService.CaptureResult.failure("screen", failure.get());
        }
        int[] source = pixels.get();
        int sourceWidth = width.get();
        int sourceHeight = height.get();
        if (source == null || sourceWidth <= 0 || sourceHeight <= 0
                || source.length < sourceWidth * sourceHeight) {
            return CameraCaptureService.CaptureResult.failure("screen", "没有取到画面像素");
        }

        int targetWidth = Math.max(160, Math.min(MAX_TARGET_WIDTH, ConfigManager.getCameraWidth()));
        targetWidth = Math.min(targetWidth, sourceWidth);
        // 按屏幕真实宽高比缩放，避免把 16:9 的游戏画面压成摄像头那样的 4:3 变形画面
        int targetHeight = (int) Math.round((double) targetWidth * sourceHeight / sourceWidth);
        targetHeight = Math.max(MIN_TARGET_HEIGHT, Math.min(MAX_TARGET_HEIGHT, targetHeight));
        if ((targetWidth & 1) == 1) targetWidth--;
        if ((targetHeight & 1) == 1) targetHeight--;
        if (targetWidth <= 0 || targetHeight <= 0) {
            return CameraCaptureService.CaptureResult.failure("screen", "目标尺寸无效: "
                    + targetWidth + "x" + targetHeight);
        }

        try {
            byte[] rgb = downscaleToRgb(source, sourceWidth, sourceHeight, targetWidth, targetHeight);
            byte[] jpeg = encodeJpeg(rgb, targetWidth, targetHeight, ConfigManager.getCameraJpegQuality());
            if (jpeg == null || jpeg.length < 128) {
                return CameraCaptureService.CaptureResult.failure("screen", "JPEG 编码失败或结果异常");
            }
            OllamaProxyMod.LOGGER.info("游戏画面截屏成功: {}x{} -> {}x{}, ~{} 字节",
                    sourceWidth, sourceHeight, targetWidth, targetHeight, jpeg.length);
            return CameraCaptureService.CaptureResult.screenJpeg(
                    Base64.getEncoder().encodeToString(jpeg));
        } catch (Throwable t) {
            return CameraCaptureService.CaptureResult.failure("screen", describe(t));
        }
    }

    /** 供配置界面"测试游戏画面"按钮使用。 */
    public static boolean testCapture(StringBuilder report) {
        CameraCaptureService.CaptureResult result = captureScreen();
        if (result.ok()) {
            report.append("成功：已截取游戏画面，JPEG 约 ")
                    .append(result.base64Jpeg().length() * 3L / 4L).append(" 字节");
            return true;
        }
        report.append("失败：").append(result.error());
        return false;
    }

    /** 把整屏像素按面积平均缩放到目标尺寸，输出紧凑 RGB；NativeImage 小端序，按 0/8/16 位移取 R/G/B。 */
    private static byte[] downscaleToRgb(int[] source, int sourceWidth, int sourceHeight,
                                        int targetWidth, int targetHeight) {
        byte[] rgb = new byte[targetWidth * targetHeight * 3];
        for (int dy = 0; dy < targetHeight; dy++) {
            int y0 = (int) ((long) dy * sourceHeight / targetHeight);
            int y1 = (int) ((long) (dy + 1) * sourceHeight / targetHeight);
            if (y1 <= y0) y1 = Math.min(sourceHeight, y0 + 1);
            for (int dx = 0; dx < targetWidth; dx++) {
                int x0 = (int) ((long) dx * sourceWidth / targetWidth);
                int x1 = (int) ((long) (dx + 1) * sourceWidth / targetWidth);
                if (x1 <= x0) x1 = Math.min(sourceWidth, x0 + 1);

                long red = 0L;
                long green = 0L;
                long blue = 0L;
                int count = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = sy * sourceWidth;
                    for (int sx = x0; sx < x1; sx++) {
                        int value = source[row + sx];
                        red += value & 0xFF;
                        green += (value >>> 8) & 0xFF;
                        blue += (value >>> 16) & 0xFF;
                        count++;
                    }
                }
                if (count == 0) count = 1;
                int offset = (dy * targetWidth + dx) * 3;
                rgb[offset] = (byte) (red / count);
                rgb[offset + 1] = (byte) (green / count);
                rgb[offset + 2] = (byte) (blue / count);
            }
        }
        return rgb;
    }

    /** 用 LWJGL 的 STB 编码 JPEG；走内存回调而不是临时文件，避开临时目录非 ASCII 字符的问题。 */
    private static byte[] encodeJpeg(byte[] rgb, int width, int height, int quality) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(rgb.length);
        buffer.put(rgb).flip();
        ByteArrayOutputStream sink = new ByteArrayOutputStream(Math.max(8192, rgb.length / 8));
        STBIWriteCallback callback = STBIWriteCallback.create((context, data, size) -> {
            ByteBuffer chunk = STBIWriteCallback.getData(data, size);
            byte[] block = new byte[size];
            chunk.get(block);
            sink.write(block, 0, size);
        });
        try {
            int ok = STBImageWrite.stbi_write_jpg_to_func(
                    callback, 0L, width, height, 3, buffer, Math.max(1, Math.min(100, quality)));
            if (ok == 0) {
                return null;
            }
        } finally {
            callback.free();
        }
        return sink.toByteArray();
    }

    private static String describe(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
