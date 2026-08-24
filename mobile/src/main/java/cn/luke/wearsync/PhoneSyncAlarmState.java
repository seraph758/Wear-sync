package cn.luke.wearsync;

public final class PhoneSyncAlarmState {

    public enum State {
        IDLE,
        RINGING,
        STOPPING
    }

    private static volatile State state = State.IDLE;

    private PhoneSyncAlarmState() {
    }

    public static synchronized void enterRinging() {
        state = State.RINGING;
    }

    public static synchronized void enterStopping() {
        state = State.STOPPING;
    }

    public static synchronized void reset() {
        state = State.IDLE;
    }

    public static boolean isRinging() {
        return state == State.RINGING;
    }
}
