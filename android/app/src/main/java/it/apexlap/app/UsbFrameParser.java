package it.apexlap.app;

/**
 * Legge i pacchetti che il GPS manda sul cavo USB, mescolati alle righe di testo del Monitor seriale.
 * Pacchetto: A5 5A, tipo, lunghezza, dati, somma (tipo + lunghezza + dati, modulo 256).
 * Tipo 1 = una posizione da 20 byte, tipo 2 = stato da 7 byte. Tutto il resto viene ignorato.
 */
public class UsbFrameParser {

    public interface Listener {
        void onFrame(int type, byte[] data);
    }

    private final Listener listener;
    private int state = 0, type = 0, len = 0, idx = 0, sum = 0;
    private final byte[] buf = new byte[64];

    public UsbFrameParser(Listener l) {
        listener = l;
    }

    public void feed(byte[] data, int n) {
        for (int i = 0; i < n; i++) feed(data[i] & 0xFF);
    }

    public void feed(int b) {
        switch (state) {
            case 0:
                if (b == 0xA5) state = 1;
                break;
            case 1:
                state = (b == 0x5A) ? 2 : (b == 0xA5 ? 1 : 0);
                break;
            case 2:
                type = b;
                state = (b == 1 || b == 2) ? 3 : 0;
                break;
            case 3:
                len = b;
                if (len == 0 || len > buf.length) { state = 0; break; }
                sum = (type + len) & 0xFF;
                idx = 0;
                state = 4;
                break;
            case 4:
                buf[idx++] = (byte) b;
                sum = (sum + b) & 0xFF;
                if (idx >= len) state = 5;
                break;
            case 5:
                state = 0;
                if (b == sum) {
                    byte[] out = new byte[len];
                    System.arraycopy(buf, 0, out, 0, len);
                    listener.onFrame(type, out);
                }
                break;
        }
    }
}
