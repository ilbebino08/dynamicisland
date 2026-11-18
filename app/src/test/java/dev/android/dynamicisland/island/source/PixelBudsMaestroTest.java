package dev.android.dynamicisland.island.source;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.util.HexFormat;
import org.junit.Test;

public class PixelBudsMaestroTest {
    // OpenControl CAP-078 (Pro 2), frames 6540 and 6493. These are Bluetooth captures.
    @Test public void readsConfirmedModeAndEncodesTheThreeModeWrite() throws Exception {
        assertArrayEquals(hex("7e004b0310151dea71de7d5e251d9a8c9e2a0422026803e40424ea7e"),
                PixelBudsMaestro.setMode(21, 3));
        byte[] frame = hex("7e00a5032a0422026802080710151dea71de7d5e25f5ad2128cbb358d57e");
        PixelBudsMaestro.Packet packet = PixelBudsMaestro.read(new ByteArrayInputStream(frame));
        assertEquals(21, packet.channel());
        assertEquals(2, packet.mode());
        frame[8] ^= 1;
        assertNull(PixelBudsMaestro.read(new ByteArrayInputStream(frame)));
    }

    // OpenControl CAP-062 (Pro 2), frame 4845, has all three battery readings.
    @Test public void decodesTheThreeBatteriesInDisplayOrder() throws Exception {
        byte[] frame = hex("7e80a3032a1e180032120a04083c10011204086410021a04086410023a06080110011800080710131dea71de7d5e2590821ee6160866417e");
        PixelBudsMaestro.Packet packet = PixelBudsMaestro.read(new ByteArrayInputStream(frame));
        assertArrayEquals(new int[] {100, 100, 60}, packet.batteries());
    }

    private static byte[] hex(String text) {
        return HexFormat.of().parseHex(text);
    }
}
