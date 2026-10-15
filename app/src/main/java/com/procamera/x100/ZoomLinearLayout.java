package com.procamera.x100;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.LinearLayout;

public class ZoomLinearLayout extends LinearLayout implements ScaleGestureDetector.OnScaleGestureListener {

    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 100.0f;

    private ScaleGestureDetector scaleDetector;
    private float scale = 1.0f;
    private float lastScaleFactor = 0f;

    public ZoomLinearLayout(Context context) {
        super(context);
        init(context);
    }

    public ZoomLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ZoomLinearLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        scaleDetector = new ScaleGestureDetector(context, this);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        float scaleFactor = detector.getScaleFactor();
        if (lastScaleFactor == 0 || (Math.signum(scaleFactor) == Math.signum(lastScaleFactor))) {
            scale *= scaleFactor;
            scale = Math.max(MIN_ZOOM, Math.min(scale, MAX_ZOOM));
            lastScaleFactor = scaleFactor;
        } else {
            lastScaleFactor = 0;
        }
        return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float newScale) {
        this.scale = Math.max(MIN_ZOOM, Math.min(newScale, MAX_ZOOM));
        invalidate();
    }
}
