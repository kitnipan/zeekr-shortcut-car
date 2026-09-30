package com.kooo.evcam.zeekr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Phone picture of the surround stream: four 16:9 cells, one camera each,
 * straightened with the same projection as the screen. The camera surface
 * stays on the texture view; this only reads a bitmap already copied off it.
 */
public final class RemoteFrame {

    /** One cell. 480×270 is 16:9, so the 2×2 frame is 960×540. */
    public static final int CELL_WIDTH = 480;
    public static final int CELL_HEIGHT = 270;

    private static final int DIVISIONS = 8;

    private final FisheyeMesh mesh = new FisheyeMesh();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float[] window = new float[4];
    private Bitmap frame;

    /**
     * Where a 16:9 cell samples the corrected square. Full width, a shorter
     * vertical span, so the fisheye is not stretched sideways.
     */
    public static void correctedWindow(float cellAspect, float[] out) {
        float aspect = cellAspect > 0.01f ? cellAspect : 1f;
        if (aspect >= 1f) {
            float span = 1f / aspect;
            out[0] = 0f;
            out[1] = (1f - span) / 2f;
            out[2] = 1f;
            out[3] = span;
        } else {
            out[0] = (1f - aspect) / 2f;
            out[1] = 0f;
            out[2] = aspect;
            out[3] = 1f;
        }
    }

    /** Phone source ch1–ch4 → stream lane 0–3. Anything else is the 2×2. */
    public static int laneForSource(String source) {
        if (source == null || source.length() != 3 || !source.startsWith("ch")) {
            return -1;
        }
        int n = source.charAt(2) - '1';
        return n >= 0 && n < CompositeStreamGeometry.LANE_COUNT ? n : -1;
    }

    /**
     * Draw into the reusable frame. {@code lanes} is 16 floats, stream order,
     * each lane {@code u0,v0,u1,v1}. {@code order[cell]} is the stream lane for
     * that cell. {@code onlyLane} 0–3 fills the whole frame with that one camera;
     * anything else is the 2×2. Does not recycle {@code source}.
     */
    public Bitmap compose(Bitmap source, float[] lanes, int[] order, int onlyLane,
                          float fovDegrees, String projection, float strength) {
        int width = CELL_WIDTH * 2;
        int height = CELL_HEIGHT * 2;
        if (frame == null || frame.getWidth() != width || frame.getHeight() != height) {
            if (frame != null) {
                frame.recycle();
            }
            frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(frame);
        canvas.drawColor(Color.BLACK);
        mesh.setCorrection(fovDegrees, projection, strength);
        correctedWindow((float) CELL_WIDTH / CELL_HEIGHT, window);
        int sw = source.getWidth();
        int sh = source.getHeight();
        FisheyeMesh.Painter painter = c -> c.drawBitmap(source, 0f, 0f, paint);
        if (onlyLane >= 0 && onlyLane < CompositeStreamGeometry.LANE_COUNT) {
            drawLane(canvas, lanes, onlyLane, sw, sh, 0f, 0f, width, height, 12, painter);
            return frame;
        }
        for (int cell = 0; cell < CompositeStreamGeometry.LANE_COUNT; cell++) {
            int lane = order[cell];
            float dx = (cell % 2) * CELL_WIDTH;
            float dy = (cell / 2) * CELL_HEIGHT;
            drawLane(canvas, lanes, lane, sw, sh, dx, dy, CELL_WIDTH, CELL_HEIGHT, DIVISIONS, painter);
        }
        return frame;
    }

    private void drawLane(Canvas canvas, float[] lanes, int lane, int sw, int sh,
                          float dx, float dy, float dw, float dh, int divisions,
                          FisheyeMesh.Painter painter) {
        if (lane < 0 || lane >= CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        int at = lane * 4;
        float left = lanes[at] * sw;
        float top = lanes[at + 1] * sh;
        float laneWidth = (lanes[at + 2] - lanes[at]) * sw;
        float laneHeight = (lanes[at + 3] - lanes[at + 1]) * sh;
        if (laneWidth < 2f || laneHeight < 2f) {
            return;
        }
        mesh.prepare(divisions, left, top, laneWidth, laneHeight,
                window[0], window[1], window[2], window[3]);
        mesh.draw(canvas, dx, dy, dw, dh, painter);
    }
}
