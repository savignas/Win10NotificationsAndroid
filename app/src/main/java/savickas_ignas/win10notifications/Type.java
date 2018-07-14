package savickas_ignas.win10notifications;

import java.util.HashMap;
import java.util.Map;

public enum Type {
    Remove(0), Add(1), Open(2);

    private int value;
    private static Map map = new HashMap<>();

    Type(int value) {
        this.value = value;
    }

    static {
        for (Type type: Type.values()) {
            map.put(type.value, type);
        }
    }

    public static Type valueOf(int type) {
        return (Type)map.get(type);
    }

    public int getValue() {
        return value;
    }
}
