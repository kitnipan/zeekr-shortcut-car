package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;

/**
 * 左右侧视同时开：一个窗口，中线两边各一路。大小和上下位置跟打转向灯的侧视窗一样，
 * 两块在中线相接，所以一块窗口就盖住原来两块会盖住的地方，中间不多挡导航。
 *
 * <p>画法和 {@link SideViewPopupView} 相同，只是左右各画一次。相机还是那一路附加输出。</p>
 */
public class BothMirrorsView extends ViewGroup {

    private static final String TAG = "BothMirrors";

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final AutoFitTextureView textureView;
    private final Matrix drawMatrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destRect = new RectF();
    private final FisheyeMesh mesh = new FisheyeMesh();
    private final FisheyeMesh.Painter paintTexture = this::drawTextureOnce;

    private WindowManager.LayoutParams params;
    private boolean attached;
    private CompositeStreamGeometry.Plan plan;
    private boolean straighten;
    private int viewFov;
    private int yaw;
    private int pitch;
    private int rollLeft;
    private int rollRight;
    private int lane;
    private int roll;
    private final FisheyeMesh.SourceMap turnedMap = (u, v, out) ->
            SideViewProjection.sourcePoint(u, v, viewFov, yaw, pitch, roll, lane, out, 0);

    public BothMirrorsView(Context context, AppConfig appConfig) {
        super(context);
        this.appConfig = appConfig;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(0xFF000000);
        textureView = new AutoFitTextureView(context);
        addView(textureView);
        readConfig();
    }

    public AutoFitTextureView getTextureView() {
        return textureView;
    }

    public void setSourceSize(android.util.Size size) {
        if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
            return;
        }
        int w = size.getWidth();
        int h = size.getHeight();
        String id = StreamLayoutTable.compositeCameraId();
        if (CompositeStreamGeometry.looksLikeComposite(id, w, h)) {
            plan = CompositeStreamGeometry.analyse(id, w, h);
        } else if (CompositeStreamGeometry.looksLikeCompositeByRatio(w, h)) {
            plan = CompositeStreamGeometry.analyseAsVertical(w, h);
        } else if (id != null) {
            plan = CompositeStreamGeometry.analyseAsVertical(w, h);
        } else {
            return;
        }
        invalidate();
    }

    public boolean hasGeometry() {
        return plan != null && plan.isComposite();
    }

    public boolean isAttached() {
        return attached;
    }

    public void show() {
        readConfig();
        if (attached) {
            place();
            update();
            invalidate();
            return;
        }
        params = new WindowManager.LayoutParams(
                1, 1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        place();
        try {
            windowManager.addView(this, params);
            attached = true;
        } catch (Exception e) {
            AppLog.e(TAG, "左右侧视添加失败", e);
        }
    }

    public void detach() {
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            AppLog.w(TAG, "左右侧视移除失败: " + e);
        }
        attached = false;
    }

    public void applyConfig() {
        readConfig();
        if (attached) {
            place();
            update();
        }
        invalidate();
    }

    private void readConfig() {
        straighten = appConfig.isSidePopupStraighten();
        viewFov = appConfig.getSidePopupFov();
        yaw = appConfig.getSidePopupYaw();
        pitch = appConfig.getSidePopupPitch();
        rollLeft = appConfig.getSidePopupRollForLane(LaneCycle.LEFT);
        rollRight = appConfig.getSidePopupRollForLane(LaneCycle.RIGHT);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return true;
    }

    private void update() {
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Exception e) {
            AppLog.w(TAG, "左右侧视更新失败: " + e);
        }
    }

    /** 两块方窗在屏幕中线相接，跟单侧弹窗同一套边长和上下位置。 */
    private void place() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int size = Math.round(Math.min(h * appConfig.getSidePopupSizePercent() / 100f, w / 2f));
        size = Math.max(AppConfig.REARVIEW_MIN_SIZE, size);
        int center = w / 2;
        params.width = size * 2;
        params.height = size;
        params.x = center - size;
        params.y = Math.round(Math.max(0, h - size) * appConfig.getSidePopupVerticalPercent() / 100f);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        textureView.layout(0, 0, getWidth(), getHeight());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        measureChildren(widthSpec, heightSpec);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!hasGeometry()) {
            super.dispatchDraw(canvas);
            canvas.drawColor(0xFF000000);
            return;
        }
        int pane = width / 2;
        drawPane(canvas, LaneCycle.LEFT, 0, pane, width, height);
        drawPane(canvas, LaneCycle.RIGHT, pane, width - pane, width, height);
    }

    private void drawPane(Canvas canvas, int side, int left, int paneW, int width, int height) {
        lane = side;
        roll = side == LaneCycle.RIGHT ? rollRight : rollLeft;
        int save = canvas.save();
        canvas.clipRect(left, 0, left + paneW, height);
        float cx = left + paneW / 2f;
        float cy = height / 2f;
        canvas.scale(-1f, 1f, cx, cy);
        if (straighten) {
            RearViewGeometry.ShaderRects r = RearViewGeometry.toShaderRects(
                    plan, side, RearViewGeometry.Viewport.full());
            mesh.prepare(FisheyeProjection.MESH_DIVISIONS,
                    r.laneOffsetX * width, r.laneOffsetY * height,
                    r.laneScaleX * width, r.laneScaleY * height, turnedMap);
            mesh.draw(canvas, left, 0f, paneW, height, paintTexture);
        } else {
            if (roll != 0) {
                float rad = (float) Math.toRadians(-roll);
                float fill = Math.abs((float) Math.cos(rad)) + Math.abs((float) Math.sin(rad));
                canvas.rotate(-roll, cx, cy);
                canvas.scale(fill, fill, cx, cy);
            }
            int zoomPercent = Math.round(180f / viewFov * 100f);
            int backPercent = Math.round(yaw / SideViewProjection.MAX_YAW_DEGREES * 100f);
            int upPercent = Math.round(pitch / SideViewProjection.MAX_PITCH_DEGREES * 100f);
            RearViewGeometry.Viewport viewport = SideViewAim.viewport(side, zoomPercent, backPercent, upPercent);
            float[] rect = RearViewGeometry.combinedSourceRect(plan, side, viewport);
            sourceRect.set(rect[0] * width, rect[1] * height,
                    (rect[0] + rect[2]) * width, (rect[1] + rect[3]) * height);
            destRect.set(left, 0, left + paneW, height);
            drawMatrix.setRectToRect(sourceRect, destRect, Matrix.ScaleToFit.FILL);
            canvas.clipRect(destRect);
            canvas.concat(drawMatrix);
            drawChild(canvas, textureView, getDrawingTime());
        }
        canvas.restoreToCount(save);
    }

    private void drawTextureOnce(Canvas canvas) {
        drawChild(canvas, textureView, getDrawingTime());
    }
}
