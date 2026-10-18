package io.github.mangi.eta.shadow.carrier;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * 影子屏 sidecar 载体。
 *
 * 由 Eta 以 root 身份通过 `app_process` 拉起，运行在独立进程中持有虚拟屏。
 *
 * 关键设计：
 * 1. 使用 ActivityThread.systemMain() 取得系统 Context，绕过普通应用在 Android 10+
 *    被移除的 CREATE_VIRTUAL_DISPLAY 权限限制。
 * 2. 虚拟屏必须挂载一个真实 Surface，否则 SurfaceFlinger 不会注册该显示，
 *    screencap -d 将无法截到画面。这里用 ImageReader 提供 Surface。
 * 3. 通过心跳租约维持存活：宿主持续刷新 carrier.hb；若超过 LEASE_GRACE_MS 未见刷新，
 *    载体自行释放虚拟屏并退出，避免留下孤儿显示。
 */
public final class VdCarrier {

    private static final String TAG = "EtaShadowCarrier";

    /** 心跳检测周期。 */
    private static final long TICK_MS = 1000L;
    /** 租约宽限期：超过此时长未收到心跳即自毁。 */
    private static final long LEASE_GRACE_MS = 90_000L;

    /**
     * 虚拟屏标志位组合。
     *
     * PUBLIC | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | SHOULD_SHOW_SYSTEM_DECORATIONS | TRUSTED | OWN_FOCUS
     * 必须避免 FLAG_SECURE（会导致 screencap 黑屏）与 FLAG_OWN_CONTENT_ONLY（拒绝外部内容）。
     */
    private static final int VIRTUAL_DISPLAY_FLAGS = 31745;

    private static File baseDir;
    private static File sessionFile;
    private static File heartbeatFile;
    private static File stopFile;
    private static File snapshotReqFile;
    private static File snapshotOutFile;

    private static VirtualDisplay virtualDisplay;
    private static ImageReader imageReader;

    public static void main(String[] args) {
        int width = 1080;
        int height = 2340;
        int dpi = 440;
        String name = "EtaShadowDisplay";
        String signal = null;

        for (String arg : args) {
            if (arg.startsWith("--width=")) {
                width = parseInt(arg.substring("--width=".length()), width);
            } else if (arg.startsWith("--height=")) {
                height = parseInt(arg.substring("--height=".length()), height);
            } else if (arg.startsWith("--dpi=")) {
                dpi = parseInt(arg.substring("--dpi=".length()), dpi);
            } else if (arg.startsWith("--name=")) {
                name = arg.substring("--name=".length());
            } else if (arg.startsWith("--base=")) {
                baseDir = new File(arg.substring("--base=".length()));
            } else if (arg.startsWith("--signal=")) {
                signal = arg.substring("--signal=".length());
            }
        }

        if (baseDir == null) {
            baseDir = new File("/data/local/tmp/eta_shadow");
        }
        sessionFile = new File(baseDir, "carrier.session");
        heartbeatFile = new File(baseDir, "carrier.hb");
        stopFile = new File(baseDir, "carrier.stop");
        snapshotReqFile = new File(baseDir, "carrier.snap");
        snapshotOutFile = new File(baseDir, "screen.png");

        // 一次性控制信令模式：写完文件立即退出，不创建虚拟屏。
        if (signal != null) {
            emitSignal(signal);
            return;
        }

        log("carrier starting " + width + "x" + height + "@" + dpi + " name=" + name);

        try {
            prepareLooper();
            VirtualDisplay display = createDisplay(width, height, dpi, name);
            if (display == null) {
                log("display creation failed, exiting");
                return;
            }
            virtualDisplay = display;
            int displayId = display.getDisplay().getDisplayId();
            log("display created displayId=" + displayId);
            publishSession(displayId, width, height, dpi, name);
            keepAlive(displayId);
        } catch (Throwable t) {
            log("carrier crashed: " + t);
            t.printStackTrace();
        } finally {
            releaseDisplay();
            log("carrier exited");
        }
    }

    /**
     * 虚拟屏的创建依赖于活跃的 Looper，因为 DisplayManager 的回调与
     * DisplayEventReceiver 需要消息循环才能正常派发。
     */
    private static void prepareLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper();
        }
    }

    private static VirtualDisplay createDisplay(int width, int height, int dpi, String name) throws Exception {
        Context context = systemContext();
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            log("DisplayManager unavailable");
            return null;
        }

        // 提供一个真实 Surface：否则显示会停留在 OFF 状态且不被 SurfaceFlinger 注册。
        imageReader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2);
        Surface surface = imageReader.getSurface();

        Method create = DisplayManager.class.getMethod(
                "createVirtualDisplay",
                String.class, int.class, int.class, int.class,
                Surface.class, int.class);
        Object result = create.invoke(manager, name, width, height, dpi, surface, VIRTUAL_DISPLAY_FLAGS);
        if (result instanceof VirtualDisplay) {
            return (VirtualDisplay) result;
        }
        log("createVirtualDisplay returned " + result);
        return null;
    }

    /**
     * 取系统 Context。
     *
     * 普通应用在 Android 10 之后不再持有 CREATE_VIRTUAL_DISPLAY 权限，
     * 通过 ActivityThread.systemMain() 可以拿到以 "android" 包身份运行的系统 Context，
     * 从而通过 DisplayManager 的权限校验。
     */
    private static Context systemContext() throws Exception {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThreadClass.getMethod("systemMain");
        Object activityThread = systemMain.invoke(null);
        Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
        Object context = getSystemContext.invoke(activityThread);
        if (context instanceof Context) {
            return (Context) context;
        }
        throw new IllegalStateException("system context unavailable");
    }

    /** 记录会话信息，供宿主读取以构造截图与输入命令。 */
    private static void publishSession(int displayId, int width, int height, int dpi, String name) {
        String surfaceFlingerId = resolveSurfaceFlingerId(name);
        String line = displayId + "|" + surfaceFlingerId + "|" + width + "|" + height + "|" + dpi + "|" + name;
        writeFile(sessionFile, line);
        log("session published " + line);
    }

    /**
     * 解析 SurfaceFlinger 物理显示 ID。
     *
     * screencap -d 需要的是 SurfaceFlinger 的内部 ID（通常是很大的整数），
     * 而不是 Android 层面的逻辑 displayId。
     */
    private static String resolveSurfaceFlingerId(String name) {
        String output = exec("dumpsys SurfaceFlinger --display-id");
        if (output == null || output.isEmpty()) {
            return "0";
        }
        String currentId = "";
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Display ")) {
                // 形如：Display 11529215048172534620 (HWC display ...)
                int space = line.indexOf(' ', 8);
                currentId = space > 0 ? line.substring(8, space) : "";
            }
            if (line.contains("displayName=\"" + name + "\"")) {
                return currentId.isEmpty() ? "0" : currentId;
            }
        }
        return "0";
    }

    private static String exec(String command) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            java.io.InputStream input = process.getInputStream();
            StringBuilder builder = new StringBuilder();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) > 0) {
                builder.append(new String(buffer, 0, read));
            }
            process.waitFor();
            return builder.toString();
        } catch (Exception e) {
            log("exec failed: " + command + " -> " + e);
            return "";
        }
    }

    /**
     * 保活循环。
     *
     * - 显示失效 → 退出
     * - 收到停止信号 → 退出
     * - 心跳租约过期 → 自行终止，防止孤儿显示
     */
    private static void keepAlive(int displayId) throws InterruptedException {
        long lastHeartbeatSeen = readHeartbeat();
        long leaseDeadline = System.currentTimeMillis() + LEASE_GRACE_MS;

        while (true) {
            Thread.sleep(TICK_MS);

            Display current = virtualDisplay == null ? null : virtualDisplay.getDisplay();
            if (current == null || !current.isValid()) {
                log("display became invalid, releasing");
                break;
            }
            if (stopFile.exists()) {
                log("stop signal detected, releasing");
                break;
            }

            if (snapshotReqFile != null && snapshotReqFile.exists()) {
                try {
                    captureSnapshot();
                } catch (Throwable t) {
                    log("snapshot failed: " + t);
                }
            }

            long heartbeat = readHeartbeat();
            if (heartbeat > lastHeartbeatSeen) {
                lastHeartbeatSeen = heartbeat;
                leaseDeadline = System.currentTimeMillis() + LEASE_GRACE_MS;
            }
            if (System.currentTimeMillis() > leaseDeadline) {
                log("heartbeat lease expired (displayId=" + displayId + "), self-terminating");
                break;
            }
        }
    }

    private static long readHeartbeat() {
        if (heartbeatFile == null || !heartbeatFile.isFile()) {
            return 0L;
        }
        try {
            String content = readFile(heartbeatFile).trim();
            return content.isEmpty() ? 0L : parseLong(content, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 直接读取虚拟屏 ImageReader 的最新一帧，编码为 PNG 写入快照文件，随后删除请求标记。
     *
     * 这是相对 screencap -d 的低延迟取帧通道：不走子进程/SurfaceFlinger dump，
     * 直接读 GPU 已合成的像素；同时避免对 surfaceFlingerId 解析的依赖。
     */
    private static void captureSnapshot() {
        if (imageReader == null) {
            log("snapshot skipped: ImageReader unavailable");
            snapshotReqFile.delete();
            return;
        }
        android.media.Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                snapshotReqFile.delete();
                return;
            }
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 1) {
                image.close();
                snapshotReqFile.delete();
                return;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            android.media.Image.Plane plane = planes[0];
            java.nio.ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (rowStride == 0 || pixelStride == 0) {
                image.close();
                snapshotReqFile.delete();
                return;
            }
            int rowPadding = rowStride - pixelStride * width;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(buffer);
            if (rowPadding > 0) {
                android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, width, height);
                bmp.recycle();
                bmp = cropped;
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(snapshotOutFile);
            try {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
                bmp.recycle();
            }
            log("snapshot written " + width + "x" + height + " -> " + snapshotOutFile);
        } catch (Exception e) {
            log("snapshot capture error: " + e);
        } finally {
            if (image != null) {
                image.close();
            }
            snapshotReqFile.delete();
        }
    }

    /** 一次性信令：供宿主脚本快速写入停止或心跳标记。 */
    private static void emitSignal(String kind) {
        if ("stop".equals(kind)) {
            writeFile(stopFile, String.valueOf(System.currentTimeMillis()));
            log("emitted stop signal");
        } else if ("beat".equals(kind) || "heartbeat".equals(kind)) {
            writeFile(heartbeatFile, String.valueOf(System.currentTimeMillis()));
        }
    }

    private static void releaseDisplay() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Throwable t) {
            log("release display failed: " + t);
        }
        try {
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (Throwable t) {
            log("close image reader failed: " + t);
        }
        try {
            if (sessionFile != null) {
                sessionFile.delete();
            }
        } catch (Throwable ignored) {
        }
        // 仅在 pid 文件仍属于本进程时删除，避免误删后续实例的状态。
        try {
            if (baseDir != null) {
                File pidFile = new File(baseDir, "carrier.pid");
                if (readFile(pidFile).trim().equals(String.valueOf(android.os.Process.myPid()))) {
                    pidFile.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeFile(File file, String content) {
        FileOutputStream out = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            out = new FileOutputStream(file, false);
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            log("write " + file + " failed: " + e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readFile(File file) {
        try {
            java.io.FileInputStream input = new java.io.FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            int read = input.read(buffer);
            input.close();
            return read > 0 ? new String(buffer, 0, read, "UTF-8") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 输出到 carrier.log，便于宿主机排查。 */
    private static void log(String message) {
        System.out.println("[" + SystemClock.uptimeMillis() + "] " + TAG + ": " + message);
    }

    private VdCarrier() {
    }
}
