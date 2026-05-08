package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

public class GameState {
    public Coin         striker; // may be null before detection
    public List<Coin>   coins   = new ArrayList<>();
    public List<PointF> pockets = new ArrayList<>();
    public RectF        board;   // auto-detected board rectangle
}
