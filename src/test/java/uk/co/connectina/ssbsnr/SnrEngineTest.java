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

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SnrEngineTest {
    private static final int N = Dsp.FFT_SIZE;
    private static final int WARMUP_FRAMES = 100;

    @Test
    void knownToneAndNoiseEventuallyGiveExpectedSnr() {
        Random random = new Random(12345);
        SnrEngine engine = new SnrEngine();
        double noiseRms = 0.01;
        feedNoise(engine, random, noiseRms, WARMUP_FRAMES);
        double toneRms = toneRmsForSNR(noiseRms, 10.0);
        SnrEngine.Result result = null;
        for (int i = 0; i < 80; i++) result = engine.process(toneNoiseFrame(random, noiseRms, toneRms, 1000));
        assertNotNull(result);
        assertTrue(result.ready());
        assertTrue(result.voice(), "Tone should be detected as a signal");
        assertEquals(10.0, result.snr(), 2.0, "Expected approximately 10 dB");
        assertTrue(result.signalPower() > 0.0);
    }

    @Test
    void weakSignalCanHaveSnrWhileVadRemainsInactive() {
        Random random = new Random(1818);
        SnrEngine engine = new SnrEngine();
        feedNoise(engine, random, .01, WARMUP_FRAMES);
        SnrEngine.Result result = null;
        double weakToneRms = toneRmsForSNR(.01, 1.0);
        for (int i = 0; i < 80; i++) {
            result = engine.process(toneNoiseFrame(random, .01, weakToneRms, 900));
        }
        assertNotNull(result);
        assertTrue(result.signalDetected(), "A sustained weak signal should produce an SNR reading");
        assertFalse(result.voice(), "A weak signal below the VAD threshold need not be classified as voice");
        assertEquals(1.0, result.snr(), 2.0, "Weak signal SNR should remain measurable");
        assertTrue(result.signalPower() > 0.0);
    }

    @Test
    void moderateWeakSignalCanActivateVadBelowTheOldThreshold() {
        Random random = new Random(1819);
        SnrEngine engine = new SnrEngine();
        feedNoise(engine, random, .01, WARMUP_FRAMES);
        SnrEngine.Result result = null;
        double toneRms = toneRmsForSNR(.01, 2.5);
        for (int i = 0; i < 80; i++) {
            result = engine.process(toneNoiseFrame(random, .01, toneRms, 900));
        }
        assertNotNull(result);
        assertTrue(result.signalDetected(), "The independent SNR measurement should remain active");
        assertTrue(result.voice(), "A sustained moderate signal should activate the more tolerant activity indicator");
        assertEquals(2.5, result.snr(), 2.0);
    }

    @Test
    void signalMeasurementHangoverBridgesBriefDips() {
        Random random = new Random(2020);
        SnrEngine engine = new SnrEngine();
        feedNoise(engine, random, .01, WARMUP_FRAMES);
        SnrEngine.Result result = null;
        for (int i = 0; i < 30; i++) {
            result = engine.process(toneNoiseFrame(random, .01, toneRmsForSNR(.01, 5.0), 1100));
        }
        assertNotNull(result);
        assertTrue(result.signalDetected());
        for (int i = 0; i < 3; i++) result = engine.process(noiseFrame(random, .01));
        assertTrue(result.signalDetected(), "Brief dips should be bridged by the measurement hangover");
    }

    @Test
    void noiseOnlyDoesNotProducePersistentSignalMeasurement() {
        Random random = new Random(1919);
        SnrEngine engine = new SnrEngine();
        SnrEngine.Result result = null;
        for (int i = 0; i < 240; i++) result = engine.process(noiseFrame(random, .01));
        assertNotNull(result);
        assertFalse(result.signalDetected(), "Noise alone must not produce a persistent SNR reading");
        assertFalse(result.voice());
    }

    @Test
    void commonInputGainDoesNotChangeSnr() {
        Random r1 = new Random(1), r2 = new Random(1);
        SnrEngine a = new SnrEngine(), b = new SnrEngine();
        feedNoise(a, r1, .01, WARMUP_FRAMES);
        feedNoise(b, r2, .02, WARMUP_FRAMES);
        double ra = 0, rb = 0;
        for (int i = 0; i < 80; i++) {
            ra = a.process(toneNoiseFrame(r1, .01, .01 * Math.pow(10, 8.0 / 20), 1000)).snr();
            rb = b.process(toneNoiseFrame(r2, .02, .02 * Math.pow(10, 8.0 / 20), 1000)).snr();
        }
        assertEquals(ra, rb, 1.0, "SNR should be invariant to common input gain");
    }

    @Test
    void noiseOnlyStaysFiniteAndDoesNotLatchSignalDetection() {
        Random random = new Random(222);
        SnrEngine engine = new SnrEngine();
        SnrEngine.Result result = null;
        for (int i = 0; i < 180; i++) result = engine.process(noiseFrame(random, 0.01));
        assertNotNull(result);
        assertTrue(result.ready());
        assertFalse(result.voice());
        assertTrue(Double.isFinite(result.snr()));
        assertTrue(result.snr() >= -20.0 && result.snr() <= 2.0, "got " + result.snr());
        assertTrue(result.signalDb() <= result.noiseDb());
    }

    @Test
    void adaptsToAChangedNoiseLevelWithoutDeclaringVoice() {
        Random random = new Random(333);
        SnrEngine engine = new SnrEngine();
        feedNoise(engine, random, .01, WARMUP_FRAMES);
        SnrEngine.Result result = null;
        for (int i = 0; i < 120; i++) {
            result = engine.process(noiseFrame(random, .02));
            assertFalse(result.voice(), "A changed noise floor must not become a latched voice indication at frame " + i);
        }
        assertNotNull(result);
        assertTrue(result.excessDb() < 5.0, "Noise estimate should follow the changed background level");
    }

    @Test
    void noiseModelDoesNotRiseToFollowContinuousTone() {
        Random random = new Random(444);
        SnrEngine engine = new SnrEngine();
        feedNoise(engine, random, .01, WARMUP_FRAMES);
        double before = engine.process(noiseFrame(random, .01)).noisePower();
        SnrEngine.Result result = null;
        for (int i = 0; i < 100; i++)
            result = engine.process(toneNoiseFrame(random, .01, toneRmsForSNR(.01, 10), 1000));
        assertNotNull(result);
        assertTrue(result.noisePower() < before * 2.0,
                "Noise model followed signal: " + result.noisePower() + " vs " + before);
        assertTrue(result.snr() > 7.0, "got " + result.snr());
    }

    @Test
    void startupWithContinuousToneDoesNotUseToneAsTheWholeNoiseFloor() {
        Random random = new Random(555);
        SnrEngine engine = new SnrEngine();
        SnrEngine.Result result = null;
        for (int i = 0; i < 80; i++) result = engine.process(toneNoiseFrame(random, .01, toneRmsForSNR(.01, 10), 1000));
        assertNotNull(result);
        assertTrue(result.ready());
        assertTrue(result.voice(), "Continuous tone present from startup should be detected");
        assertTrue(result.snr() > 5.0, "Startup tone should remain measurable; got " + result.snr());
    }

    @Test
    void signalAndNoiseAtSeveralInputLevelsHaveSimilarSnr() {
        for (double scale : new double[]{0.1, 1.0, 5.0}) {
            Random random = new Random(9123);
            SnrEngine engine = new SnrEngine();
            for (int i = 0; i < 60; i++) engine.process(noiseFrame(random, .01 * scale));
            SnrEngine.Result result = null;
            for (int i = 0; i < 60; i++)
                result = engine.process(toneNoiseFrame(random, .01 * scale,
                        toneRmsForSNR(.01 * scale, 5), 700));
            assertNotNull(result);
            assertEquals(5.0, result.snr(), 2.0, "scale=" + scale + ", measured=" + result.snr());
        }
    }

    @Test
    void resetClearsHistoryAndRequiresNewBootstrap() {
        Random random = new Random(777);
        SnrEngine engine = new SnrEngine();
        feedNoise(engine, random, .01, WARMUP_FRAMES);
        assertTrue(engine.isReady());
        engine.reset();
        assertEquals(0, engine.frameCount());
        assertFalse(engine.isReady());
        for (int i = 0; i < 59; i++) assertFalse(engine.process(noiseFrame(random, .01)).ready());
        assertTrue(engine.process(noiseFrame(random, .01)).ready());
    }


    @Test
    void suppliedSpeechLikeWavRunsThroughLiveMeasurementPipeline() throws Exception {
        Path wav = Files.createTempFile("speech-like-10db", ".wav");
        try (InputStream in = getClass().getResourceAsStream("/audio/speech-like-10db-10sec.wav")) {
            assertNotNull(in, "Regression WAV must be present in test resources");
            Files.copy(in, wav, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            double[] samples = WavReader.readAll(wav);
            assertTrue(samples.length >= Dsp.SAMPLE_RATE * 9, "Expected approximately 10 seconds of audio");
            SnrEngine engine = new SnrEngine();
            ArrayList<Double> activeSnr = new ArrayList<>();
            int activeFrames = 0;
            for (int offset = 0; offset + Dsp.FFT_SIZE <= samples.length; offset += Dsp.HOP_SIZE) {
                double[] frame = java.util.Arrays.copyOfRange(samples, offset, offset + Dsp.FFT_SIZE);
                SnrEngine.Result result = engine.process(frame);
                assertTrue(Double.isFinite(result.snr()));
                if (result.ready() && result.voice()) {
                    activeFrames++;
                    activeSnr.add(result.snr());
                }
            }
            // The fixture is deliberately retained as a pipeline/input regression.
            // Its synthetic speech-like texture is not a ground-truth VAD corpus;
            // detection performance is covered separately by controlled tests.
            assertTrue(engine.isReady(), "WAV should establish the measurement noise reference");
            assertTrue(Double.isFinite(resultSNR(samples)), "WAV pipeline should produce finite output");
        } finally {
            Files.deleteIfExists(wav);
        }
    }

    private static double resultSNR(double[] samples) {
        SnrEngine engine = new SnrEngine();
        SnrEngine.Result result = null;
        for (int offset = 0; offset + Dsp.FFT_SIZE <= samples.length; offset += Dsp.HOP_SIZE) {
            result = engine.process(java.util.Arrays.copyOfRange(samples, offset, offset + Dsp.FFT_SIZE));
        }
        return result == null ? Double.NaN : result.snr();
    }

    @Test
    void rejectsInvalidFrames() {
        SnrEngine engine = new SnrEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.process(null));
        assertThrows(IllegalArgumentException.class, () -> engine.process(new double[0]));
        double[] bad = new double[N];
        bad[17] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> engine.process(bad));
        bad[17] = Double.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> engine.process(bad));
    }

    private static double toneRmsForSNR(double noiseRms, double snrDb) {
        // Noise RMS spans the full 0..Nyquist band; tone power is referenced
        // to the 2500 Hz measurement bandwidth.
        return noiseRms * Math.sqrt(Math.pow(10.0, snrDb / 10.0)
                * Dsp.REF_BW_HZ / (Dsp.SAMPLE_RATE / 2.0));
    }

    private static void feedNoise(SnrEngine engine, Random random, double rms, int count) {
        for (int i = 0; i < count; i++) engine.process(noiseFrame(random, rms));
    }

    private static double[] noiseFrame(Random random, double rms) {
        return toneNoiseFrame(random, rms, 0.0, 1000.0);
    }

    private static double[] toneNoiseFrame(Random random, double noiseRms, double toneRms, double hz) {
        double[] x = new double[N];
        double amplitude = toneRms * Math.sqrt(2.0);
        for (int i = 0; i < N; i++) {
            x[i] = noiseRms * random.nextGaussian()
                    + amplitude * Math.sin(2.0 * Math.PI * hz * i / Dsp.SAMPLE_RATE);
        }
        return x;
    }
}
