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

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Streaming audio-band SNR estimator.
 * <p>
 * Noise power is integrated from a per-bin noise PSD over 100..2600 Hz.
 * Signal power is max(measured band power - estimated noise power, 0).
 * The displayed SNR is 10 log10(signal/noise), smoothed and limited to a
 * display range. This is an audio engineering estimate, not a calibrated RF
 * S-meter or a bit-for-bit reproduction of another radio application.
 */
final class SnrEngine {
    private static final int NOISE_HISTORY_FRAMES = 60;
    // 60 hops at 50% overlap gives roughly 7.7 seconds of startup context.
    // Per-bin medians reduce contamination from intermittent speech components.
    private static final int BOOTSTRAP_FRAMES = 60;
    private static final int MIN_HISTORY_FOR_UPDATE = 8;
    private static final double NOISE_EMA = 0.20;
    private static final int VAD_CONFIRM_FRAMES = 2;
    private static final double VAD_ON_DB = 4.0;
    private static final double VAD_OFF_DB = 1.8;
    // Signal measurement is deliberately more sensitive than speech/VAD.
    private static final double SIGNAL_ON_DB = 1.5;
    private static final double SIGNAL_OFF_DB = 0.8;
    private static final int SIGNAL_CONFIRM_FRAMES = 2;
    private static final double SIGNAL_HANGOVER_SECONDS = 0.75;
    private static final double HANGOVER_SECONDS = 0.60;
    private static final double MIN_POWER = 1e-20;
    private static final double SNR_FLOOR_DB = -20.0;
    private static final double SNR_CEILING_DB = 60.0;

    private final double[] window = Dsp.hann();
    private final ArrayDeque<double[]> bootstrap = new ArrayDeque<>();
    private final ArrayDeque<double[]> quietHistory = new ArrayDeque<>();
    private double[] noisePsd;
    private boolean signalPresent;
    private boolean measurementPresent;
    private int signalCandidateFrames;
    private int signalHangoverFrames;
    private int hangoverFrames;
    private int candidateFrames;
    private double smoothedSnrDb = SNR_FLOOR_DB;
    private long frames;

    Result process(double[] input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        if (input.length == 0) throw new IllegalArgumentException("input frame must not be empty");
        double[] frame = new double[Dsp.FFT_SIZE];
        int copied = Math.min(input.length, frame.length);
        for (int i = 0; i < copied; i++) {
            double sample = input[i];
            if (!Double.isFinite(sample)) throw new IllegalArgumentException("input contains a non-finite sample");
            frame[i] = sample;
        }

        double[] psd = Dsp.oneSidedPsd(frame, window);
        double measuredPower = Dsp.bandPower(psd, Dsp.BAND_LOW_HZ, Dsp.BAND_HIGH_HZ);
        double flatness = Dsp.spectralFlatness(psd, 200.0, 2600.0);
        frames++;

        // Build a robust startup reference over several seconds. The median
        // across time reduces the influence of speech syllables and intermittent
        // carriers. This is still an estimate: a continuously occupied channel
        // with no quiet gaps cannot reveal its true noise floor from audio alone.
        if (noisePsd == null) {
            bootstrap.addLast(Arrays.copyOf(psd, psd.length));
            while (bootstrap.size() > BOOTSTRAP_FRAMES) bootstrap.removeFirst();
            if (bootstrap.size() < BOOTSTRAP_FRAMES) {
                double provisionalNoise = Math.max(measuredPower, MIN_POWER);
                return result(SNR_FLOOR_DB, Dsp.db(provisionalNoise), -200.0, false, false,
                        flatness, 0.0, psd, provisionalNoise, 0.0, false);
            }
            noisePsd = bootstrapNoiseSpectrum(bootstrap);
            bootstrap.clear();
        }

        double noisePowerBefore = Math.max(Dsp.bandPower(noisePsd, Dsp.BAND_LOW_HZ, Dsp.BAND_HIGH_HZ), MIN_POWER);
        double excessDb = Dsp.db(Math.max(measuredPower, MIN_POWER) / noisePowerBefore);
        // A single loud/noisy frame must not latch VAD. Require a short run of
        // candidate frames, and keep adapting the noise estimate during this
        // provisional period. This is especially important when receiver noise
        // changes after the initial reference has been established.
        // A receiver gain/noise-floor step raises power across most of the SSB
        // passband. It must be treated as a new noise condition, not speech.
        // Speech and carriers tend to concentrate their extra energy in fewer
        // sub-bands. This check is deliberately independent of spectral flatness,
        // which is not a reliable speech discriminator for radio noise.
        boolean broadbandNoiseRise = isBroadbandNoiseRise(psd, noisePsd);
        boolean candidate = !broadbandNoiseRise && excessDb >= VAD_ON_DB
                && (flatness < 0.97 || excessDb >= 10.0);
        boolean release = excessDb < VAD_OFF_DB || broadbandNoiseRise;

        // Independent, lower-threshold signal gate. This controls whether an
        // SNR estimate is useful enough to display; it does not assert that
        // the signal is speech. A longer hangover bridges brief speech dips.
        boolean measurementCandidate = !broadbandNoiseRise && excessDb >= SIGNAL_ON_DB;
        if (!measurementPresent) {
            signalCandidateFrames = measurementCandidate ? signalCandidateFrames + 1 : 0;
            if (signalCandidateFrames >= SIGNAL_CONFIRM_FRAMES) {
                measurementPresent = true;
                signalHangoverFrames = signalHangoverLengthFrames();
            }
        } else if (excessDb < SIGNAL_OFF_DB || broadbandNoiseRise) {
            if (--signalHangoverFrames <= 0) {
                measurementPresent = false;
                signalCandidateFrames = 0;
            }
        } else {
            signalHangoverFrames = signalHangoverLengthFrames();
        }
        if (!signalPresent) {
            candidateFrames = candidate ? candidateFrames + 1 : 0;
            if (candidateFrames >= VAD_CONFIRM_FRAMES) {
                signalPresent = true;
                hangoverFrames = hangoverLengthFrames();
            }
        } else if (release) {
            if (--hangoverFrames <= 0) {
                signalPresent = false;
                candidateFrames = 0;
            }
        } else {
            hangoverFrames = hangoverLengthFrames();
        }

        // Until VAD is confirmed, treat candidate frames as possible background
        // noise and let the rolling median follow it. Once voice is confirmed,
        // freeze adaptation to avoid learning the wanted signal as noise.
        if ((!signalPresent && !candidate && !measurementCandidate) || broadbandNoiseRise) {
            quietHistory.addLast(Arrays.copyOf(psd, psd.length));
            while (quietHistory.size() > NOISE_HISTORY_FRAMES) quietHistory.removeFirst();
            if (quietHistory.size() >= MIN_HISTORY_FOR_UPDATE) {
                double[] robust = medianNoiseSpectrum(quietHistory);
                for (int k = 0; k < noisePsd.length; k++) {
                    noisePsd[k] = (1.0 - NOISE_EMA) * noisePsd[k] + NOISE_EMA * robust[k];
                }
            }
        }

        double noisePower = Math.max(Dsp.bandPower(noisePsd, Dsp.BAND_LOW_HZ, Dsp.BAND_HIGH_HZ), MIN_POWER);
        double signalPower = Math.max(0.0, measuredPower - noisePower);
        double rawSnr = signalPower > MIN_POWER
                ? 10.0 * Math.log10(signalPower / noisePower)
                : SNR_FLOOR_DB;
        rawSnr = Math.max(SNR_FLOOR_DB, Math.min(SNR_CEILING_DB, rawSnr));
        double alpha = measurementPresent ? 0.28 : 0.12;
        smoothedSnrDb += alpha * (rawSnr - smoothedSnrDb);
        // Signal/SNR visibility follows the independent measurement gate,
        // not the more selective VAD state.
        double signalDb = measurementPresent && signalPower > MIN_POWER ? Dsp.db(signalPower) : -200.0;
        double displayedSnr = smoothedSnrDb;
        return result(displayedSnr, Dsp.db(noisePower), signalDb, signalPresent,
                measurementPresent, flatness, excessDb, psd, noisePower, signalPower, true);
    }


    /**
     * Detects a broad-band increase in receiver noise by comparing integrated
     * power in eight equal sub-bands. At least six must rise by 3 dB. This
     * rejects a broadband noise/gain step while leaving narrow carriers and
     * most speech spectra eligible for VAD.
     */
    private static boolean isBroadbandNoiseRise(double[] current, double[] reference) {
        int low = Dsp.lowBin();
        int high = Dsp.highBin();
        int bins = high - low + 1;
        int elevated = 0;
        for (int band = 0; band < 8; band++) {
            int from = low + band * bins / 8;
            int to = low + (band + 1) * bins / 8 - 1;
            double now = 0.0;
            double before = 0.0;
            for (int k = from; k <= to; k++) {
                now += current[k];
                before += reference[k];
            }
            if (before > MIN_POWER && now >= before * 1.995262) elevated++;
        }
        return elevated >= 6;
    }

    private Result result(double snr, double noiseDb, double signalDb, boolean active,
                          boolean detected, double flatness, double excessDb, double[] psd,
                          double noisePower, double signalPower, boolean ready) {
        return new Result(snr, noiseDb, signalDb, active, detected, flatness, excessDb,
                Dsp.spectrumDb(psd), noisePower, signalPower, ready, frames);
    }

    void reset() {
        bootstrap.clear();
        quietHistory.clear();
        noisePsd = null;
        signalPresent = false;
        measurementPresent = false;
        signalCandidateFrames = 0;
        signalHangoverFrames = 0;
        hangoverFrames = 0;
        candidateFrames = 0;
        smoothedSnrDb = SNR_FLOOR_DB;
        frames = 0;
    }

    long frameCount() {
        return frames;
    }

    boolean isReady() {
        return noisePsd != null;
    }

    private int hangoverLengthFrames() {
        return Math.max(1, (int) Math.round(HANGOVER_SECONDS * Dsp.SAMPLE_RATE / Dsp.HOP_SIZE));
    }

    private int signalHangoverLengthFrames() {
        return Math.max(1, (int) Math.round(SIGNAL_HANGOVER_SECONDS * Dsp.SAMPLE_RATE / Dsp.HOP_SIZE));
    }

    private static double[] bootstrapNoiseSpectrum(ArrayDeque<double[]> history) {
        int bins = history.peekFirst().length;
        int n = history.size();
        double[] out = new double[bins];
        double[] values = new double[n];
        for (int k = 0; k < bins; k++) {
            for (int i = 0; i < n; i++) values[i] = nth(history, i)[k];
            Arrays.sort(values);
            double median = median(values);
            // A persistent narrow carrier is a strong local outlier. Replace
            // its bin with a local median before establishing the noise floor.
            double local = localMedian(nth(history, n - 1), k, 4);
            double cleanMedian = median;
            if (k > 0 && k < bins - 1 && median > Math.max(local * 8.0, MIN_POWER)) cleanMedian = local;
            // Periodogram ordinates of Gaussian noise are approximately
            // exponential; median / ln(2) estimates their mean.
            out[k] = Math.max(cleanMedian / Math.log(2.0), MIN_POWER);
        }
        return out;
    }

    private static double localMedian(double[] spectrum, int bin, int radius) {
        double[] local = new double[radius * 2];
        int n = 0;
        for (int k = Math.max(1, bin - radius); k <= Math.min(spectrum.length - 2, bin + radius); k++) {
            if (Math.abs(k - bin) <= 1) continue;
            local[n++] = spectrum[k];
        }
        if (n == 0) return MIN_POWER;
        Arrays.sort(local, 0, n);
        return n % 2 == 0 ? (local[n / 2 - 1] + local[n / 2]) * 0.5 : local[n / 2];
    }

    private static double[] nth(ArrayDeque<double[]> deque, int index) {
        int i = 0;
        for (double[] value : deque) if (i++ == index) return value;
        throw new IndexOutOfBoundsException(index);
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 0 ? 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]) : sorted[n / 2];
    }

    private static double[] medianNoiseSpectrum(ArrayDeque<double[]> history) {
        int n = history.size();
        int bins = history.peekFirst().length;
        double[] out = new double[bins];
        double[] values = new double[n];
        for (int k = 0; k < bins; k++) {
            int i = 0;
            for (double[] spectrum : history) values[i++] = spectrum[k];
            Arrays.sort(values);
            out[k] = Math.max(median(values) / Math.log(2.0), MIN_POWER);
        }
        return out;
    }

    record Result(double snr, double noiseDb, double signalDb, boolean voice,
                  boolean signalDetected, double spectralFlatness, double excessDb, double[] spectrum,
                  double noisePower, double signalPower, boolean ready, long frameNumber) {
    }
}
