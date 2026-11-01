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

final class FFT {
    private FFT() {
    }

    static void transform(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double a = -2 * Math.PI / len, wr0 = Math.cos(a), wi0 = Math.sin(a);
            for (int i = 0; i < n; i += len) {
                double wr = 1, wi = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j, v = u + len / 2;
                    double vr = re[v] * wr - im[v] * wi, vi = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    double t = wr * wr0 - wi * wi0;
                    wi = wr * wi0 + wi * wr0;
                    wr = t;
                }
            }
        }
    }
}
