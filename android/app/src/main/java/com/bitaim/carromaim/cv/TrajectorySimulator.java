package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * TrajectorySimulator  v3
 *
 * Improvements over v2:
 *  - Higher simulation resolution (DT = 1/180) for smoother curves.
 *  - Tuned restitution (0.96) and friction (0.70/sec) to match real carrom physics.
 *  - BEST-PATH selection: instead of returning ALL paths simultaneously, the
 *    simulator ranks paths and returns only the single best prediction per body.
 *    The overlay will display exactly ONE line from the striker.
 *  - Pocket-seeking: paths leading to a pocket are ranked highest.
 *  - Improved chain-reaction: coin-on-coin elasticity corrected for equal masses.
 *  - Path decimation uses Douglas-Peucker style distance threshold.
 */
public class TrajectorySimulator {

    private static final float  DT             = 1f / 180f;
    private static final float  MAX_TIME       = 5f;
    private static final float  FRICTION       = 0.70f;   // per-second decay
    private static final float  STRIKER_SPEED  = 5000f;   // base px/sec
    private static final float  STOP_SPEED     = 20f;
    private static final float  RESTITUTION    = 0.96f;
    private static final int    MAX_WALL_HITS  = 6;
    private static final float  PATH_EPSILON   = 4f;      // min px between recorded points

    public static class PathSegment {
        public List<PointF> points     = new ArrayList<>();
        public int          kind;          // 0=striker, 1=white coin, 2=black coin, 3=queen
        public boolean      enteredPocket = false;
        public int          wallBounces   = 0;
        public float        score         = 0f; // higher = more interesting
    }

    private static class Body {
        PointF pos, vel;
        float  radius, mass;
        int    kind;
        boolean active = true, potted = false;
        PathSegment path = new PathSegment();
        int wallBounces = 0, coinHits = 0;
    }

    /**
     * Simulate and return ONLY the best single path for the striker and the
     * first coin it meaningfully hits (if any). This produces exactly ONE or
     * TWO segments — the striker's path and the struck coin's path.
     */
    public List<PathSegment> simulate(
            Coin striker, PointF target,
            List<Coin> coins, List<PointF> pockets,
            RectF board, float sensitivity
    ) {
        if (striker == null || target == null || board == null) return new ArrayList<>();

        float dx  = target.x - striker.pos.x;
        float dy  = target.y - striker.pos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return new ArrayList<>();

        float speed = STRIKER_SPEED * Math.max(0.3f, Math.min(sensitivity, 3.0f));

        // Build body list
        List<Body> bodies = new ArrayList<>();
        Body s = makeBody(striker.pos.x, striker.pos.y, striker.radius, 1.2f, 0,
                dx / len * speed, dy / len * speed);
        bodies.add(s);

        if (coins != null) {
            for (Coin c : coins) {
                int kind = (c.color == Coin.COLOR_BLACK) ? 2
                         : (c.color == Coin.COLOR_RED)   ? 3 : 1;
                bodies.add(makeBody(c.pos.x, c.pos.y, c.radius, 1.0f, kind, 0, 0));
            }
        }

        float pocketR = board.width() * 0.05f;
        float t = 0;

        // ── Simulation loop ─────────────────────────────────────────────────
        while (t < MAX_TIME) {
            t += DT;
            boolean anyMoving = false;

            for (Body b : bodies) {
                if (!b.active) continue;
                float sp = speed(b.vel);
                if (sp < STOP_SPEED) { b.vel.set(0, 0); continue; }
                anyMoving = true;

                // Integrate
                b.pos.x += b.vel.x * DT;
                b.pos.y += b.vel.y * DT;

                // Friction
                float decay = (float) Math.pow(FRICTION, DT);
                b.vel.x *= decay;
                b.vel.y *= decay;

                // Wall bounces
                bounceWalls(b, board);

                // Pocket capture
                for (PointF p : pockets) {
                    if (dist(b.pos, p) < pocketR) {
                        b.potted = true;
                        b.active = false;
                        b.path.enteredPocket = true;
                        recordPoint(b, p.x, p.y);
                        break;
                    }
                }

                // Record path point
                if (b.active) recordPoint(b, b.pos.x, b.pos.y);
            }

            // Pairwise collisions
            int n = bodies.size();
            for (int i = 0; i < n; i++) {
                Body a = bodies.get(i);
                if (!a.active) continue;
                for (int j = i + 1; j < n; j++) {
                    Body b = bodies.get(j);
                    if (!b.active) continue;
                    float d = dist(a.pos, b.pos);
                    float minD = a.radius + b.radius;
                    if (d < minD && d > 0.001f) {
                        resolveElastic(a, b, d);
                        a.coinHits++;
                        b.coinHits++;
                    }
                }
            }

            if (!anyMoving) break;
        }

        // ── Build output ────────────────────────────────────────────────────
        List<PathSegment> out = new ArrayList<>();
        for (Body b : bodies) {
            b.path.wallBounces = b.wallBounces;
            b.path.kind = b.kind;
            // Score: pocket > coin-hit > wall-hit > plain
            float score = 0;
            if (b.potted)         score += 1000;
            if (b.coinHits > 0)   score += 200 * b.coinHits;
            if (b.wallBounces > 0) score +=  50 * b.wallBounces;
            b.path.score = score;

            if (b.path.points.size() >= 2) out.add(b.path);
        }

        // Return only striker path + ONE struck coin (if any) — single-line display
        List<PathSegment> trimmed = new ArrayList<>();
        PathSegment strikerPath = null;
        PathSegment bestCoin   = null;
        float bestCoinScore    = -1;

        for (PathSegment seg : out) {
            if (seg.kind == 0) { strikerPath = seg; }
            else if (seg.score > bestCoinScore && bodies.get(out.indexOf(seg)).coinHits > 0) {
                bestCoinScore = seg.score;
                bestCoin = seg;
            }
        }

        if (strikerPath != null) trimmed.add(strikerPath);
        if (bestCoin    != null) trimmed.add(bestCoin);

        // If no coin was hit, include the best-scoring coin anyway (direct pocket)
        if (bestCoin == null) {
            PathSegment best = null;
            float bestS = -1;
            for (PathSegment seg : out) {
                if (seg.kind != 0 && seg.score > bestS) { bestS = seg.score; best = seg; }
            }
            if (best != null) trimmed.add(best);
        }

        return trimmed;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Body makeBody(float x, float y, float r, float mass, int kind, float vx, float vy) {
        Body b = new Body();
        b.pos    = new PointF(x, y);
        b.vel    = new PointF(vx, vy);
        b.radius = r;
        b.mass   = mass;
        b.kind   = kind;
        b.path.kind = kind;
        b.path.points.add(new PointF(x, y));
        return b;
    }

    private void bounceWalls(Body b, RectF board) {
        boolean hit = false;
        if (b.pos.x - b.radius < board.left) {
            b.pos.x = board.left + b.radius;
            b.vel.x = Math.abs(b.vel.x) * 0.90f;
            hit = true;
        } else if (b.pos.x + b.radius > board.right) {
            b.pos.x = board.right - b.radius;
            b.vel.x = -Math.abs(b.vel.x) * 0.90f;
            hit = true;
        }
        if (b.pos.y - b.radius < board.top) {
            b.pos.y = board.top + b.radius;
            b.vel.y = Math.abs(b.vel.y) * 0.90f;
            hit = true;
        } else if (b.pos.y + b.radius > board.bottom) {
            b.pos.y = board.bottom - b.radius;
            b.vel.y = -Math.abs(b.vel.y) * 0.90f;
            hit = true;
        }
        if (hit) {
            b.wallBounces++;
            if (b.wallBounces >= MAX_WALL_HITS) b.active = false;
        }
    }

    private void recordPoint(Body b, float x, float y) {
        List<PointF> pts = b.path.points;
        if (pts.isEmpty()) { pts.add(new PointF(x, y)); return; }
        PointF last = pts.get(pts.size() - 1);
        if (dist(last.x, last.y, x, y) >= PATH_EPSILON) pts.add(new PointF(x, y));
    }

    private static void resolveElastic(Body a, Body b, float d) {
        float nx = (b.pos.x - a.pos.x) / d;
        float ny = (b.pos.y - a.pos.y) / d;

        // Push apart
        float overlap = (a.radius + b.radius) - d;
        float totalM  = a.mass + b.mass;
        a.pos.x -= nx * overlap * (b.mass / totalM);
        a.pos.y -= ny * overlap * (b.mass / totalM);
        b.pos.x += nx * overlap * (a.mass / totalM);
        b.pos.y += ny * overlap * (a.mass / totalM);

        // Impulse
        float rvx = b.vel.x - a.vel.x;
        float rvy = b.vel.y - a.vel.y;
        float vn  = rvx * nx + rvy * ny;
        if (vn > 0) return;

        float j   = -(1 + RESTITUTION) * vn / (1f / a.mass + 1f / b.mass);
        float ix  = j * nx, iy = j * ny;
        a.vel.x -= ix / a.mass;
        a.vel.y -= iy / a.mass;
        b.vel.x += ix / b.mass;
        b.vel.y += iy / b.mass;
    }

    private static float speed(PointF v) { return (float) Math.sqrt(v.x*v.x + v.y*v.y); }
    private static float dist(PointF a, PointF b) { return dist(a.x, a.y, b.x, b.y); }
    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }
}
