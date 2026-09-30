package com.kooo.evcam.share;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import android.view.Surface;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.zeekr.CompositeStreamGeometry;
import com.kooo.evcam.zeekr.FisheyeGlPipe;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把环视录像拉直后再交给 U 盘。
 *
 * <p>盘上那份是鱼眼原片，屏幕上的校正只是看着。导出要的是拔下来就能播的直画面，
 * 所以这里解码 → {@link FisheyeGlPipe} 逐像素校正 → 再编码。座舱不是鱼眼，不走这里。</p>
 *
 * <p>校正参数用设置里那一套（投影、视野、强度）。屏幕上的拉直开关关着时不走这里，
 * 导出的就是录像原片。</p>
 */
public final class SurroundDefish {

    private static final String TAG = "SurroundDefish";
    private static final String AVC = "video/avc";

    private SurroundDefish() {
    }

    /** 文件名是环视的 mp4（含旧名 {@code _front}）。 */
    public static boolean isSurroundVideo(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.US);
        if (!lower.endsWith(".mp4")) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        int underscore = base.lastIndexOf('_');
        if (underscore < 0 || underscore >= base.length() - 1) {
            return false;
        }
        String suffix = base.substring(underscore + 1);
        return CameraSlots.SURROUND.equals(CameraSlots.canonical(suffix));
    }

    /** 环视视频，而且屏幕上的拉直开关开着。关着就按原文件导出。 */
    public static boolean wanted(Context context, File file) {
        return file != null
                && isSurroundVideo(file.getName())
                && new AppConfig(context).isFisheyeCorrection();
    }

    /**
     * 把 {@code source} 拉直写到 {@code dest}。失败时删掉半成品。
     */
    public static void write(Context context, File source, File dest) throws IOException {
        write(context, source, dest, null);
    }

    /**
     * 同 {@link #write(Context, File, File)}。{@code cancel} 变成 true 时停在下一帧，删掉半成品。
     */
    public static void write(Context context, File source, File dest, AtomicBoolean cancel)
            throws IOException {
        if (dest.exists() && !dest.delete()) {
            throw new IOException("replace " + dest.getAbsolutePath());
        }
        AppConfig config = new AppConfig(context);
        try {
            transcode(source, dest,
                    config.getFisheyeFov(),
                    config.getFisheyeProjection(),
                    config.getFisheyeStrength() / 100f,
                    cancel);
        } catch (IOException e) {
            if (dest.exists() && !dest.delete()) {
                AppLog.w(TAG, "删不掉半成品: " + dest.getAbsolutePath());
            }
            throw e;
        }
    }

    private static void transcode(File source, File dest,
                                  float fov, String projection, float strength,
                                  AtomicBoolean cancel) throws IOException {
        MediaExtractor videoEx = new MediaExtractor();
        MediaExtractor audioEx = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FisheyeGlPipe pipe = null;
        Surface decoderSurface = null;
        Surface encoderSurface = null;
        try {
            videoEx.setDataSource(source.getAbsolutePath());
            audioEx.setDataSource(source.getAbsolutePath());
            int videoIndex = track(videoEx, "video/");
            if (videoIndex < 0) {
                throw new IOException("no video");
            }
            videoEx.selectTrack(videoIndex);
            MediaFormat inFormat = videoEx.getTrackFormat(videoIndex);
            int width = inFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if ((width & 1) != 0) {
                width--;
            }
            if ((height & 1) != 0) {
                height--;
            }
            int fps = inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            if (fps <= 0) {
                fps = 30;
            }
            int bitrate = inFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? inFormat.getInteger(MediaFormat.KEY_BIT_RATE)
                    : Math.max(8_000_000, width * height * 2);
            long durationUs = inFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inFormat.getLong(MediaFormat.KEY_DURATION) : 0L;

            int audioIndex = track(audioEx, "audio/");
            MediaFormat audioFormat = audioIndex >= 0 ? audioEx.getTrackFormat(audioIndex) : null;
            if (audioIndex >= 0) {
                audioEx.selectTrack(audioIndex);
            }

            encoder = MediaCodec.createEncoderByType(AVC);
            MediaFormat outFormat = MediaFormat.createVideoFormat(AVC, width, height);
            outFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            outFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            outFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            pipe = FisheyeGlPipe.start("usb", encoderSurface, width, height, lanesFor(width, height));
            if (pipe == null) {
                throw new IOException("defish pipe");
            }
            pipe.setCorrection(true, fov, projection, strength);
            decoderSurface = pipe.newInputSurface();
            if (decoderSurface == null) {
                throw new IOException("defish input");
            }

            String mime = inFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime != null ? mime : AVC);
            decoder.configure(inFormat, decoderSurface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(dest.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            run(videoEx, audioEx, decoder, encoder, muxer, pipe, audioFormat, durationUs, cancel);
            AppLog.i(TAG, "环视已拉直: " + dest.getName() + " " + width + "x" + height);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? "defish" : e.getMessage(), e);
        } finally {
            release(decoder);
            if (decoderSurface != null) {
                decoderSurface.release();
            }
            if (pipe != null) {
                pipe.release();
            }
            release(encoder);
            if (encoderSurface != null) {
                encoderSurface.release();
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // stop 失败时 release 还会再抛一次
                }
            }
            videoEx.release();
            audioEx.release();
        }
    }

    private static void run(MediaExtractor videoEx, MediaExtractor audioEx,
                            MediaCodec decoder, MediaCodec encoder, MediaMuxer muxer,
                            FisheyeGlPipe pipe, MediaFormat audioFormat, long durationUs,
                            AtomicBoolean cancel)
            throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inEos = false;
        boolean decEos = false;
        boolean signaled = false;
        boolean encEos = false;
        boolean muxerStarted = false;
        int rendered = 0;
        int videoTrack = -1;
        int audioTrack = -1;
        long budgetMs = Math.max(120_000L, durationUs / 1000L * 4L + 30_000L);
        long deadline = SystemClock.elapsedRealtime() + budgetMs;

        while (!encEos) {
            if (cancel != null && cancel.get()) {
                throw new IOException("cancelled");
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new IOException("timeout");
            }
            if (!inEos) {
                int in = decoder.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    int n = buf == null ? -1 : videoEx.readSampleData(buf, 0);
                    if (n < 0) {
                        decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        decoder.queueInputBuffer(in, 0, n, Math.max(0L, videoEx.getSampleTime()), 0);
                        videoEx.advance();
                    }
                }
            }
            if (!decEos) {
                int out = decoder.dequeueOutputBuffer(info, 10_000);
                if (out >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean render = info.size > 0;
                    decoder.releaseOutputBuffer(out, render);
                    if (render) {
                        rendered++;
                    }
                    if (eos) {
                        decEos = true;
                    }
                }
            }
            if (decEos && !signaled && pipe.drawn() >= rendered) {
                encoder.signalEndOfInputStream();
                signaled = true;
            }
            int enc = encoder.dequeueOutputBuffer(info, 10_000);
            if (enc == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IOException("format twice");
                }
                videoTrack = muxer.addTrack(encoder.getOutputFormat());
                if (audioFormat != null) {
                    audioTrack = muxer.addTrack(audioFormat);
                }
                muxer.start();
                muxerStarted = true;
            } else if (enc >= 0) {
                if (!muxerStarted) {
                    throw new IOException("sample before format");
                }
                ByteBuffer buf = encoder.getOutputBuffer(enc);
                if (buf != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    muxer.writeSampleData(videoTrack, buf, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(enc, false);
                if (eos) {
                    encEos = true;
                }
            }
        }
        if (rendered == 0) {
            throw new IOException("no frames");
        }
        if (audioTrack >= 0) {
            copyAudio(audioEx, muxer, audioTrack);
        }
        muxer.stop();
    }

    private static void copyAudio(MediaExtractor audioEx, MediaMuxer muxer, int audioTrack) {
        ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int n = audioEx.readSampleData(buf, 0);
            if (n < 0) {
                return;
            }
            info.offset = 0;
            info.size = n;
            info.presentationTimeUs = Math.max(0L, audioEx.getSampleTime());
            info.flags = audioEx.getSampleFlags();
            muxer.writeSampleData(audioTrack, buf, info);
            audioEx.advance();
        }
    }

    /** 录像落盘是 2×2。万一还是合成流长条，按长条的四格来。 */
    static float[] lanesFor(int width, int height) {
        if (!CompositeStreamGeometry.looksLikeCompositeByRatio(width, height)) {
            return FisheyeGlPipe.GRID_2X2;
        }
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(null, width, height);
        float[] lanes = new float[plan.lanes.length * 4];
        for (int i = 0; i < plan.lanes.length; i++) {
            CompositeStreamGeometry.Lane lane = plan.lanes[i];
            lanes[i * 4] = lane.u0;
            lanes[i * 4 + 1] = lane.v0;
            lanes[i * 4 + 2] = lane.u1 - lane.u0;
            lanes[i * 4 + 3] = lane.v1 - lane.v0;
        }
        return lanes;
    }

    private static int track(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void release(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
            // 没 start 成功时 stop 会抛
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // 已经放掉了
        }
    }
}
