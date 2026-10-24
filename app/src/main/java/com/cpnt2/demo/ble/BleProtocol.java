package com.cpnt2.demo.ble;

/**
 * BLE 协议常量定义
 *
 * 设计要点：
 *   - 单包最大 20 字节 (BLE 4.0 ATT MTU 23，协商后可更大)
 *   - codec2 各模式压缩帧 ≤ 8 字节 (3200 模式 64bit)，单包即可承载，无需分包
 *
 * 数据包格式 (单包)：
 *   [0]      : packet type (0x01 = 音频帧，0x02 = 控制/状态，0x03 = 文本，0x04 = PING，0x05 = PONG)
 *   [1]      : 音频帧 → codec 模式 ID；控制帧 → subtype
 *   [2-19]   : 有效负载 (音频帧为编码字节)
 *
 * 音频帧 payload (frame) :
 *   [1]      : codec 模式 ID (1=3200, 2=2400, 3=1600, 4=1400, 5=1300, 6=1200, 7=700C, 8=450, 9=450PWB)
 *   [2-19]   : 编码后的字节
 */
public final class BleProtocol {

    private BleProtocol() {}

    // 自定义 Service/Characteristic UUID
    // 注意：不使用蓝牙基础 UUID 格式 (0000xxxx-0000-1000-8000-00805f9b34fb)
    // 因为该格式会被 Android 识别为 16-bit UUID，在部分国产 ROM (Oppo/ColorOS) 上
    // 会导致 service 发现失败。使用真正的随机 128-bit UUID 格式。
    public static final String SERVICE_UUID        = "c3a10001-d4e5-f6a7-b8c9-0123456789ab";
    public static final String CHAR_RX_UUID        = "c3a10002-d4e5-f6a7-b8c9-0123456789ab"; // Central -> Peripheral (音频)
    public static final String CHAR_TX_UUID        = "c3a10003-d4e5-f6a7-b8c9-0123456789ab"; // Peripheral -> Central (反向通道/ACK)
    public static final String CHAR_INFO_UUID      = "c3a10004-d4e5-f6a7-b8c9-0123456789ab"; // 设备信息
    public static final String DESC_UUID           = "00002902-0000-1000-8000-00805f9b34fb"; // CCCD (标准蓝牙 UUID, 保持不变)

    // 广播中使用的制造商 ID (任意取值，只要双端一致)
    public static final int MANUFACTURER_ID = 0xC0DE;

    // 设备名称前缀
    public static final String DEVICE_NAME_PREFIX = "Oulab_";

    // 包类型
    public static final byte PACKET_TYPE_AUDIO    = 0x01;
    public static final byte PACKET_TYPE_CONTROL  = 0x02;
    public static final byte PACKET_TYPE_TEXT     = 0x03;
    public static final byte PACKET_TYPE_PING     = 0x04;
    public static final byte PACKET_TYPE_PONG     = 0x05;

    // 控制子类型
    public static final byte CTRL_HELLO           = 0x01;
    public static final byte CTRL_ROLE_REQUEST    = 0x02;  // 客户端声明角色
    public static final byte CTRL_ROLE_ACCEPT     = 0x03;
    public static final byte CTRL_BITRATE_CHANGE  = 0x04;  // 通知对端切换码率
    public static final byte CTRL_HANGUP          = 0x05;

    // 角色
    public static final byte ROLE_ENCODER = 0x01;
    public static final byte ROLE_DECODER = 0x02;
    public static final byte ROLE_AUTO    = 0x00;

    // MTU 协商默认
    public static final int DEFAULT_MTU = 23;

    /**
     * 将 9 种 codec 模式映射到 1 字节 ID
     */
    public static byte modeToId(String modeName) {
        switch (modeName) {
            case "3200":   return 1;
            case "2400":   return 2;
            case "1600":   return 3;
            case "1400":   return 4;
            case "1300":   return 5;
            case "1200":   return 6;
            case "700C":   return 7;
            case "450":    return 8;
            case "450PWB": return 9;
            default:       return 5; // 默认 1300
        }
    }

    public static String idToMode(byte id) {
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
}
