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
import java.io.*;
import java.nio.file.*;

final class WavReader {
    static double[] readAll(Path p) throws Exception {
        AudioInputStream src = AudioSystem.getAudioInputStream(p.toFile());
        AudioFormat dst = new AudioFormat(16000, 16, 1, true, false);
        try (AudioInputStream in = AudioSystem.getAudioInputStream(dst, src); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            byte[] d = out.toByteArray();
            double[] x = new double[d.length / 2];
            for (int i = 0; i < x.length; i++) {
                int lo = d[2 * i] & 255, hi = d[2 * i + 1];
                x[i] = ((short) ((hi << 8) | lo)) / 32768.0;
            }
            return x;
        }
    }
}
