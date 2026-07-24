package dev.jaimin.auraorbit.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

public class InterceptingFrameLayout extends FrameLayout {
    public InterceptingFrameLayout(Context context) {
        super(context);
    }

    public InterceptingFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public InterceptingFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true; // Intercept all touches so they are processed by the container's touch listener
    }
}
