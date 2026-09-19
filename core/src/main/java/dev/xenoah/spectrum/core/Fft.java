package dev.xenoah.spectrum.core;

/** In-place, unnormalised, radix-2 complex FFT. No Android dependencies. */
public final class Fft {
    private final int size;
    private final int[] reverse;
    private final double[] cos, sin;

    public Fft(int size) {
        if (size < 2 || (size & (size - 1)) != 0) throw new IllegalArgumentException("power of two required");
        this.size = size;
        int bits = Integer.numberOfTrailingZeros(size);
        reverse = new int[size];
        cos = new double[size / 2]; sin = new double[size / 2];
        for (int i = 0; i < size; i++) reverse[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < size / 2; i++) {
            cos[i] = Math.cos(-2 * Math.PI * i / size);
            sin[i] = Math.sin(-2 * Math.PI * i / size);
        }
    }

    public void transform(double[] re, double[] im) {
        if (re.length != size || im.length != size) throw new IllegalArgumentException("size mismatch");
        for (int i = 0; i < size; i++) {
            int j = reverse[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= size; len <<= 1) {
            int half = len / 2, stride = size / len;
            for (int start = 0; start < size; start += len) {
                for (int j = 0; j < half; j++) {
                    int a = start + j, b = a + half, k = j * stride;
                    double tr = re[b] * cos[k] - im[b] * sin[k];
                    double ti = re[b] * sin[k] + im[b] * cos[k];
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
        }
    }
}
