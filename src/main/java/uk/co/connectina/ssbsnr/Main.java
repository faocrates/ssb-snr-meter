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

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import java.util.Locale;

public class Main extends Application {
    private static final double HISTORY_WINDOW_SECONDS = 30.0;

    private final ComboBox<String> input = new ComboBox<>();
    private final Label snr = new Label("-- dB");
    private final Label noise = new Label("Noise: -- dBFS");
    private final Label signal = new Label("Signal: -- dBFS");
    private final Label status = new Label("Stopped");
    private final Label duration = new Label("Signal duration: 00:00");
    private final Label maximum = new Label("Maximum SNR: -- dB");
    private final Label minimum = new Label("Minimum SNR: -- dB");
    private final Label average = new Label("Average SNR: -- dB");

    private LineChart<Number, Number> history;
    private LineChart<Number, Number> spectrum;
    private final XYChart.Series<Number, Number> historySeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> spectrumSeries = new XYChart.Series<>();
    private AudioWorker worker;
    private NumberAxis historyTimeAxis;
    private long historyFrameCount;
    private long signalStartFrame = -1;
    private long measurementFrames;
    private double snrSum;
    private double minimumSnr = Double.POSITIVE_INFINITY;
    private double maximumSnr = Double.NEGATIVE_INFINITY;

    @Override
    public void start(Stage stage) {
        input.setPrefWidth(430);
        refresh();

        Button refresh = new Button("Refresh");
        Button start = new Button("Start");
        Button stop = new Button("Stop");
        Button reset = new Button("Reset");
        Button about = new Button("About");
        stop.setDisable(true);

        refresh.setOnAction(event -> refresh());
        start.setOnAction(event -> {
            if (input.getValue() != null) {
                resetMeasurementState();
                historyFrameCount = 0;
                historySeries.getData().clear();
                spectrumSeries.getData().clear();
                historyTimeAxis.setLowerBound(0);
                historyTimeAxis.setUpperBound(HISTORY_WINDOW_SECONDS);
                worker = new AudioWorker(input.getValue(), this);
                worker.start();
                start.setDisable(true);
                stop.setDisable(false);
            }
        });
        stop.setOnAction(event -> {
            if (worker != null) worker.shutdown();
            start.setDisable(false);
            stop.setDisable(true);
            status.setText("Stopped");
        });
        reset.setOnAction(event -> {
            resetMeasurementState();
            historyFrameCount = 0;
            historySeries.getData().clear();
            spectrumSeries.getData().clear();
            historyTimeAxis.setLowerBound(0);
            historyTimeAxis.setUpperBound(HISTORY_WINDOW_SECONDS);
            status.setText("Reset — allow approximately 8 seconds to establish the noise reference");
        });
        about.setOnAction(event -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);

            alert.setTitle("About");
            alert.setHeaderText("Connectina SSB SNR Meter v1.0.0");
            alert.setContentText("Copyright © 2026 Dr Christos Bohoris\nLicensed under GNU General Public License v3.0 (GPL-3.0)");

            alert.showAndWait();
        });

        HBox top = new HBox(7, new Label("Audio input:"), input, refresh, start, stop, reset, about);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10));

        snr.setStyle("-fx-font-size:46px;-fx-font-weight:bold;");
        maximum.setStyle("-fx-font-weight:bold;");
        VBox meter = new VBox(8,
                new Label("SSB SNR"), snr, duration, noise, signal, status,
                maximum, minimum, average,
                new Label("Reference bandwidth: 2500 Hz"),
                new Label("Measurement band: 100–2600 Hz"));
        meter.setPadding(new Insets(15));
        meter.setPrefWidth(330);
        meter.setMinWidth(250);

        NumberAxis timeAxis = new NumberAxis(0, HISTORY_WINDOW_SECONDS, 5);
        NumberAxis snrAxis = new NumberAxis(-40, 50, 10);
        timeAxis.setLabel("Time (seconds)");
        timeAxis.setAutoRanging(false);
        historyTimeAxis = timeAxis;
        snrAxis.setLabel("SNR (dB)");
        history = new LineChart<>(timeAxis, snrAxis);
        history.setTitle("SNR history");
        history.setAnimated(false);
        history.setCreateSymbols(false);
        history.setLegendVisible(false);
        history.getData().add(historySeries);

        NumberAxis frequencyAxis = new NumberAxis(0, 4000, 500);
        NumberAxis psdAxis = new NumberAxis(-130, 0, 10);
        frequencyAxis.setLabel("Audio frequency (Hz)");
        psdAxis.setLabel("PSD (dBFS/Hz)");
        spectrum = new LineChart<>(frequencyAxis, psdAxis);
        spectrum.setTitle("Input spectrum");
        spectrum.setAnimated(false);
        spectrum.setCreateSymbols(false);
        spectrum.setLegendVisible(false);
        spectrum.getData().add(spectrumSeries);

        VBox charts = new VBox(5, history, spectrum);
        VBox.setVgrow(history, Priority.ALWAYS);
        VBox.setVgrow(spectrum, Priority.ALWAYS);
        charts.setMinWidth(420);

        SplitPane content = new SplitPane(meter, charts);
        content.setOrientation(Orientation.HORIZONTAL);
        content.setDividerPositions(0.27);
        content.setMinWidth(650);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(content);
        stage.setScene(new Scene(root, 1250, 800));
        stage.setTitle("Connectina SSB SNR Meter");
        stage.show();
    }

    private void resetMeasurementState() {
        signalStartFrame = -1;
        measurementFrames = 0;
        snrSum = 0.0;
        minimumSnr = Double.POSITIVE_INFINITY;
        maximumSnr = Double.NEGATIVE_INFINITY;
        snr.setText("-- dB");
        duration.setText("Signal duration: 00:00");
        minimum.setText("Minimum SNR: -- dB");
        maximum.setText("Maximum SNR: -- dB");
        average.setText("Average SNR: -- dB");
        noise.setText("Noise: -- dBFS");
        signal.setText("Signal: -- dBFS");
    }

    private void refresh() {
        input.getItems().clear();
        for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
            try {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                    if (lineInfo instanceof DataLine.Info) {
                        input.getItems().add(mixerInfo.getName());
                        break;
                    }
                }
            } catch (Exception ignored) {
                // Ignore unavailable audio devices while enumerating inputs.
            }
        }
        if (!input.getItems().isEmpty()) input.getSelectionModel().selectFirst();
    }

    void update(SnrEngine.Result result, int ignored) {
        Platform.runLater(() -> {
            long frame = result.frameNumber();
            boolean detected = result.signalDetected();

            snr.setText(detected ? String.format(Locale.ROOT, "%+.1f dB", result.snr()) : "— dB");
            noise.setText(String.format(Locale.ROOT, "Noise: %.1f dBFS | VAD excess: %.1f dB",
                    result.noiseDb(), result.excessDb()));
            signal.setText(String.format(Locale.ROOT, "Signal: %s | VAD: %s | flatness %.2f",
                    detected ? String.format(Locale.ROOT, "%.1f dBFS", result.signalDb()) : "—",
                    result.voice() ? "VOICE" : "NO VOICE", result.spectralFlatness()));
            status.setText(result.ready() ? ""
                    : "Establishing noise reference (approximately 8 seconds)…");

            updateMeasurementStatistics(result, frame, detected);
            updateHistory(result);

            spectrumSeries.getData().clear();
            double[] spectrumData = result.spectrum();
            for (int bin = 1; bin < spectrumData.length; bin++) {
                double frequency = bin * Dsp.binHz();
                if (frequency <= 4000) {
                    spectrumSeries.getData().add(new XYChart.Data<>(frequency, spectrumData[bin]));
                }
            }
        });
    }

    private void updateMeasurementStatistics(SnrEngine.Result result, long frame, boolean detected) {
        if (!detected) {
            signalStartFrame = -1;
            measurementFrames = 0;
            snrSum = 0.0;
            minimumSnr = Double.POSITIVE_INFINITY;
            maximumSnr = Double.NEGATIVE_INFINITY;
            duration.setText("Signal duration: 00:00");
            maximum.setText("Maximum SNR: -- dB");
            minimum.setText("Minimum SNR: -- dB");
            average.setText("Average SNR: -- dB");
            return;
        }

        if (signalStartFrame < 0) signalStartFrame = frame;
        double value = result.snr();
        measurementFrames++;
        snrSum += value;
        minimumSnr = Math.min(minimumSnr, value);
        maximumSnr = Math.max(maximumSnr, value);

        long elapsedFrames = Math.max(1, frame - signalStartFrame + 1);
        long elapsedSeconds = Math.round(elapsedFrames * Dsp.HOP_SIZE / (double) Dsp.SAMPLE_RATE);
        duration.setText("Signal duration: " + String.format(Locale.ROOT, "%02d:%02d",
                elapsedSeconds / 60, elapsedSeconds % 60));
        maximum.setText(String.format(Locale.ROOT, "Maximum SNR: %+.1f dB", maximumSnr));
        minimum.setText(String.format(Locale.ROOT, "Minimum SNR: %+.1f dB", minimumSnr));
        average.setText(String.format(Locale.ROOT, "Average SNR: %+.1f dB", snrSum / measurementFrames));
    }

    private void updateHistory(SnrEngine.Result result) {
        double elapsedSeconds = historyFrameCount++ * Dsp.HOP_SIZE / (double) Dsp.SAMPLE_RATE;
        double frameSeconds = Dsp.HOP_SIZE / (double) Dsp.SAMPLE_RATE;
        double chartTime = Math.min(elapsedSeconds, HISTORY_WINDOW_SECONDS);
        if (elapsedSeconds > HISTORY_WINDOW_SECONDS) {
            for (XYChart.Data<Number, Number> point : historySeries.getData()) {
                point.setXValue(point.getXValue().doubleValue() - frameSeconds);
            }
        }
        historySeries.getData().add(new XYChart.Data<>(chartTime,
                result.signalDetected() ? result.snr() : Double.NaN));
        while (!historySeries.getData().isEmpty()
                && historySeries.getData().get(0).getXValue().doubleValue() < 0.0) {
            historySeries.getData().remove(0);
        }
    }

    void error(String message) {
        Platform.runLater(() -> status.setText("Error: " + message));
    }

    @Override
    public void stop() {
        if (worker != null) worker.shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
