package cn.luke.wearsync;

public final class PhoneSyncAlarmState {

    public enum State {
        IDLE,
        RINGING,
        STOPPING
    }

    private static volatile State state = State.IDLE;
    private static volatile long lastStartTimestamp = 0;

    private PhoneSyncAlarmState() {
    }

    public static synchronized void enterRinging() {
        state = State.RINGING;
        lastStartTimestamp = System.currentTimeMillis();
    }

    public static synchronized void enterStopping() {
        state = State.STOPPING;
    }

    public static synchronized void reset() {
        state = State.IDLE;
    }

    public static State getState() {
        return state;
    }

    public static boolean isRinging() {
        return state == State.RINGING;
    }

    public static boolean isIdle() {
        return state == State.IDLE;
    }

    public static long getLastStartTimestamp() {
        return lastStartTimestamp;
    }
}
