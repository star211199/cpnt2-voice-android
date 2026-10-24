package com.cpnt2.demo.audio;

/**
 * 与 BleProtocol 模式 ID 转换 (Java 版)
 * 跟 JNI/C 层保持一致
 */
public final class BleCodecMode {

    private BleCodecMode() {}

    public static byte toId(String name) {
        switch (name) {
            case "3200":   return 1;
            case "2400":   return 2;
            case "1600":   return 3;
            case "1400":   return 4;
            case "1300":   return 5;
            case "1200":   return 6;
            case "700C":   return 7;
            case "450":    return 8;
            case "450PWB": return 9;
            default:       return 5;
        }
    }

    public static String toName(byte id) {
        switch (id) {
            case 1:  return "3200";
            case 2:  return "2400";
            case 3:  return "1600";
            case 4:  return "1400";
            case 5:  return "1300";
            case 6:  return "1200";
            case 7:  return "700C";
            case 8:  return "450";
            case 9:  return "450PWB";
            default: return "1300";
        }
    }

    public static int sampleRate(String name) {
        return "450PWB".equals(name) ? 16000 : 8000;
    }

    public static int idToSampleRate(byte id) {
        return id == 9 ? 16000 : 8000;
    }
}
