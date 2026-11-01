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

import javax.sound.sampled.*;

final class AudioWorker extends Thread {
    private final String name;
    private final Main ui;
    private volatile boolean run = true;
    private TargetDataLine line;

    AudioWorker(String n, Main u) {
        name = n;
        ui = u;
        setDaemon(true);
    }

    public void run() {
        try {
            Mixer m = null;
            for (Mixer.Info i : AudioSystem.getMixerInfo()) if (i.getName().equals(name)) m = AudioSystem.getMixer(i);
            AudioFormat f = new AudioFormat(16000, 16, 1, true, false);
            line = (TargetDataLine) m.getLine(new DataLine.Info(TargetDataLine.class, f));
            line.open(f, Dsp.FFT_SIZE * 2);
            line.start();
            byte[] b = new byte[Dsp.HOP_SIZE * 2];
            double[] ring = new double[Dsp.FFT_SIZE];
            int fill = 0;
            SnrEngine e = new SnrEngine();
            while (run) {
                int n = line.read(b, 0, b.length);
                if (n <= 0) continue;
                double[] chunk = new double[n / 2];
                for (int i = 0; i < chunk.length; i++) {
                    int lo = b[2 * i] & 255, hi = b[2 * i + 1];
                    chunk[i] = ((short) ((hi << 8) | lo)) / 32768.0;
                }
                for (double sample : chunk) {
                    if (fill < Dsp.FFT_SIZE) ring[fill++] = sample;
                    if (fill == Dsp.FFT_SIZE) {
                        ui.update(e.process(ring.clone()), Dsp.HOP_SIZE);
                        System.arraycopy(ring, Dsp.HOP_SIZE, ring, 0, Dsp.FFT_SIZE - Dsp.HOP_SIZE);
                        fill = Dsp.FFT_SIZE - Dsp.HOP_SIZE;
                    }
                }
            }
        } catch (Exception ex) {
            ui.error(ex.toString());
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
        }
    }

    void shutdown() {
        run = false;
        if (line != null) line.stop();
    }
}
