package com.dozingcatsoftware.asciicam;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Creates Bitmaps and HTML from AsciiConverter.Result objects.
 */
public class AsciiRenderer {

    private static final boolean DEBUG = true;

    Paint paint = new Paint();

    int charPixelHeight = 9;
    int charPixelWidth = 7;
    int textSize = 10;
    int characterSizePercent = 100;

    // One element of this array holds the visible bitmap. The next image is drawn offscreen into
    // the other element, and then activeBitmapIndex is flipped to make it visible.
    Bitmap[] bitmaps = new Bitmap[2];
    int activeBitmapIndex;

    // When rendering an ASCII image, we draw color values directly into an int array a row at a
    // time. We use a bitmap containing the possible characters and copy slices from it. This is
    // faster than Canvas.drawText.
    // We store some temporary objects used to fill the bitmap pixels between requests,
    // so we can avoid allocating new objects when possible. See drawIntoBitmap().
    Bitmap possibleCharsBitmap;
    int[] possibleCharsBitmapPixels;
    byte[] possibleCharsGrayscale;

    class Worker implements Callable<Long> {
        int startRow, endRow;
        int charPixelWidth, charPixelHeight;
        AsciiConverter.Result result;
        byte[] possibleCharsGrayscale;
        int backgroundColor;
        Bitmap outputBitmap;

        int[] rowAsciiValues;
        int[] rowColorValues;
        int[] renderedRowPixels;

        void init(int workerId, int numWorkers,
                AsciiConverter.Result result,
                int charPixelWidth, int charPixelHeight, byte[] possibleCharsGrayscale, int bgColor,
                Bitmap outputBitmap) {
            this.startRow = result.rows * workerId / numWorkers;
            this.endRow = result.rows * (workerId + 1) / numWorkers;
            this.charPixelWidth = charPixelWidth;
            this.charPixelHeight = charPixelHeight;
            this.result = result;
            this.possibleCharsGrayscale = possibleCharsGrayscale;
            this.backgroundColor = bgColor;
            this.outputBitmap = outputBitmap;

            int pixelArraySize = charPixelWidth * charPixelHeight * result.columns;
            if (renderedRowPixels == null || renderedRowPixels.length != pixelArraySize) {
                renderedRowPixels = new int[pixelArraySize];
            }
            if (rowAsciiValues == null || rowAsciiValues.length != result.columns) {
                rowAsciiValues = new int[result.columns];
            }
            if (rowColorValues == null || rowColorValues.length != result.columns) {
                rowColorValues = new int[result.columns];
            }
        }

        // Returns time in nanoseconds to execute.
        @Override public Long call() throws Exception {
            long t1 = System.nanoTime();

            int pixelsPerRow = charPixelWidth * result.columns;
            for (int row=startRow; row<endRow; row++) {
                for (int col=0; col<result.columns; col++) {
                    rowAsciiValues[col] = result.asciiIndexAtRowColumn(row, col);
                    rowColorValues[col] = result.colorAtRowColumn(row, col);
                }

                if (nativeCodeAvailable) {
                    fillPixelsInRowNative(renderedRowPixels, renderedRowPixels.length,
                            rowAsciiValues, rowColorValues, rowAsciiValues.length,
                            possibleCharsGrayscale, backgroundColor,
                            charPixelWidth, charPixelHeight, result.columns);
                }
                else {
                    fillPixelsInRow(renderedRowPixels, renderedRowPixels.length,
                            rowAsciiValues, rowColorValues, rowAsciiValues.length,
                            possibleCharsGrayscale, backgroundColor,
                            charPixelWidth, charPixelHeight, result.columns);
                }
                int y = charPixelHeight * row;
                // setPixels is not threadsafe; without synchronization some devices end up with
                // slightly garbled images.
                synchronized (outputBitmap) {
                    outputBitmap.setPixels(renderedRowPixels, 0, pixelsPerRow, 0, y, pixelsPerRow, charPixelHeight);
                }
            }
            return System.nanoTime() - t1;
        }
    }

    ExecutorService threadPool;
    List<Worker> renderWorkers;

    static boolean nativeCodeAvailable = false;
    static {
        try {
            System.loadLibrary("asciiart");
            nativeCodeAvailable = true;
        }
        catch(Throwable ignored) {}
    }

    int maxWidth;
    int maxHeight;
    int outputImageWidth;
    int outputImageHeight;

    public Bitmap getVisibleBitmap() {
        return bitmaps[activeBitmapIndex];
    }

    public int getCharPixelHeight() {
        return charPixelHeight;
    }

    public int getCharPixelWidth() {
        return charPixelWidth;
    }

    public void setMaximumImageSize(int maxWidth, int maxHeight) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
    }

    public void setCharacterSizePercent(int percent) {
        characterSizePercent = Math.max(50, Math.min(200, percent));
    }

    public void setCameraImageSize(int width, int height) {
        // Always use full screen width for better centering
        // This ensures the ASCII art spans the entire width of the screen
        this.outputImageWidth = this.maxWidth;
        this.outputImageHeight = this.maxHeight;
        
        // Get screen density for adaptive scaling
        android.util.DisplayMetrics metrics = android.content.res.Resources.getSystem().getDisplayMetrics();
        float density = metrics.density;
        int densityDpi = metrics.densityDpi;
        
        // Calculate density-aware text size for different screen sizes
        // Adjust target columns based on screen density and size
        int baseTargetColumns;
        if (densityDpi <= 160) { // LDPI
            baseTargetColumns = 25;
        } else if (densityDpi <= 240) { // MDPI
            baseTargetColumns = 30;
        } else if (densityDpi <= 320) { // HDPI
            baseTargetColumns = 35;
        } else if (densityDpi <= 480) { // XHDPI
            baseTargetColumns = 40;
        } else if (densityDpi <= 640) { // XXHDPI
            baseTargetColumns = 45;
        } else { // XXXHDPI and above
            baseTargetColumns = 50;
        }
        
        // Adjust for screen aspect ratio - wider screens can fit more columns
        float aspectRatio = (float) outputImageWidth / outputImageHeight;
        if (aspectRatio > 1.8f) { // Very wide screens (18:9 or wider)
            baseTargetColumns = (int) (baseTargetColumns * 1.1f);
        } else if (aspectRatio < 1.5f) { // Square-ish screens
            baseTargetColumns = (int) (baseTargetColumns * 0.9f);
        }
        
        // Ensure reasonable bounds
        int targetColumns = Math.min(60, Math.max(25, baseTargetColumns));
        
        // Calculate text size with density scaling, then apply the user preference.
        int baseTextSize = outputImageWidth / targetColumns;
        int minimumTextSize = (int)(16 * density);
        textSize = Math.max(minimumTextSize, baseTextSize);
        textSize = Math.max(1, textSize * characterSizePercent / 100);
        
        // Adjust character dimensions for better readability
        charPixelWidth = Math.max(1, (int) (textSize * 0.65));
        charPixelHeight = Math.max(1, (int) (textSize * 1.1));
        
        android.util.Log.d("AsciiRenderer", "Screen: " + outputImageWidth + "x" + outputImageHeight + 
                ", density: " + density + " (" + densityDpi + "dpi), aspectRatio: " + String.format("%.2f", aspectRatio) +
                ", textSize: " + textSize + ", charSize: " + charPixelWidth + "x" + charPixelHeight + 
                ", targetColumns: " + targetColumns + ", characterSizePercent: " + characterSizePercent);
    }

    public int getOutputImageWidth() {
        return this.outputImageWidth;
    }
    public int getOutputImageHeight() {
        return this.outputImageHeight;
    }

    public int asciiColumnsForWidth(int width) {
        return width / getCharPixelWidth();
    }
    public int asciiRowsForHeight(int height) {
        return height / getCharPixelHeight();
    }

    public int asciiRows() {
        return asciiRowsForHeight(this.outputImageHeight);
    }
    public int asciiColumns() {
        return asciiColumnsForWidth(this.outputImageWidth);
    }

    void initRenderThreadPool(int numThreads) {
        if (threadPool!=null) {
            threadPool.shutdown();
        }
        if (numThreads<=0) numThreads = Runtime.getRuntime().availableProcessors();
        threadPool = Executors.newFixedThreadPool(numThreads);
        renderWorkers = new ArrayList<Worker>();
        for(int i=0; i<numThreads; i++) {
            renderWorkers.add(new Worker());
        }
    }

    public void destroyThreadPool() {
        if (threadPool!=null) {
            threadPool.shutdown();
            threadPool = null;
        }
    }

    private void drawIntoBitmap(AsciiConverter.Result result, Bitmap bitmap) {
        paint.setARGB(255, 255, 255, 255);

        long t1 = System.nanoTime();
        // Directly drawing characters into the bitmap takes ~210ms on a Nexus 5x, and worse on a
        // Nexus 7. This results in a very choppy display.
        /*
        Canvas canvas = new Canvas(bitmap);
        canvas.drawARGB(255, 0, 0, 0);
        paint.setTextSize(textSize);
        if (result!=null) {
            for(int r=0; r<result.rows; r++) {
                int y = charPixelHeight * (r+1);  // Because drawText uses baseline. Should be -1?
                int x = 0;
                for(int c=0; c<result.columns; c++) {
                    String s = result.stringAtRowColumn(r, c);
                    paint.setColor(result.colorAtRowColumn(r, c));
                    canvas.drawText(s, x, y, paint);
                    x += charPixelWidth;
                }
            }
        }
        */

        // Instead, we directly generate the pixels a row of text at a time. We create a "template"
        // bitmap into which we draw one copy of each character that we might need, and convert
        // that to a flattened grayscale array. (Currently we only care whether the pixel has a
        // nonzero brightness, so no anti-aliasing support). Then for each character we want to
        // draw to the output image, we copy the corresponding pixels from the template bitmap.
        // (Setting the output image pixel to the color determined by AsciiCoverter if nonblack).
        //
        // This isn't much faster in Java (190ms on a Nexus 5x), but when implemented in C with
        // JNI, it drops to 55ms for an almost 4x performance increase on a single thread.
        // With 6 threads (as reported by Runtime.getAvailableProcessors), it's 20-25ms.

        // Create a bitmap containing each character that we might need to render. We could try to
        // skip this step if (as is usually the case) the characters are the same as the previous
        // frame, but in practice there's only a few characters and it takes almost no time.
        int pixelsPerRow = charPixelWidth * result.columns;
        if (possibleCharsBitmap == null ||
                possibleCharsBitmap.getWidth() != pixelsPerRow ||
                possibleCharsBitmap.getHeight() != charPixelHeight) {
            possibleCharsBitmap = Bitmap.createBitmap(pixelsPerRow, charPixelHeight, Bitmap.Config.ARGB_8888);
        }
        Canvas charsBitmapCanvas = new Canvas(possibleCharsBitmap);
        charsBitmapCanvas.drawARGB(255, 0, 0, 0);
        paint.setTextSize(textSize);
        paint.setColor(0xffffffff);
        for (int i=0; i<result.pixelChars.length; i++) {
            charsBitmapCanvas.drawText(result.pixelChars[i], charPixelWidth*i, charPixelHeight, paint);
        }

        // Extract brightness bytes from the bitmap and flatten to a 1d array.
        int numCharsBitmapPixels = possibleCharsBitmap.getWidth() * possibleCharsBitmap.getHeight();
        if (possibleCharsBitmapPixels == null ||
                possibleCharsBitmapPixels.length != numCharsBitmapPixels) {
            possibleCharsBitmapPixels = new int[numCharsBitmapPixels];
            possibleCharsGrayscale = new byte[numCharsBitmapPixels];
        }

        possibleCharsBitmap.getPixels(possibleCharsBitmapPixels,
                0, possibleCharsBitmap.getWidth(), 0, 0,
                possibleCharsBitmap.getWidth(), possibleCharsBitmap.getHeight());
        for (int i=0; i<possibleCharsBitmapPixels.length; i++) {
            // Each RGB component should be equal; take the blue.
            possibleCharsGrayscale[i] = (byte) (possibleCharsBitmapPixels[i] & 0xff);
        }

        // Create workers if needed, and assign them a subset of the rows to render.
        if (threadPool == null) {
            initRenderThreadPool(0);
        }
        int numWorkers = renderWorkers.size();
        for (int i=0; i<numWorkers; i++) {
            renderWorkers.get(i).init(i, numWorkers, result, charPixelWidth, charPixelHeight,
                    possibleCharsGrayscale, result.backgroundColor(), bitmap);
        }

        try {
            threadPool.invokeAll(renderWorkers);
        }
        catch (InterruptedException ex) {
            android.util.Log.e("AsciiRenderer", "Interrupted", ex);
        }
        bitmap.prepareToDraw();

        if (DEBUG) {
            long t2 = System.nanoTime();
            long millis = (long)((t2-t1) / 1e6);
            int numThreads = (renderWorkers != null) ? renderWorkers.size() : 1;
            android.util.Log.e("AC", "Created output bitmap in " + millis + "ms using " + numThreads + " threads");
        }
    }

    private void fillPixelsInRow(int[] rowPixels, int numRowPixels,
            int[] asciiValues, int[] colorValues, int numValues,
            byte[] charsBitmap, int backgroundColor, int charWidth, int charHeight, int numChars) {
        int offset = 0;
        int pixelsPerRow = numValues * charWidth;
        
        if (DEBUG && charsBitmap != null) {
            android.util.Log.d("AsciiRenderer", "fillPixelsInRow - charsBitmap.length: " + charsBitmap.length + 
                    ", pixelsPerRow: " + pixelsPerRow + ", charWidth: " + charWidth + ", charHeight: " + charHeight + 
                    ", numChars: " + numChars + ", numValues: " + numValues);
        }
        
        // For each row of pixels:
        for (int y=0; y<charHeight; y++) {
            // For each character to draw:
            for (int charPosition=0; charPosition<numChars; charPosition++) {
                int charValue = asciiValues[charPosition];
                int charColor = colorValues[charPosition];
                
                // Bounds check for charValue
                if (charValue < 0 || charValue >= numValues) {
                    if (DEBUG) {
                        android.util.Log.w("AsciiRenderer", "Invalid charValue: " + charValue + ", numValues: " + numValues);
                    }
                    charValue = Math.max(0, Math.min(charValue, numValues - 1));
                }
                
                // Index into the chars bitmap, going "down" the number of rows,
                // and "across" the amount of character widths given by the index.
                int charBitmapOffset = y*pixelsPerRow + charValue*charWidth;
                
                // Bounds check for charBitmapOffset
                if (charBitmapOffset + charWidth > charsBitmap.length) {
                    if (DEBUG) {
                        android.util.Log.w("AsciiRenderer", "charBitmapOffset out of bounds: " + charBitmapOffset + 
                                " + " + charWidth + " > " + charsBitmap.length);
                    }
                    break;
                }
                
                for (int i=0; i<charWidth; i++) {
                    byte bitmapValue = charsBitmap[charBitmapOffset++];
                    rowPixels[offset++] = (bitmapValue!=0) ? charColor : backgroundColor;
                }
            }
        }
    }

    // Implemented in asciiart.c, almost identical to the above Java implementation.
    private native void fillPixelsInRowNative(int[] pixels, int numPixels,
            int[] asciiValues, int[] colorValues, int numValues,
            byte[] charsBitmap, int backgroundColor, int charWidth, int charHeight, int numChars);

    public Bitmap createBitmap(AsciiConverter.Result result) {
        if (DEBUG) {
            android.util.Log.d("AsciiRenderer", "createBitmap called - result: " + (result != null) + 
                    ", outputImageWidth: " + outputImageWidth + ", outputImageHeight: " + outputImageHeight);
            if (result != null) {
                android.util.Log.d("AsciiRenderer", "result - rows: " + result.rows + ", columns: " + result.columns + 
                        ", pixelChars: " + (result.pixelChars != null ? result.pixelChars.length : "null"));
            }
        }
        
        int nextIndex = (activeBitmapIndex + 1) % bitmaps.length;
        if (bitmaps[nextIndex]==null ||
                bitmaps[nextIndex].getWidth()!=outputImageWidth ||
                bitmaps[nextIndex].getHeight()!=outputImageHeight) {
            bitmaps[nextIndex] = Bitmap.createBitmap(outputImageWidth, outputImageHeight, Bitmap.Config.ARGB_8888);
            if (DEBUG) {
                android.util.Log.d("AsciiRenderer", "Created new bitmap: " + outputImageWidth + "x" + outputImageHeight);
            }
        }
        
        if (result == null) {
            if (DEBUG) {
                android.util.Log.w("AsciiRenderer", "Result is null, returning empty bitmap");
            }
            return bitmaps[nextIndex];
        }
        
        drawIntoBitmap(result, bitmaps[nextIndex]);
        activeBitmapIndex = nextIndex;
        
        if (DEBUG) {
            android.util.Log.d("AsciiRenderer", "Bitmap created successfully, activeBitmapIndex: " + activeBitmapIndex);
        }
        
        return bitmaps[activeBitmapIndex];
    }

    // For thumbnails, create image one-fourth normal size, use every other row and column, and draw solid rectangles
    // instead of text because text won't scale down well for gallery view.
    public Bitmap createThumbnailBitmap(AsciiConverter.Result result) {
        int width = outputImageWidth / 4;
        int height = outputImageHeight / 4;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setARGB(255, 255, 255, 255);

        canvas.drawARGB(255, 0, 0, 0);
        if (result!=null) {
            for(int r=0; r<result.rows; r+=2) {
                int ymin = height*r / result.rows;
                int ymax = height*(r+2) / result.rows;
                for(int c=0; c<result.columns; c+=2) {
                    int xmin = width*c / result.columns;
                    int xmax = width*(c+2) / result.columns;
                    float ratio = result.brightnessRatioAtRowColumn(r, c);
                    paint.setColor(result.colorAtRowColumn(r, c));
                    // for full color, always draw larger rectangle because colors will be darker
                    if (result.getColorType()==AsciiConverter.ColorType.FULL_COLOR || ratio > 0.5) {
                        canvas.drawRect(xmin, ymin, xmax, ymax, paint);
                    }
                    else {
                        int x = (xmin + xmax) / 2 - 1;
                        int y = (ymin + ymax) / 2 - 1;
                        canvas.drawRect(x, y, x+2, y+2, paint);
                    }
                }
            }
        }
        return bitmap;
    }
}
