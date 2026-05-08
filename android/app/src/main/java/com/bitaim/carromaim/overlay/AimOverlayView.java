package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView  v3.1
 *
 * All v3 features plus:
 *
 *  SNAP-TO-COIN MODE (new):
 *    When enabled, tapping near any detected coin automatically computes the
 *    perfect aim angle to pocket that coin. The app calculates:
 *      1. Which pocket gives the most direct path from the coin.
 *      2. The exact "contact point" the striker must reach to push the coin
 *         straight toward that pocket.
 *      3. Sets targetPos on the aim line from striker through that contact point.
 *    A bright green dotted line connects coin → best pocket for confirmation.
 *    Tapping empty board space still sets a normal aim target (fallback).
 *
 *  STRIKER MOVEABLE (v3):
 *    Drag the gold-ringed striker to reposition manually.
 *
 *  SINGLE LINE:
 *    One cyan aim line from striker + one orange coin deflection line.
 */
public class AimOverlayView extends View {

    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";
    public static final String MODE_ALL    = "ALL";

    private final TrajectorySimulator simulator = new TrajectorySimulator();

    private String  shotMode        = MODE_ALL;
    private PointF  targetPos;
    private PointF  manualStriker;
    private boolean draggingStriker = false;
    private boolean strikerMoveable = true;
    private boolean snapMode        = false;   // NEW: snap-to-coin

    // Snap state
    private Coin   snapCoin;       // coin that was snapped to
    private PointF snapPocket;     // best pocket for that coin

    private GameState detected;
    private float marginOffsetX = 0f, marginOffsetY = 0f;
    private float sensitivity   = 1.0f;
    private final float dp;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint aimLinePaint;
    private final Paint coinPathPaint;
    private final Paint snapLinePaint;       // dotted lime — coin → pocket
    private final Paint snapCoinRingPaint;   // ring around snapped coin
    private final Paint pocketGlowPaint;
    private final Paint strikerFillPaint, strikerRingPaint;
    private final Paint coinOutlinePaint;
    private final Paint blackFillPaint, whiteFillPaint, redFillPaint;
    private final Paint pocketFillPaint;
    private final Paint targetCrossPaint;
    private final Paint textPaint;
    private final Paint boardOutlinePaint;
    private final Paint dragHintPaint;

    public AimOverlayView(Context context) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;

        aimLinePaint      = stroke(Color.parseColor("#00E5FF"), 3.8f);

        coinPathPaint     = stroke(Color.parseColor("#FF8A00"), 3.0f);

        // Snap: bright lime dashes from coin to pocket
        snapLinePaint     = stroke(Color.parseColor("#22FF6E"), 2.6f);
        snapLinePaint.setPathEffect(new DashPathEffect(new float[]{10*dp, 6*dp}, 0));

        // Snap: thick lime ring around the targeted coin
        snapCoinRingPaint = stroke(Color.parseColor("#22FF6E"), 2.4f);

        pocketGlowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        pocketGlowPaint.setColor(Color.parseColor("#22C55E"));
        pocketGlowPaint.setStyle(Paint.Style.FILL);
        pocketGlowPaint.setShadowLayer(16 * dp, 0, 0, Color.parseColor("#AA22C55E"));

        strikerFillPaint  = fill(Color.parseColor("#66FFFFFF"));
        strikerRingPaint  = stroke(Color.parseColor("#FFD700"), 2.4f);

        coinOutlinePaint  = stroke(Color.parseColor("#99FFFFFF"), 1.5f);
        blackFillPaint    = fill(Color.parseColor("#AA111111"));
        whiteFillPaint    = fill(Color.parseColor("#55FFFFFF"));
        redFillPaint      = fill(Color.parseColor("#AAFF3D71"));

        pocketFillPaint   = fill(Color.parseColor("#6622C55E"));

        targetCrossPaint  = stroke(Color.WHITE, 1.8f);
        targetCrossPaint.setAlpha(200);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(13 * dp);
        textPaint.setShadowLayer(3 * dp, 0, 0, Color.BLACK);

        boardOutlinePaint = stroke(Color.parseColor("#44FFD700"), 1.2f);
        boardOutlinePaint.setPathEffect(new DashPathEffect(new float[]{8*dp, 6*dp}, 0));

        dragHintPaint     = stroke(Color.parseColor("#88FFD700"), 1.0f);
        dragHintPaint.setPathEffect(new DashPathEffect(new float[]{5*dp, 4*dp}, 0));

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setShotMode(String mode)             { shotMode = mode; postInvalidate(); }
    public void setMarginOffset(float dx, float dy)  { marginOffsetX = dx; marginOffsetY = dy; postInvalidate(); }
    public void setSensitivity(float v)              { sensitivity = Math.max(0.3f, Math.min(v, 3.0f)); postInvalidate(); }
    public void setStrikerMoveable(boolean m)        { strikerMoveable = m; postInvalidate(); }
    public void setSnapMode(boolean on)              { snapMode = on; clearSnap(); postInvalidate(); }

    public void setDetectedState(GameState s) {
        this.detected = s;
        if (!draggingStriker) manualStriker = null;
        postInvalidate();
    }

    private void clearSnap() { snapCoin = null; snapPocket = null; }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float ex = event.getX();
        float ey = event.getY();
        GameState s = currentState();
        PointF strikerPos = effectiveStriker(s);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                // Priority 1: drag striker if touching it
                if (strikerMoveable && strikerPos != null) {
                    float r = (s != null && s.striker != null) ? s.striker.radius : 20 * dp;
                    if (dist(ex, ey, strikerPos.x, strikerPos.y) < r * 2.2f) {
                        draggingStriker = true;
                        manualStriker = new PointF(ex, ey);
                        clearSnap();
                        postInvalidate();
                        return true;
                    }
                }
                draggingStriker = false;

                // Priority 2: snap-to-coin if near a coin
                if (snapMode && s != null && strikerPos != null) {
                    Coin nearby = findNearestCoin(s.coins, ex, ey);
                    if (nearby != null) {
                        applySnap(nearby, s.pockets, strikerPos, s.striker != null ? s.striker.radius : 18*dp);
                        postInvalidate();
                        return true;
                    }
                }

                // Priority 3: normal aim target
                clearSnap();
                targetPos = new PointF(ex + marginOffsetX, ey + marginOffsetY);
                postInvalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (draggingStriker) {
                    manualStriker = new PointF(ex, ey);
                } else if (!snapMode) {
                    // In snap mode, dragging doesn't change aim — user must tap coins
                    targetPos = new PointF(ex + marginOffsetX, ey + marginOffsetY);
                    clearSnap();
                }
                postInvalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                draggingStriker = false;
                postInvalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /**
     * Find the coin whose center is within 2.5× its radius of the touch point.
     * Returns null if none is close enough.
     */
    private Coin findNearestCoin(List<Coin> coins, float tx, float ty) {
        Coin best = null;
        float bestDist = Float.MAX_VALUE;
        for (Coin c : coins) {
            float d = dist(tx, ty, c.pos.x, c.pos.y);
            float threshold = c.radius * 2.5f;
            if (d < threshold && d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    /**
     * Compute and apply the perfect snap-to-pocket aim for a given coin.
     *
     * Physics insight:
     *   To pocket coin C into pocket P, the coin must travel along the vector C→P.
     *   The striker must arrive at the "contact point" on the coin opposite to P:
     *     contactPt = C - normalize(P - C) * (coinR + strikerR)
     *   We set targetPos = striker + normalize(contactPt - striker) * FAR
     *   so the simulator naturally drives the striker to that contact point.
     *
     *   Best pocket = fewest blocking coins on the C→P line.
     *   For each other coin O, we check if the perpendicular distance from O's
     *   center to the segment C→P is less than O.radius (i.e. O physically blocks
     *   the path). The pocket with zero (or fewest) blockers is chosen.
     *   Tie-break: shorter distance wins.
     */
    private void applySnap(Coin coin, List<PointF> pockets, PointF strikerPos, float strikerR) {
        if (pockets == null || pockets.isEmpty()) return;

        // Find clearest pocket — fewest blocking coins, then shortest distance
        PointF bestPocket  = null;
        int    bestBlocked = Integer.MAX_VALUE;
        float  bestDist    = Float.MAX_VALUE;

        for (PointF p : pockets) {
            int blocked = countBlockers(coin, p);
            float d = dist(coin.pos.x, coin.pos.y, p.x, p.y);
            if (blocked < bestBlocked || (blocked == bestBlocked && d < bestDist)) {
                bestBlocked = blocked;
                bestDist    = d;
                bestPocket  = p;
            }
        }
        if (bestPocket == null) return;

        // Direction coin must travel: coin → pocket
        float cpDx = bestPocket.x - coin.pos.x;
        float cpDy = bestPocket.y - coin.pos.y;
        float cpLen = (float) Math.sqrt(cpDx*cpDx + cpDy*cpDy);
        if (cpLen < 1) return;
        float nx = cpDx / cpLen;  // normalized coin-to-pocket direction
        float ny = cpDy / cpLen;

        // Contact point = on the coin, on the striker's side (opposite pocket)
        float contactX = coin.pos.x - nx * (coin.radius + strikerR);
        float contactY = coin.pos.y - ny * (coin.radius + strikerR);

        // Target = far point along striker → contact line
        float dirX = contactX - strikerPos.x;
        float dirY = contactY - strikerPos.y;
        float dirLen = (float) Math.sqrt(dirX*dirX + dirY*dirY);
        if (dirLen < 1) return;
        float normDirX = dirX / dirLen;
        float normDirY = dirY / dirLen;

        float FAR = 3000f;
        targetPos = new PointF(
                strikerPos.x + normDirX * FAR + marginOffsetX,
                strikerPos.y + normDirY * FAR + marginOffsetY);

        snapCoin   = coin;
        snapPocket = bestPocket;
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        GameState s = currentState();
        if (s == null) s = synthFallback();
        if (s == null || s.striker == null) {
            drawHint(canvas, "Waiting for board detection…");
            return;
        }

        // Board outline
        if (s.board != null) canvas.drawRect(s.board, boardOutlinePaint);

        // Pockets
        for (PointF p : s.pockets) {
            canvas.drawCircle(p.x, p.y, 13 * dp, pocketFillPaint);
        }

        // Coins — highlight snapped coin with lime ring
        for (Coin c : s.coins) {
            Paint fill = (c.color == Coin.COLOR_BLACK) ? blackFillPaint
                       : (c.color == Coin.COLOR_RED)   ? redFillPaint
                                                       : whiteFillPaint;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, fill);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
            if (snapMode && snapCoin != null &&
                    c.pos.x == snapCoin.pos.x && c.pos.y == snapCoin.pos.y) {
                canvas.drawCircle(c.pos.x, c.pos.y, c.radius + 4*dp, snapCoinRingPaint);
            }
        }

        // Snap line: coin → best pocket
        if (snapMode && snapCoin != null && snapPocket != null) {
            canvas.drawLine(snapCoin.pos.x, snapCoin.pos.y,
                    snapPocket.x, snapPocket.y, snapLinePaint);
            canvas.drawCircle(snapPocket.x, snapPocket.y, 18 * dp, pocketGlowPaint);
        }

        // Striker
        PointF strikerPos = effectiveStriker(s);
        float  strikerR   = s.striker.radius;
        canvas.drawCircle(strikerPos.x, strikerPos.y, strikerR, strikerFillPaint);
        canvas.drawCircle(strikerPos.x, strikerPos.y, strikerR, strikerRingPaint);
        if (strikerMoveable) {
            canvas.drawCircle(strikerPos.x, strikerPos.y, strikerR * 2.0f, dragHintPaint);
        }

        if (targetPos == null) {
            if (snapMode) {
                drawHint(canvas, "Tap a coin to snap aim → pocket");
            } else {
                drawHint(canvas, "Drag striker  •  Tap board to aim");
            }
            return;
        }

        // Target crosshair
        float cr = 9 * dp, cl = 14 * dp;
        canvas.drawCircle(targetPos.x, targetPos.y, cr, targetCrossPaint);
        canvas.drawLine(targetPos.x-cl, targetPos.y, targetPos.x+cl, targetPos.y, targetCrossPaint);
        canvas.drawLine(targetPos.x, targetPos.y-cl, targetPos.x, targetPos.y+cl, targetCrossPaint);

        // ONE aim line from striker → target
        canvas.drawLine(strikerPos.x, strikerPos.y, targetPos.x, targetPos.y, aimLinePaint);
        drawAngleLabel(canvas, strikerPos, targetPos);

        // Simulate
        Coin tempStriker = new Coin(strikerPos.x, strikerPos.y, strikerR,
                Coin.COLOR_STRIKER, true);
        List<TrajectorySimulator.PathSegment> paths = simulator.simulate(
                tempStriker, targetPos, s.coins, s.pockets, s.board, sensitivity);

        for (TrajectorySimulator.PathSegment seg : paths) {
            if (!shouldDraw(seg)) continue;
            Paint paint = (seg.kind == 0) ? aimLinePaint : coinPathPaint;
            drawPolyline(canvas, seg.points, paint);
            if (seg.enteredPocket && !seg.points.isEmpty()) {
                PointF end = seg.points.get(seg.points.size() - 1);
                canvas.drawCircle(end.x, end.y, 20 * dp, pocketGlowPaint);
            }
        }

        // Snap mode label
        if (snapMode) {
            Paint snapLabel = new Paint(textPaint);
            snapLabel.setColor(Color.parseColor("#22FF6E"));
            snapLabel.setTextSize(11 * dp);
            canvas.drawText("SNAP MODE — tap a coin", 24*dp, 80*dp, snapLabel);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean shouldDraw(TrajectorySimulator.PathSegment seg) {
        switch (shotMode) {
            case MODE_DIRECT: return seg.kind == 0 && seg.wallBounces == 0;
            case MODE_GOLDEN: return seg.wallBounces <= 1;
            case MODE_LUCKY:  return seg.wallBounces <= 2;
            default: return true;
        }
    }

    private void drawPolyline(Canvas c, List<PointF> pts, Paint p) {
        if (pts.size() < 2) return;
        for (int i = 1; i < pts.size(); i++) {
            PointF a = pts.get(i-1), b = pts.get(i);
            c.drawLine(a.x, a.y, b.x, b.y, p);
        }
    }

    private void drawAngleLabel(Canvas canvas, PointF from, PointF to) {
        double angle = Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
        if (angle < 0) angle += 360;
        canvas.drawText(String.format("%.1f°", angle),
                (from.x+to.x)/2f + 10*dp, (from.y+to.y)/2f - 10*dp, textPaint);
    }

    private void drawHint(Canvas canvas, String msg) {
        canvas.drawText(msg, 24 * dp, 60 * dp, textPaint);
    }

    private GameState currentState() { return detected; }

    private PointF effectiveStriker(GameState s) {
        if (manualStriker != null) return manualStriker;
        if (s != null && s.striker != null) return s.striker.pos;
        return null;
    }

    /**
     * Count how many OTHER coins physically block the line from coin.pos → pocket.
     *
     * A coin O blocks the segment A→B if the closest point on A→B to O.center
     * is within O.radius distance of O.center AND that closest point lies
     * between A and B (not outside the segment).
     */
    private int countBlockers(Coin coin, PointF pocket) {
        if (detected == null) return 0;
        float ax = coin.pos.x, ay = coin.pos.y;
        float bx = pocket.x,   by = pocket.y;
        float abx = bx - ax,   aby = by - ay;
        float abLen2 = abx*abx + aby*aby;
        int count = 0;
        for (Coin o : detected.coins) {
            // skip the coin we're aiming at
            if (o.pos.x == coin.pos.x && o.pos.y == coin.pos.y) continue;
            float apx = o.pos.x - ax, apy = o.pos.y - ay;
            // parameter t of the closest point on the line
            float t = (abLen2 > 0) ? (apx*abx + apy*aby) / abLen2 : 0f;
            t = Math.max(0f, Math.min(1f, t));
            // closest point on segment A→B to O.pos
            float cx = ax + t * abx;
            float cy = ay + t * aby;
            float d = dist(o.pos.x, o.pos.y, cx, cy);
            if (d < o.radius) count++;
        }
        return count;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2-x1, dy = y2-y1;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }

    private GameState synthFallback() {
        if (getWidth() == 0 || getHeight() == 0) return null;
        GameState s = new GameState();
        int w = getWidth(), h = getHeight();
        float side = Math.min(w, h) * 0.92f;
        float cx = w / 2f, cy = h / 2f;
        s.board = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        float inset = side * 0.04f;
        s.pockets.add(new PointF(s.board.left+inset,  s.board.top+inset));
        s.pockets.add(new PointF(s.board.right-inset, s.board.top+inset));
        s.pockets.add(new PointF(s.board.left+inset,  s.board.bottom-inset));
        s.pockets.add(new PointF(s.board.right-inset, s.board.bottom-inset));
        s.coins = new ArrayList<>();
        float r = 14 * dp;
        for (int i = -2; i <= 2; i++) {
            for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) continue;
                int color = ((Math.abs(i)+Math.abs(j))&1)==0 ? Coin.COLOR_BLACK : Coin.COLOR_WHITE;
                s.coins.add(new Coin(cx+i*40*dp, cy+j*40*dp, r, color, false));
            }
        }
        s.coins.add(new Coin(cx, cy, r, Coin.COLOR_RED, false));
        s.striker = new Coin(cx, s.board.bottom-80*dp, 18*dp, Coin.COLOR_STRIKER, true);
        return s;
    }

    private Paint stroke(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w*dp); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL);
        return p;
    }
}
