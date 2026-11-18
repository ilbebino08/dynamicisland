package dev.android.dynamicisland.island.source;

import android.app.BroadcastOptions;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.XLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Relays battery reports from the Pixel Buds app's existing Maestro socket. */
public final class PixelBudsBatteryBridge {
    public static final String COMPANION = "com.google.android.apps.wearables.maestro.companion";
    static final String ACTION = "dev.android.dynamicisland.PIXEL_BUDS_BATTERY";
    static final String ADDRESS = "address";
    static final String LEVELS = "levels";
    static final UUID MAESTRO = UUID.fromString("25e97ff7-24ce-4c4c-8951-f764a708f7b5");

    private PixelBudsBatteryBridge() {}

    public static void install() {
        Map<BluetoothSocket, BatteryFrames> sockets = new WeakHashMap<>();
        XposedHelpers.findAndHookMethod(BluetoothSocket.class, "read", byte[].class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.getResult() instanceof Integer count) || count <= 0) return;
                        BluetoothSocket socket = (BluetoothSocket) param.thisObject;
                        synchronized (sockets) {
                            BatteryFrames frames = sockets.get(socket);
                            if (frames == null) {
                                if (sockets.containsKey(socket)) return;
                                BluetoothDevice device = socket.getRemoteDevice();
                                if (!(Refl.get(socket, "mUuid") instanceof ParcelUuid uuid)
                                        || !MAESTRO.equals(uuid.getUuid()) || device == null
                                        || !PixelBudsSource.matches(device)) {
                                    sockets.put(socket, null);
                                    return;
                                }
                                frames = new BatteryFrames();
                                sockets.put(socket, frames);
                                relay(device.getAddress(), null);
                            }
                            String address = socket.getRemoteDevice().getAddress();
                            byte[] bytes = (byte[]) param.args[0];
                            int offset = (Integer) param.args[1];
                            for (int i = offset; i < offset + count; i++) {
                                int[] levels = frames.accept(bytes[i]);
                                if (levels != null) relay(address, levels);
                            }
                        }
                    }
                });
        XLog.d("Pixel Buds 2a: companion battery hook installed");
    }

    private static void relay(String address, int[] levels) {
        try {
            Object app = Refl.callStatic(Class.forName("android.app.ActivityThread"), "currentApplication");
            if (!(app instanceof Context context)) return;
            Intent intent = new Intent(ACTION).setPackage("com.android.systemui").putExtra(ADDRESS, address);
            if (levels != null) intent.putExtra(LEVELS, levels);
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            context.sendBroadcast(intent, "android.permission.BLUETOOTH_CONNECT", options.toBundle());
        } catch (ReflectiveOperationException | RuntimeException error) {
            XLog.once("Pixel Buds battery relay", error);
        }
    }

    /** RFCOMM reads may split or combine HDLC frames. */
    static final class BatteryFrames {
        private static final int FLAG = 0x7e;
        private static final int MAX_WIRE_FRAME_BYTES = 8192;
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private boolean collecting;

        int[] accept(byte value) {
            if ((value & 0xff) != FLAG) {
                if (frame.size() == MAX_WIRE_FRAME_BYTES) collecting = false;
                if (collecting) frame.write(value & 0xff);
                return null;
            }
            int[] levels = null;
            if (collecting && frame.size() > 0) {
                frame.write(FLAG);
                try {
                    PixelBudsMaestro.Packet packet = PixelBudsMaestro.read(new ByteArrayInputStream(frame.toByteArray()));
                    if (packet != null && packet.method() == PixelBudsMaestro.BATTERY
                            && packet.type() == PixelBudsMaestro.STREAM && packet.status() == 0) {
                        levels = packet.batteries();
                    }
                } catch (IOException ignored) {
                    // Treat an incomplete frame as unavailable.
                }
            }
            collecting = true;
            frame.reset();
            return levels;
        }
    }
}
