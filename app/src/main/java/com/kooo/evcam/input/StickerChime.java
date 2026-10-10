package com.kooo.evcam.input;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Two soft notes when a sticker connects. Short, so the cabin audio is barely touched. */
final class StickerChime {

    private static final int RATE = 22050;

    private StickerChime() {
    }

    static void play() {
        short[] samples = notes();
        AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(samples.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(samples, 0, samples.length);
            track.setNotificationMarkerPosition(samples.length);
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override
                public void onMarkerReached(AudioTrack done) {
                    done.release();
                }

                @Override
                public void onPeriodicNotification(AudioTrack done) {
                }
            });
            track.play();
        } catch (RuntimeException ignored) {
            // no audio route
        }
    }

    private static short[] notes() {
        int gap = (int) (RATE * 0.04f);
        short[] first = tone(523.25, 0.12f);
        short[] second = tone(659.25, 0.20f);
        short[] all = new short[first.length + gap + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length + gap, second.length);
        return all;
    }

    private static short[] tone(double hz, float seconds) {
        int count = (int) (RATE * seconds);
        short[] samples = new short[count];
        for (int i = 0; i < count; i++) {
            float edge = Math.min(i, count - 1 - i) / (RATE * 0.02f);
            float env = Math.min(1f, Math.max(0f, edge));
            double wave = Math.sin(2 * Math.PI * hz * i / RATE);
            samples[i] = (short) (wave * env * 0.22 * Short.MAX_VALUE);
        }
        return samples;
    }
}
