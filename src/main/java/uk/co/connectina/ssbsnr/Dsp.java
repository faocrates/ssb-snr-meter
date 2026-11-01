/*
 SSB SNR Meter - A software tool for estimating the audio-band signal-to-noise ratio of live receiver audio.
 Copyright (C) 2026  Dr Christos Bohoris

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package uk.co.connectina.ssbsnr;

import java.util.Arrays;

/**
 * DSP primitives used by the SSB SNR meter.
 */
final class Dsp {
    static final int SAMPLE_RATE = 16000;
    static final int FFT_SIZE = 4096;
    static final int HOP_SIZE = 2048;
    static final double REF_BW_HZ = 2500.0;
    static final double BAND_LOW_HZ = 100.0;
    static final double BAND_HIGH_HZ = 2600.0;

    private static final double WINDOW_SUM_SQUARES = sumSquares(hann());

    private Dsp() {
    }

    private static double sumSquares(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value * value;
        return sum;
    }

    static double[] hann() {
        double[] w = new double[FFT_SIZE];
        for (int i = 0; i < w.length; i++)
            w[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (w.length - 1));
        return w;
    }

    /**
     * One-sided PSD in linear V^2/Hz, with the usual Hann-window correction.
     */
    static double[] oneSidedPsd(double[] frame, double[] window) {
        int n = frame.length;
        double[] re = Arrays.copyOf(frame, n);
        double[] im = new double[n];
        double mean = 0;
        for (double v : re) mean += v;
        mean /= n;
        for (int i = 0; i < n; i++) re[i] = (re[i] - mean) * window[i];

        FFT.transform(re, im);
        double scale = 1.0 / (SAMPLE_RATE * WINDOW_SUM_SQUARES);
        double[] psd = new double[n / 2 + 1];
        for (int k = 0; k < psd.length; k++) {
            double p = (re[k] * re[k] + im[k] * im[k]) * scale;
            if (k != 0 && k != n / 2) p *= 2.0;
            psd[k] = p;
        }
        return psd;
    }

    static double binHz() {
        return (double) SAMPLE_RATE / FFT_SIZE;
    }

    static int lowBin() {
        return (int) Math.ceil(BAND_LOW_HZ / binHz());
    }

    static int highBin() {
        return (int) Math.floor(BAND_HIGH_HZ / binHz());
    }

    /**
     * Integrates a one-sided PSD between the defined SSB band edges.
     */
    static double bandPower(double[] psd, double lowHz, double highHz) {
        double df = binHz();
        int lo = Math.max(1, (int) Math.ceil(lowHz / df));
        int hi = Math.min(psd.length - 1, (int) Math.floor(highHz / df));
        double p = 0;
        for (int k = lo; k <= hi; k++) p += psd[k] * df;
        return p;
    }

    static double db(double x) {
        return 10.0 * Math.log10(Math.max(x, 1e-20));
    }

    static double spectralFlatness(double[] psd, double lowHz, double highHz) {
        int lo = Math.max(1, (int) Math.ceil(lowHz / binHz()));
        int hi = Math.min(psd.length - 1, (int) Math.floor(highHz / binHz()));
        double sumLog = 0, sum = 0;
        int n = 0;
        for (int k = lo; k <= hi; k++) {
            double p = Math.max(psd[k], 1e-20);
            sumLog += Math.log(p);
            sum += p;
            n++;
        }
        if (n == 0 || sum <= 0) return 1;
        return Math.exp(sumLog / n) / (sum / n);
    }

    static double[] spectrumDb(double[] psd) {
        double[] out = new double[psd.length];
        for (int k = 0; k < out.length; k++) out[k] = db(psd[k]);
        return out;
    }
}
