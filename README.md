# Connectina SSB SNR Meter

An open source software tool for estimating the audio-band signal-to-noise ratio of live receiver audio.
The source code is available at: https://github.com/faocrates/ssb-snr-meter

# Usage

- On Windows run ssb-snr-meter\bin\ssb-snr-meter.bat. On Linux run ssb-snr-meter/bin/ssb-snr-meter.sh.
- Ensure that your transceiver and PC are connected (e.g. through a suitable USB cable) so that the audio from the transceiver is available as an audio input to the PC. 
- Select the relevant audio input source and press start. 

## Copyright and Licence

Copyright (C) 2026 Dr Christos Bohoris. Licensed under GNU General Public License v3.0 (GPL-3.0).

# Glossary

### SNR – Signal-to-Noise Ratio
A measurement of how strong a received signal is compared with the background noise. A higher SNR generally indicates a clearer signal relative to the noise.

SSB SNR Meter expresses SNR in decibels (dB), using a reference noise bandwidth of 2,500 Hz.

### dB – Decibel
A logarithmic unit used to express ratios between quantities. In this application, it is used to represent the signal-to-noise ratio.

### dBFS – Decibels Full Scale
A unit used in digital audio to express signal levels relative to the maximum level that can be represented digitally. 0 dBFS represents the maximum digital level; lower levels are expressed as negative values.

### VAD – Voice Activity Detection
A feature that identifies periods of audio activity likely to contain voice. It helps distinguish periods of voice activity from silence or background noise.

VAD is an audio activity indicator, not a guarantee that the detected sound is human speech.

### PSD – Power Spectral Density
A representation of how the power of a signal is distributed across different frequencies. It is used by the application to analyse the received audio and estimate signal and noise power.

### Input Spectrum
A graphical representation of the frequency content of the incoming audio. It helps visualise how the received signal's energy is distributed across the audio frequency range.

### Measurement Band
The frequency range of the incoming audio used for the SNR measurement. SSB SNR Meter uses a band from 100 Hz to 2,600 Hz.

### Reference Bandwidth
The bandwidth used as the reference when expressing noise power and calculating SNR. SSB SNR Meter uses 2,500 Hz, following the same reference-bandwidth convention used by WSJT-X for digital-mode SNR reporting.

The measurement methods are different, so readings from the two applications should not be considered directly interchangeable.

### Noise Floor
The estimated background noise level against which the received signal is evaluated. It provides the reference for determining how much the signal stands above the surrounding noise.

### Maximum SNR
The highest SNR value recorded during the current detected signal period. It represents the best measured signal-to-noise ratio during that transmission.

### Minimum SNR
The lowest SNR value recorded during the current detected signal period.

### Average SNR
The average SNR recorded throughout the current detected signal period. It provides an indication of the overall signal quality rather than just its strongest or weakest moment.

### Signal Duration
The elapsed time for which the application has considered a signal to be present. It helps indicate how long the current signal has been detected.

## Measurement definition

The measurement band is 100–2600 Hz (nominal 2500 Hz reference bandwidth). The engine integrates the one-sided PSD over that band, estimates the noise PSD per frequency bin, and calculates:

    Psignal = max(Pmeasured - Pnoise, 0)
    SNR = 10 log10(Psignal / Pnoise)

This is an engineering estimate from audio samples. It is not a calibrated RF S-meter and is not claimed to match another application's algorithm exactly. Absolute dBFS display calibration does not change SNR.

## Important operating guidance

For best results, start the meter while the receiver is tuned to an unoccupied channel or during a quiet interval. Allow approximately eight seconds for the initial noise reference. The longer median startup period is more robust than the short v0.5 bootstrap, but it cannot determine the true noise floor if a signal continuously occupies the channel throughout startup. No audio-only estimator can unambiguously separate signal and noise in every arbitrary recording without assumptions or a known noise-only interval.

The SNR display is a smoothed, short-time estimate. Speech naturally varies from syllable to syllable and includes pauses, so individual readings will fluctuate. The supplied WAV's nominal 10 dB is an integrated signal/noise property; it is not an expectation that every frame, or the final frame, reads 10 dB. The WAV test checks that the file passes through the real measurement pipeline, produces detected frames and stays within a broad sanity envelope. It does not claim metrological proof of 10 dB accuracy.

The engine uses a Hann-windowed 4096-point FFT at 16 kHz with 50% overlap. Noise estimation is per-bin median PSD with an exponential-periodogram median correction. VAD remains a heuristic, not speech recognition. The higher threshold is intended to reduce false positives, but may miss weak speech; validate with the actual receiver and microphone before treating displayed SNR as calibrated.

## SNR reference bandwidth

SSB SNR Meter expresses its SNR estimates using a 2,500 Hz reference noise bandwidth, following the same reference-bandwidth convention used by WSJT-X for digital-mode SNR reporting. The measurement methods differ, however, so readings should be treated as indicative rather than directly interchangeable.

