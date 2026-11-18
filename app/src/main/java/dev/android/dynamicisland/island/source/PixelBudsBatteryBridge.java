package dev.android.dynamicisland.island.source;

import android.app.BroadcastOptions;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;
import android.os.SystemClock;
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
import java.util.concurrent.Executors;

/** Relays battery and ANC state over the Pixel Buds app's existing Maestro socket. */
public final class PixelBudsBatteryBridge {
    public static final String COMPANION = "com.google.android.apps.wearables.maestro.companion";
    static final String SYSTEM_UI = "com.android.systemui";
    static final String ACTION = "dev.android.dynamicisland.PIXEL_BUDS_BATTERY";
    static final String SET_MODE = "dev.android.dynamicisland.PIXEL_BUDS_SET_MODE";
    static final String ADDRESS = "address";
    static final String LEVELS = "levels";
    static final String MODE = "mode";
    static final String REJECTED = "rejected";
    static final int UNCHANGED = -2;
    static final UUID MAESTRO = UUID.fromString("25e97ff7-24ce-4c4c-8951-f764a708f7b5");

    private PixelBudsBatteryBridge() {}

    public static void install() {
        Map<BluetoothSocket, Frames> sockets = new WeakHashMap<>();
        var writer = Executors.newSingleThreadExecutor(task -> new Thread(task, "Pixel Buds ANC"));
        BroadcastReceiver requests = new BroadcastReceiver() {
            private int nextCallId = 0x3fff0000;

            @Override public void onReceive(Context context, Intent intent) {
                if (!SYSTEM_UI.equals(getSentFromPackage())) return;
                String address = intent.getStringExtra(ADDRESS);
                int mode = intent.getIntExtra(MODE, 0);
                if (address == null || mode < 1 || mode > 3) return;
                writer.execute(() -> {
                    synchronized (sockets) {
                        for (var entry : sockets.entrySet()) {
                            BluetoothSocket socket = entry.getKey();
                            Frames frames = entry.getValue();
                            if (frames == null || socket == null || !socket.isConnected()
                                    || !address.equals(socket.getRemoteDevice().getAddress())
                                    || !PixelBudsMaestro.supportsChannel(frames.channel)
                                    || mode != PixelBudsSource.nextMode(frames.mode)) continue;
                            long now = SystemClock.uptimeMillis();
                            if (frames.requestedMode != 0 && now - frames.requestedAt < 2_000) return;
                            frames.callId = nextCallId++;
                            frames.requestedMode = mode;
                            frames.requestedAt = now;
                            try {
                                socket.getOutputStream().write(PixelBudsMaestro.setMode(frames.channel, mode, frames.callId));
                                return;
                            } catch (IOException | RuntimeException error) {
                                frames.requestedMode = 0;
                                XLog.once("Pixel Buds companion ANC write", error);
                            }
                            break;
                        }
                    }
                    relay(address, null, UNCHANGED, true);
                });
            }
        };
        XposedHelpers.findAndHookMethod(BluetoothSocket.class, "read", byte[].class, int.class, int.class,
                new XC_MethodHook() {
                    private boolean commandsRegistered;

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.getResult() instanceof Integer count) || count <= 0) return;
                        BluetoothSocket socket = (BluetoothSocket) param.thisObject;
                        synchronized (sockets) {
                            Frames frames = sockets.get(socket);
                            if (frames == null) {
                                if (sockets.containsKey(socket)) return;
                                BluetoothDevice device = socket.getRemoteDevice();
                                if (!(Refl.get(socket, "mUuid") instanceof ParcelUuid uuid)
                                        || !MAESTRO.equals(uuid.getUuid()) || device == null
                                        || !PixelBudsSource.matches(device)) {
                                    sockets.put(socket, null);
                                    return;
                                }
                                frames = new Frames();
                                sockets.put(socket, frames);
                                if (!commandsRegistered) commandsRegistered = registerCommands(requests);
                                relay(device.getAddress(), null, 0, false);
                            }
                            String address = socket.getRemoteDevice().getAddress();
                            byte[] bytes = (byte[]) param.args[0];
                            int offset = (Integer) param.args[1];
                            for (int i = offset; i < offset + count; i++) {
                                PixelBudsMaestro.Packet packet = frames.accept(bytes[i]);
                                if (packet == null) continue;
                                frames.onPacket(address, packet);
                            }
                        }
                    }
                });
        XLog.d("Pixel Buds 2a: companion battery hook installed");
    }

    private static boolean registerCommands(BroadcastReceiver requests) {
        try {
            Context context = currentApplication();
            if (context == null) return false;
            context.registerReceiver(requests, new IntentFilter(SET_MODE),
                    "android.permission.BLUETOOTH_CONNECT", null, Context.RECEIVER_EXPORTED);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            XLog.once("Pixel Buds companion ANC receiver", error);
            return false;
        }
    }

    private static Context currentApplication() throws ClassNotFoundException {
        Object app = Refl.callStatic(Class.forName("android.app.ActivityThread"), "currentApplication");
        return app instanceof Context context ? context : null;
    }

    private static void relay(String address, int[] levels, int mode, boolean rejected) {
        try {
            Context context = currentApplication();
            if (context == null) return;
            Intent intent = new Intent(ACTION).setPackage(SYSTEM_UI).putExtra(ADDRESS, address);
            if (levels != null) intent.putExtra(LEVELS, levels);
            if (mode != UNCHANGED) intent.putExtra(MODE, mode);
            if (rejected) intent.putExtra(REJECTED, true);
            send(context, intent);
        } catch (ReflectiveOperationException | RuntimeException error) {
            XLog.once("Pixel Buds battery relay", error);
        }
    }

    static void send(Context context, Intent intent) {
        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        context.sendBroadcast(intent, "android.permission.BLUETOOTH_CONNECT", options.toBundle());
    }

    static int[] batteryLevels(PixelBudsMaestro.Packet packet) {
        return packet != null && packet.method() == PixelBudsMaestro.BATTERY
                && packet.type() == PixelBudsMaestro.STREAM && packet.status() == 0
                ? packet.batteries() : null;
    }

    /** RFCOMM reads may split or combine HDLC frames. */
    static final class Frames {
        private static final int FLAG = 0x7e;
        private static final int MAX_WIRE_FRAME_BYTES = 8192;
        private final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        private boolean collecting;
        int channel;
        int mode;
        int callId;
        int requestedMode;
        long requestedAt;

        void onPacket(String address, PixelBudsMaestro.Packet packet) {
            if (PixelBudsMaestro.supportsChannel(packet.channel())) channel = packet.channel();
            int[] levels = batteryLevels(packet);
            if (levels != null) relay(address, levels, UNCHANGED, false);
            if (packet.method() == PixelBudsMaestro.WRITE_MODE && packet.type() == PixelBudsMaestro.RESPONSE
                    && requestedMode != 0 && packet.callId() == callId) {
                boolean accepted = packet.status() == 0;
                if (accepted) mode = requestedMode;
                requestedMode = 0;
                XLog.d("Pixel Buds 2a: companion ANC command " + (accepted ? "confirmed" : "rejected"));
                relay(address, null, mode, !accepted);
                return;
            }
            if (packet.status() != 0) return;
            boolean read = packet.method() == PixelBudsMaestro.READ_MODE && packet.type() == PixelBudsMaestro.RESPONSE;
            boolean update = packet.method() == PixelBudsMaestro.SETTINGS && packet.type() == PixelBudsMaestro.STREAM;
            if (!read && !update) return;
            int reported = packet.mode();
            if (reported < 0 || reported > 4) return;
            mode = reported;
            XLog.d("Pixel Buds 2a: companion ANC state " + mode);
            relay(address, null, mode, false);
        }

        PixelBudsMaestro.Packet accept(byte value) {
            if ((value & 0xff) != FLAG) {
                if (frame.size() == MAX_WIRE_FRAME_BYTES) collecting = false;
                if (collecting) frame.write(value & 0xff);
                return null;
            }
            PixelBudsMaestro.Packet packet = null;
            if (collecting && frame.size() > 0) {
                frame.write(FLAG);
                try {
                    packet = PixelBudsMaestro.read(new ByteArrayInputStream(frame.toByteArray()));
                } catch (IOException ignored) {
                    // Treat an incomplete frame as unavailable.
                }
            }
            collecting = true;
            frame.reset();
            return packet;
        }
    }
}
