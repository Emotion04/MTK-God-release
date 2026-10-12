// AIDL surface of the elevated UserService that Shizuku spawns.
// The implementation runs inside Shizuku's server process (uid 0 with root,
// uid 2000 with ADB), which is what lets us touch /sys and /proc.
//
// Shizuku also sends a teardown transaction with the code
// ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy (16777115). That value is
// above the maximum id AIDL allows, so it is intercepted in onTransact() rather
// than declared here.
package magicau.mtkcontroller;

interface IRuntimeService {
    /** Run a command through /system/bin/sh -c and return stdout (stderr merged). */
    String exec(String command);

    /** uid the service is running as: 0 = root, 2000 = shell. */
    int getUid();

    /**
     * Run a PowerHAL acquire from this UserService process. Keeping both this
     * and release here makes the process that owns a PowerHAL request stable
     * across activity and app-process recreation.
     */
    int powerHalAcquire(in int[] commands, int durationMs);

    /** Queue release of a handle previously acquired by this UserService. */
    boolean powerHalRelease(int handler);
    /** Appended to preserve existing transaction IDs. */
    String cameraProbe();
    String romControlProbe();
    String openRomConfig(int index);
    byte[] readRomConfig(String token, int offset);
    void closeRomConfig(String token);
    String discoverRomImplementation();
    String vivoCpuLimitProbe(boolean start);
    String vivoPolicyProbe(boolean start, int profile);
    /** 0 探测，正数请求 Hz，-1 释放。 */
    String vivoDisplayRequest(int fps);
    String vivoTouchRequest(int level);
    String axMaintenance(String kind, String pkg);

    /**
     * 当前前台应用包名；拿不到返回空串并附原因。
     *
     * 走 dumpsys 解析而不是反射 IActivityTaskManager —— 隐藏 API 的签名随 ROM 变，
     * 而 dumpsys 的输出在 uid 2000 下可读，读不到就明确说读不到。
     */
    String foregroundApp();

    /** 假电量 / 假充电；command ∈ level:<n> | charge | discharge | reset。 */
    String fakeBattery(String command);
}
