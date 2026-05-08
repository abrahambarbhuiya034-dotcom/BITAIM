package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * BoardDetector  v3
 *
 * Multi-strategy board + piece detection pipeline:
 *
 * BOARD DETECTION (in order of preference):
 *   1. HSV color threshold on the distinctive carrom-board wooden frame
 *      (reddish-brown mahogany border around the beige/cream playing surface).
 *      Find the largest bounding rect of the frame color → board rect.
 *   2. If the frame area is too small, fall back to inferring from coin spread.
 *   3. Last resort: 95% of frame width, centred.
 *
 * PIECE DETECTION:
 *   - HoughCircles with adaptive param2 (tries 3 thresholds, picks the best)
 *   - Color classification via mean HSV in the inner 40% of each circle
 *   - Striker heuristic: largest white circle in the lower 55% of the board
 *
 * Board size auto-detect feeds directly into the overlay via GameState.board,
 * so the calibration section in the UI is only a fine-tune, not the source of truth.
 */
public class BoardDetector {

    private static final String TAG = "BoardDetector";
    private static final int PROC_WIDTH = 720;

    // Tunables exposed to UI
    private float minRadiusFrac = 0.013f;
    private float maxRadiusFrac = 0.048f;
    private double param2       = 20;

    // Reusable Mats
    private final Mat frame   = new Mat();
    private final Mat small   = new Mat();
    private final Mat gray    = new Mat();
    private final Mat hsv     = new Mat();
    private final Mat circles = new Mat();
    private final Mat mask    = new Mat();
    private final Mat kernel  = new Mat();

    public void setMinRadiusFrac(float v) { minRadiusFrac = Math.max(0.005f, Math.min(v, 0.06f)); }
    public void setMaxRadiusFrac(float v) { maxRadiusFrac = Math.max(0.02f,  Math.min(v, 0.12f)); }
    public void setParam2(double v)       { param2        = Math.max(8,       Math.min(v, 60));    }

    public synchronized GameState detect(Bitmap bitmap) {
        if (bitmap == null) return null;
        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        Utils.bitmapToMat(bitmap, frame);

        float scale = (float) PROC_WIDTH / srcW;
        int procW   = PROC_WIDTH;
        int procH   = Math.round(srcH * scale);
        Imgproc.resize(frame, small, new Size(procW, procH), 0, 0, Imgproc.INTER_AREA);

        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 1.5);
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_RGBA2BGR);
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_BGR2HSV);

        // ── 1. Detect all circles with adaptive threshold ───────────────────
        List<Coin> all = detectCircles(procW, procH, scale, srcW, srcH);

        // ── 2. Classify striker ─────────────────────────────────────────────
        GameState state = new GameState();
        Coin striker = pickStriker(all, srcH);
        if (striker != null) {
            striker.isStriker = true;
            striker.color = Coin.COLOR_STRIKER;
            state.striker = striker;
            for (Coin c : all) {
                if (c != striker) state.coins.add(c);
            }
        } else {
            state.coins.addAll(all);
        }

        // ── 3. Board rect (auto-detect) ──────────────────────────────────────
        RectF boardFromColor = detectBoardFromColor(procW, procH, scale);
        if (boardFromColor != null && boardFromColor.width() > srcW * 0.3f) {
            state.board = boardFromColor;
        } else {
            state.board = inferBoardFromCoins(all, srcW, srcH);
        }

        // ── 4. Place 4 corner pockets ────────────────────────────────────────
        placePockets(state);

        return state;
    }

    // ── Circle detection with multi-threshold retry ─────────────────────────

    private List<Coin> detectCircles(int procW, int procH, float scale, int srcW, int srcH) {
        int minR    = Math.round(procW * minRadiusFrac);
        int maxR    = Math.round(procW * maxRadiusFrac);
        int minDist = (int) (minR * 1.6f);

        // Try 3 param2 thresholds — collect the one with most reasonable circles
        double[] thresholds = { param2, param2 * 0.75, param2 * 1.35 };
        List<Coin> best = new ArrayList<>();

        for (double thresh : thresholds) {
            Mat c = new Mat();
            Imgproc.HoughCircles(gray, c, Imgproc.HOUGH_GRADIENT,
                    1.2, minDist, 90, thresh, minR, maxR);
            List<Coin> found = parseCircles(c, scale, srcW, srcH);
            c.release();
            // Prefer the result that found more plausible coins (4..30 range)
            if (found.size() > best.size() && found.size() <= 32) {
                best = found;
            }
        }
        return best;
    }

    private List<Coin> parseCircles(Mat c, float scale, int srcW, int srcH) {
        List<Coin> list = new ArrayList<>();
        if (c.empty()) return list;
        int n = c.cols();
        for (int i = 0; i < n; i++) {
            double[] d = c.get(0, i);
            if (d == null || d.length < 3) continue;
            float cx = (float) d[0];
            float cy = (float) d[1];
            float cr = (float) d[2];
            int color = classifyColor(hsv, (int) cx, (int) cy, (int) cr);
            if (color < 0) continue;
            list.add(new Coin(cx / scale, cy / scale, cr / scale, color, false));
        }
        return list;
    }

    // ── Striker selection ────────────────────────────────────────────────────

    private Coin pickStriker(List<Coin> all, int srcH) {
        Coin best  = null;
        float bestScore = -1;
        for (Coin c : all) {
            if (c.color != Coin.COLOR_WHITE && c.color != Coin.COLOR_STRIKER) continue;
            // Prefer lower half and larger radius
            float yFrac  = c.pos.y / (float) srcH;
            float score  = c.radius * (0.3f + 0.7f * yFrac);
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    // ── Color classifier ─────────────────────────────────────────────────────

    private int classifyColor(Mat hsv, int x, int y, int r) {
        if (r < 3) return -1;
        int rows = hsv.rows(), cols = hsv.cols();
        if (x - r < 0 || y - r < 0 || x + r >= cols || y + r >= rows) return -1;

        // Sample inner 40% of radius for robust color
        int s = Math.max(2, (int)(r * 0.4f));
        int y0 = Math.max(0, y - s), y1 = Math.min(rows - 1, y + s);
        int x0 = Math.max(0, x - s), x1 = Math.min(cols - 1, x + s);
        if (y1 <= y0 || x1 <= x0) return -1;

        Mat patch = hsv.submat(y0, y1, x0, x1);
        Scalar mean = Core.mean(patch);
        patch.release();

        double h = mean.val[0]; // 0..180
        double sat = mean.val[1]; // 0..255
        double val = mean.val[2]; // 0..255

        // Black coin: very dark
        if (val < 65) return Coin.COLOR_BLACK;

        // White / striker: bright, unsaturated
        if (val > 170 && sat < 80) return Coin.COLOR_WHITE;

        // Queen / red: saturated reddish hue
        if (sat > 85 && (h < 15 || h > 160)) return Coin.COLOR_RED;

        // Reject board surface (brownish/tan, low-mid sat, mid val)
        if (val > 70 && val < 200 && sat < 110 && h > 8 && h < 35) return -1;

        // Reject UI chrome
        return -1;
    }

    // ── Board detection from wooden frame color ───────────────────────────────

    /**
     * Carrom board wooden frames are typically deep reddish-brown (mahogany).
     * HSV range: H ~0-15 or 165-180, S ~80-255, V ~40-160.
     * We threshold that color, find the largest contour, get its bounding rect.
     */
    private RectF detectBoardFromColor(int procW, int procH, float scale) {
        try {
            // Threshold for reddish-brown wooden border
            Mat lower1 = new Mat(), lower2 = new Mat(), combined = new Mat();

            // Hue wraps around: red is near 0 and near 180
            Core.inRange(hsv,
                    new Scalar(0,   70, 40),
                    new Scalar(18, 255, 170),
                    lower1);
            Core.inRange(hsv,
                    new Scalar(160, 70, 40),
                    new Scalar(180, 255, 170),
                    lower2);
            Core.bitwise_or(lower1, lower2, combined);

            // Also include tan/brown secondary hue (some boards are lighter)
            Mat tan = new Mat();
            Core.inRange(hsv, new Scalar(8, 40, 60), new Scalar(28, 140, 180), tan);
            Core.bitwise_or(combined, tan, combined);

            // Morphological close to fill gaps in the frame
            Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15));
            Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_CLOSE, k);
            Imgproc.morphologyEx(combined, combined, Imgproc.MORPH_DILATE, k);

            List<MatOfPoint> contours = new ArrayList<>();
            Mat hier = new Mat();
            Imgproc.findContours(combined, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double maxArea = 0;
            Rect bestRect = null;
            for (MatOfPoint cnt : contours) {
                double area = Imgproc.contourArea(cnt);
                if (area > maxArea) {
                    maxArea = area;
                    bestRect = Imgproc.boundingRect(cnt);
                }
            }

            lower1.release(); lower2.release(); combined.release();
            tan.release(); k.release(); hier.release();

            if (bestRect == null || maxArea < procW * procH * 0.05) return null;

            // Scale back to screen coords
            float sl = bestRect.x / scale;
            float st = bestRect.y / scale;
            float sr = (bestRect.x + bestRect.width) / scale;
            float sb = (bestRect.y + bestRect.height) / scale;

            // Make square (carrom board is square)
            float cx  = (sl + sr) / 2f;
            float cy  = (st + sb) / 2f;
            float side = Math.max(sr - sl, sb - st);
            return new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);

        } catch (Exception e) {
            Log.w(TAG, "Board color detect failed: " + e.getMessage());
            return null;
        }
    }

    // ── Board inference from coin spread ────────────────────────────────────

    private RectF inferBoardFromCoins(List<Coin> all, int w, int h) {
        if (all.size() < 3) {
            // Default: 90% of portrait width, centered at 45% vertical
            float side = w * 0.90f;
            float cx   = w / 2f;
            float cy   = h * 0.45f;
            return clamp(new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f), w, h);
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Coin c : all) {
            if (c.pos.x < minX) minX = c.pos.x;
            if (c.pos.y < minY) minY = c.pos.y;
            if (c.pos.x > maxX) maxX = c.pos.x;
            if (c.pos.y > maxY) maxY = c.pos.y;
        }
        float pad  = Math.max(30f, (maxX - minX) * 0.10f);
        float side = Math.max(maxX - minX, maxY - minY) + pad * 2f;
        float cx   = (minX + maxX) / 2f;
        float cy   = (minY + maxY) / 2f;
        return clamp(new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f), w, h);
    }

    private RectF clamp(RectF r, int w, int h) {
        if (r.left < 0)  r.left = 0;
        if (r.top  < 0)  r.top  = 0;
        if (r.right  > w) r.right  = w;
        if (r.bottom > h) r.bottom = h;
        return r;
    }

    // ── Pocket placement ─────────────────────────────────────────────────────

    private void placePockets(GameState s) {
        float inset = s.board.width() * 0.045f;
        s.pockets.add(new PointF(s.board.left  + inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.top    + inset));
        s.pockets.add(new PointF(s.board.left  + inset, s.board.bottom - inset));
        s.pockets.add(new PointF(s.board.right - inset, s.board.bottom - inset));
    }
}
