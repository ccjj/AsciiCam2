// Copyright (C) 2012 Brian Nenninger

package com.dozingcatsoftware.asciicam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.View;

/** View which displays the ASCII image computed from the camera preview. */
public class OverlayView extends View {

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    Bitmap bitmap;

    boolean flipHorizontal;
    Matrix flipHorizontalMatrix = new Matrix();
    int backgroundFillColor = Color.argb(255, 0, 0, 0);

    public void setFlipHorizontal(boolean value) {
        this.flipHorizontal = value;
    }

    @Override protected void onDraw(Canvas canvas) {
        // Always draw background to hide camera view
        canvas.drawColor(backgroundFillColor);
        
        // Only draw bitmap if available
        if (bitmap == null) return;
        
        // Scale bitmap to fill entire screen (stretch to fit full vertical mode)
        Matrix scaleMatrix = new Matrix();
        float scaleX = (float)this.getWidth() / bitmap.getWidth();
        float scaleY = (float)this.getHeight() / bitmap.getHeight();
        
        // Use full screen scaling - stretch to fill entire view
        if (flipHorizontal) {
            scaleMatrix.setScale(-scaleX, scaleY);
            scaleMatrix.postTranslate(this.getWidth(), 0);
        } else {
            scaleMatrix.setScale(scaleX, scaleY);
        }
        
        canvas.drawBitmap(bitmap, scaleMatrix, null);
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setBitmap(Bitmap value) {
        this.bitmap = value;
    }

    public void setBackgroundFillColor(int color) {
        this.backgroundFillColor = color;
    }

}
