package com.march7th.hkrpg.core;

import android.os.RemoteException;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.march7th.hkrpg.ITouchEventCallback;
import com.march7th.hkrpg.third.Ln;
import com.march7th.hkrpg.third.wrappers.InputManager;
import com.march7th.hkrpg.third.wrappers.ServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * 多指触控注入：{@link TouchPointerSequence} 规划 MotionEvent，这里注入并维护槽位
 */
public final class InputControlUtils {

    public static final int SINGLE_CONTACT = 0;
    private static final String TAG = "InputControlUtils";
    private static final int DEFAULT_DEVICE_ID = 0;
    private static final int DEFAULT_SOURCE = InputDevice.SOURCE_TOUCHSCREEN;
    private static final MotionEvent.PointerProperties[] POINTER_PROPERTIES =
            new MotionEvent.PointerProperties[TouchPointerSequence.MAX_CONTACTS];
    private static final MotionEvent.PointerCoords[] POINTER_COORDS =
            new MotionEvent.PointerCoords[TouchPointerSequence.MAX_CONTACTS];
    private static InputManager manager;
    private static volatile ITouchEventCallback touchCallback;
    /**
     * 在场手指；只整体换引用，注入失败时保持与系统侧一致
     */
    private static List<TouchPointerSequence.Pointer> slots = Collections.emptyList();
    private static long gestureDownTime = 0;

    static {
        for (int i = 0; i < TouchPointerSequence.MAX_CONTACTS; i++) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;
            POINTER_PROPERTIES[i] = props;
            POINTER_COORDS[i] = new MotionEvent.PointerCoords();
        }
    }
    private InputControlUtils() {
    }

    private static InputManager getManager() {
        if (manager == null) {
            manager = ServiceManager.getInputManager();
        }
        return manager;
    }

    /**
     * liftingIndex 为正在抬起的手指（压力置 0），无则传 -1；CANCEL 全部置 0
     */
    private static MotionEvent obtainEvent(List<TouchPointerSequence.Pointer> pointers, long eventTime,
                                           int action, int liftingIndex) {
        boolean cancel = (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_CANCEL;
        int n = pointers.size();
        for (int i = 0; i < n; i++) {
            TouchPointerSequence.Pointer p = pointers.get(i);
            POINTER_PROPERTIES[i].id = p.getContact();
            MotionEvent.PointerCoords coord = POINTER_COORDS[i];
            coord.x = Math.max(0, p.getX());
            coord.y = Math.max(0, p.getY());
            coord.pressure = (cancel || i == liftingIndex) ? 0.0f : 1.0f;
            coord.size = 1.0f;
        }
        return MotionEvent.obtain(
                gestureDownTime, eventTime, action,
                n, POINTER_PROPERTIES, POINTER_COORDS,
                0, 0,
                1.0f, 1.0f,
                DEFAULT_DEVICE_ID, 0, DEFAULT_SOURCE, 0
        );
    }

    /**
     * reportIndex 为本次事件发生变化的手指，触控预览只上报这一根
     */
    private static boolean inject(MotionEvent event, int displayId, int mode, int reportIndex) {
        try {
            if (!setDisplayId(event, displayId)) {
                return false;
            }
            notifyTouchCallback(event, reportIndex);
            return getManager().injectInputEvent(event, mode);
        } finally {
            event.recycle();
        }
    }

    public static void setTouchCallback(ITouchEventCallback callback) {
        touchCallback = callback;
    }

    private static void notifyTouchCallback(MotionEvent event, int index) {
        ITouchEventCallback callback = touchCallback;
        if (callback == null) {
            return;
        }
        try {
            callback.onCallback(Math.round(event.getX(index)), Math.round(event.getY(index)),
                    event.getActionMasked(), event.getPointerId(index));
        } catch (RemoteException | RuntimeException e) {
            touchCallback = null;
            Ln.w(TAG + ": touch callback failed, clearing registration", e);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean setDisplayId(InputEvent event, int displayId) {
        return displayId == 0 || InputManager.setDisplayId(event, displayId);
    }

    private static int encodeAction(int masked, int index) {
        if (masked == TouchPointerSequence.ACTION_POINTER_DOWN
                || masked == TouchPointerSequence.ACTION_POINTER_UP) {
            return masked | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        return masked;
    }

    private static List<TouchPointerSequence.Pointer> without(List<TouchPointerSequence.Pointer> pointers,
                                                              int index) {
        List<TouchPointerSequence.Pointer> next = new ArrayList<>(pointers);
        next.remove(index);
        return next;
    }

    /**
     * 清空系统侧触控槽位
     */
    private static void cancelGesture(int displayId) {
        if (!slots.isEmpty()) {
            MotionEvent cancel = obtainEvent(slots, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, -1);
            inject(cancel, displayId, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC, 0);
        }
        slots = Collections.emptyList();
    }

    private static boolean injectStep(TouchPointerSequence.Step step, int displayId) {
        return injectStep(step, displayId, -1);
    }

    /**
     * @param forcedMode 强制指定注入模式；-1 = 按动作类型自动选
     *                   （DOWN 用 WAIT_FOR_FINISH，其余 ASYNC）
     */
    private static boolean injectStep(TouchPointerSequence.Step step, int displayId, int forcedMode) {
        if (!step.getOk()) {
            return false;
        }
        if (step.getCancelFirst()) {
            cancelGesture(displayId);
        }
        long now = SystemClock.uptimeMillis();
        if (slots.isEmpty()) {
            gestureDownTime = now;
        }
        int masked = step.getActionMasked();
        int index = step.getChangingIndex();
        List<TouchPointerSequence.Pointer> pointers = step.getPointers();
        boolean isDown = masked == TouchPointerSequence.ACTION_DOWN
                || masked == TouchPointerSequence.ACTION_POINTER_DOWN;
        boolean isUp = masked == TouchPointerSequence.ACTION_UP
                || masked == TouchPointerSequence.ACTION_POINTER_UP;
        List<TouchPointerSequence.Pointer> next = masked == TouchPointerSequence.ACTION_UP
                ? Collections.<TouchPointerSequence.Pointer>emptyList()
                : masked == TouchPointerSequence.ACTION_POINTER_UP ? without(pointers, index) : pointers;

        // DOWN 必须 WAIT_FOR_FINISH，确保起始状态被系统接收
        int mode = forcedMode >= 0 ? forcedMode
                : (isDown ? InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
                        : InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        boolean ok = inject(
                obtainEvent(pointers, now, encodeAction(masked, index), isUp ? index : -1),
                displayId,
                mode,
                index);
        // 未送达则槽位不动；之后同 contact 再按下会先整体 CANCEL 自愈
        if (ok) {
            slots = next;
        } else {
            Ln.w(TAG + ": 注入失败 action=" + Integer.toHexString(masked)
                    + " x=" + (pointers.isEmpty() ? -1 : pointers.get(0).getX())
                    + " y=" + (pointers.isEmpty() ? -1 : pointers.get(0).getY())
                    + " displayId=" + displayId
                    + " mode=" + mode
                    + " pending=" + slots.size());
        }
        return ok;
    }

    private static synchronized boolean apply(TouchPointerSequence.Kind kind, int x, int y, int contact,
                                              int displayId) {
        return apply(kind, x, y, contact, displayId, -1);
    }

    private static synchronized boolean apply(TouchPointerSequence.Kind kind, int x, int y, int contact,
                                              int displayId, int forcedMode) {
        return injectStep(TouchPointerSequence.INSTANCE.plan(kind, slots, contact, x, y), displayId, forcedMode);
    }

    public static boolean down(int x, int y, int contact, int displayId) {
        return apply(TouchPointerSequence.Kind.Down, x, y, contact, displayId);
    }

    /**
     * DOWN 的降级版本：只等事件**入队**，不等目标界面消费完。
     *
     * ## 为什么需要它
     *
     * [InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH] 会一直等到
     * 目标窗口把事件**处理完**才返回。如果游戏主线程这一刻正忙
     * （转场、加载、GC），等待就会超时并返回 false ——
     * 但事件其实已经送达了。
     *
     * 这种"其实是送达到了、只是没等上"的失败，和"权限被拒 / 指针状态错乱"
     * 完全是两回事。前者换个模式就能成，后者换什么都没用。
     * 所以正常 DOWN 失败后用它再试一次，既能自愈，也能在日志里区分两类原因。
     */
    public static boolean downQueued(int x, int y, int contact, int displayId) {
        return apply(TouchPointerSequence.Kind.Down, x, y, contact, displayId,
                InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT);
    }

    public static boolean move(int x, int y, int contact, int displayId) {
        return apply(TouchPointerSequence.Kind.Move, x, y, contact, displayId);
    }

    public static boolean up(int x, int y, int contact, int displayId) {
        return apply(TouchPointerSequence.Kind.Up, x, y, contact, displayId);
    }

    /**
     * 整体取消当前手势，仍按着的手指全部释放
     */
    public static synchronized void cancel(int displayId) {
        cancelGesture(displayId);
    }

    /**
     * 当前**系统侧仍认为按着**的手指数量。
     *
     * 只读诊断用。这个值本该在每次 tap 结束后回到 0；
     * 如果它长期大于 0，就说明有 UP/CANCEL 没送达 ——
     * 此时 InputDispatcher 会判定指针状态不一致，**后续所有 DOWN 都被丢弃**，
     * 外在表现就是"画面正常、坐标正确、就是点不动"。
     */
    public static int pendingContacts() {
        return slots.size();
    }

    public static boolean keyDown(int keyCode, int displayId) {
        long downTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0);

        if (!setDisplayId(keyEvent, displayId)) {
            return false;
        }
        return getManager().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    public static boolean keyUp(int keyCode, int displayId) {
        long upTime = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(upTime, upTime, KeyEvent.ACTION_UP, keyCode, 0);

        if (!setDisplayId(keyEvent, displayId)) {
            return false;
        }

        return getManager().injectInputEvent(keyEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
