package dev.android.dynamicisland.island.source;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Bluetooth wire format documented in OpenControl's PROTOCOL.md, sections 2.2a and 4. */
final class PixelBudsMaestro {
    static final int SOFTWARE = 0x7199fa44;
    static final int READ_MODE = 0xaed0ae51;
    static final int WRITE_MODE = 0x9e8c9a1d;
    static final int SETTINGS = 0x2821adf5;
    static final int BATTERY = 0xe61e8290;
    static final int RESPONSE = 1;
    static final int STREAM = 7;
    private static final int MAESTRO = 0x7ede71ea;
    private static final int FLAG = 0x7e;
    private static final int ESCAPE = 0x7d;
    private static final int MAX_FRAME = 4096;

    private PixelBudsMaestro() {}

    record Packet(int channel, int method, int type, int status, int callId, byte[] payload) {
        int mode() {
            return parse(parse(payload).bytes(4)).number(13, -1);
        }

        int[] batteries() {
            Fields levels = parse(parse(payload).bytes(6));
            return new int[] { level(levels.bytes(2)), level(levels.bytes(3)), level(levels.bytes(1)) };
        }

        private static int level(byte[] component) {
            int percentage = parse(component).number(1, -1);
            return percentage >= 0 && percentage <= 100 ? percentage : -1;
        }
    }

    static boolean supportsChannel(int channel) {
        return address(channel) != 0;
    }

    static byte[] setMode(int channel, int mode) {
        if (mode < 1 || mode > 3) throw new IllegalArgumentException("Unknown noise control mode");
        return request(channel, WRITE_MODE, (byte) 0x22, (byte) 2, (byte) 0x68, (byte) mode);
    }

    static byte[] request(int channel, int method, byte... payload) {
        int address = address(channel);
        if (address == 0) throw new IllegalArgumentException("Unknown Maestro channel");
        ByteBuffer buffer = ByteBuffer.allocate(15 + (payload.length == 0 ? 0 : 2 + payload.length) + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) address).put((byte) 3);
        buffer.put((byte) 0x10).put((byte) channel);
        buffer.put((byte) 0x1d).putInt(MAESTRO).put((byte) 0x25).putInt(method);
        if (payload.length > 0) buffer.put((byte) 0x2a).put((byte) payload.length).put(payload);
        buffer.putInt(crc(buffer.array(), buffer.position()));

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(FLAG);
        for (byte value : buffer.array()) {
            int octet = value & 0xff;
            if (octet == FLAG || octet == ESCAPE) {
                wire.write(ESCAPE);
                octet ^= 0x20;
            }
            wire.write(octet);
        }
        wire.write(FLAG);
        return wire.toByteArray();
    }

    private static int address(int channel) {
        return switch (channel) {
            case 19 -> 0x3b00;
            case 21 -> 0x4b00;
            case 24 -> 0x3d80;
            case 26 -> 0x4d80;
            default -> 0;
        };
    }

    /** Reads through a frame delimiter. An invalid frame is discarded without losing the stream. */
    static Packet read(InputStream input) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        boolean escaped = false;
        boolean invalid = false;
        for (int octet; (octet = input.read()) != -1;) {
            if (octet == FLAG) {
                if (invalid || escaped) return null;
                if (frame.size() > 0) return decode(frame.toByteArray());
            } else if (frame.size() >= MAX_FRAME) {
                invalid = true;
            } else if (escaped) {
                if (octet != (FLAG ^ 0x20) && octet != (ESCAPE ^ 0x20)) invalid = true;
                frame.write(octet ^ 0x20);
                escaped = false;
            } else if (octet == ESCAPE) {
                escaped = true;
            } else {
                frame.write(octet);
            }
        }
        throw new EOFException();
    }

    private static Packet decode(byte[] frame) {
        int end = frame.length - 4;
        if (end < 3) return null;
        ByteBuffer buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        if (crc(frame, end) != buffer.getInt(end)) return null;
        buffer.limit(end);
        while (buffer.hasRemaining() && (buffer.get() & 1) == 0) {
            // HDLC addresses end at the first byte whose low bit is set.
        }
        if (!buffer.hasRemaining() || buffer.get() != 3) return null;
        Fields rpc = parse(buffer);
        if (rpc.number(3, 0) != MAESTRO) return null;
        return new Packet(rpc.number(2, 0), rpc.number(4, 0), rpc.number(1, 0),
                rpc.number(6, 0), rpc.number(7, 0), rpc.bytes(5));
    }

    private static int crc(byte[] bytes, int size) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, size);
        return (int) crc.getValue();
    }

    private record Fields(Map<Integer, Object> values) {
        int number(int field, int fallback) {
            return values.get(field) instanceof Integer value ? value : fallback;
        }

        byte[] bytes(int field) {
            return values.get(field) instanceof byte[] value ? value : new byte[0];
        }
    }

    private static Fields parse(byte[] bytes) {
        return parse(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN));
    }

    private static Fields parse(ByteBuffer input) {
        Map<Integer, Object> fields = new HashMap<>();
        try {
            while (input.hasRemaining()) {
                int tag = Math.toIntExact(varint(input));
                int field = tag >>> 3;
                if (field == 0) return new Fields(Map.of());
                switch (tag & 7) {
                    case 0 -> fields.put(field, protobufferInt(varint(input)));
                    case 1 -> input.getLong();
                    case 2 -> {
                        int length = Math.toIntExact(varint(input));
                        if (length < 0 || length > input.remaining()) return new Fields(Map.of());
                        byte[] value = new byte[length];
                        input.get(value);
                        fields.put(field, value);
                    }
                    case 5 -> fields.put(field, input.getInt());
                    default -> { return new Fields(Map.of()); }
                }
            }
            return new Fields(fields);
        } catch (IllegalArgumentException | ArithmeticException | java.nio.BufferUnderflowException ignored) {
            return new Fields(Map.of());
        }
    }

    private static long varint(ByteBuffer input) {
        long number = 0;
        for (int shift = 0; shift <= 63; shift += 7) {
            int octet = input.get() & 0xff;
            if (shift == 63 && octet > 1) break;
            number |= (long) (octet & 127) << shift;
            if ((octet & 128) == 0) return number;
        }
        throw new IllegalArgumentException("Invalid protobuf varint");
    }

    private static Object protobufferInt(long number) {
        if (number >= 0 && number <= 0xffffffffL) return (int) number;
        return number;
    }
}
