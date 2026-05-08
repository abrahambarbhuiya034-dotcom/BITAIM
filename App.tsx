/**
 * BitAim v3.1 — Carrom Pool Aim Assistant
 *
 * v3.1 additions:
 *  - Snap-to-Coin mode: tap any detected coin while snap is ON and the aim line
 *    automatically computes the perfect angle to pocket that coin. A lime dotted
 *    line from coin → pocket confirms the intended path.
 *
 * v3.0 features:
 *  - Striker is moveable — drag the gold-ringed striker to reposition.
 *  - Auto-detect board size from wooden frame color.
 *  - Single clean cyan aim line (+ one orange deflection line when hitting a coin).
 *  - Multi-threshold detection for stronger piece recognition.
 */

import React, {useState, useEffect, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  Switch,
  ScrollView,
  Platform,
  StatusBar,
  NativeModules,
  Linking,
} from 'react-native';
import Slider from '@react-native-community/slider';

const {OverlayModule} = NativeModules;

type ShotMode = 'ALL' | 'DIRECT' | 'AI' | 'GOLDEN' | 'LUCKY';

interface MarginSettings {
  d2X: number; d2Y: number;
  e2X: number; e2Y: number;
  insideX: number; insideY: number;
}

const SHOT_MODES: {mode: ShotMode; label: string; desc: string}[] = [
  {mode: 'ALL',    label: 'All Lines', desc: 'Striker + best coin shot'},
  {mode: 'DIRECT', label: 'Direct',    desc: 'Striker direct line only'},
  {mode: 'AI',     label: 'AI Aim',    desc: 'Best AI-ranked trajectory'},
  {mode: 'GOLDEN', label: 'Golden',    desc: 'Up to one cushion bounce'},
  {mode: 'LUCKY',  label: 'Lucky',     desc: 'Up to two cushion bounces'},
];

export default function App() {
  const [hasOverlay, setHasOverlay]             = useState(false);
  const [overlayActive, setOverlayActive]       = useState(false);
  const [autoDetect, setAutoDetect]             = useState(false);
  const [strikerMoveable, setStrikerMoveable]   = useState(true);
  const [snapMode, setSnapMode]                 = useState(false);   // v3.1
  const [selectedMode, setSelectedMode]         = useState<ShotMode>('ALL');
  const [sensitivity, setSensitivity]           = useState(1.0);
  const [detectThreshold, setDetectThreshold]   = useState(20);
  const [margin, setMargin] = useState<MarginSettings>({
    d2X: 0, d2Y: 0, e2X: 0, e2Y: 0, insideX: 0, insideY: 0,
  });
  const [activeMarginTab, setActiveMarginTab] =
    useState<'D2' | 'E2' | 'INSIDE'>('D2');

  useEffect(() => {
    refreshStatus();
    const t = setInterval(refreshStatus, 2000);
    return () => clearInterval(t);
  }, []);

  const refreshStatus = useCallback(async () => {
    try {
      const can = await OverlayModule.canDrawOverlays();
      setHasOverlay(can);
    } catch { setHasOverlay(true); }
    try {
      const active = await OverlayModule.isAutoDetectActive();
      setAutoDetect(active);
    } catch {}
  }, []);

  const requestOverlay = useCallback(() => {
    try {
      OverlayModule.requestOverlayPermission();
      setTimeout(refreshStatus, 1500);
    } catch {
      Alert.alert('Permission Needed',
        'Please grant "Display over other apps" in Settings.',
        [{text: 'Open Settings', onPress: () => Linking.openSettings()}]);
    }
  }, [refreshStatus]);

  const toggleOverlay = useCallback(async () => {
    if (!hasOverlay) { requestOverlay(); return; }
    try {
      if (overlayActive) {
        await OverlayModule.stopOverlay();
        setOverlayActive(false);
        setAutoDetect(false);
      } else {
        await OverlayModule.startOverlay();
        setOverlayActive(true);
        try { OverlayModule.setStrikerMoveable(strikerMoveable); } catch {}
        try { OverlayModule.setSnapMode(snapMode); } catch {}
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle overlay');
    }
  }, [hasOverlay, overlayActive, strikerMoveable, snapMode, requestOverlay]);

  const toggleAutoDetect = useCallback(async () => {
    if (!overlayActive) {
      Alert.alert('Start Overlay First',
        'Turn on the Aim Overlay before enabling auto-detect.');
      return;
    }
    try {
      if (autoDetect) {
        await OverlayModule.stopScreenCapture();
        setAutoDetect(false);
      } else {
        await OverlayModule.requestScreenCapture();
        setTimeout(refreshStatus, 2500);
      }
    } catch (e: any) {
      Alert.alert('Error', e.message || 'Could not toggle screen capture');
    }
  }, [overlayActive, autoDetect, refreshStatus]);

  const toggleStrikerMoveable = useCallback((val: boolean) => {
    setStrikerMoveable(val);
    try { OverlayModule.setStrikerMoveable(val); } catch {}
  }, []);

  const toggleSnapMode = useCallback((val: boolean) => {
    setSnapMode(val);
    try { OverlayModule.setSnapMode(val); } catch {}
  }, []);

  const handleModeSelect = useCallback((mode: ShotMode) => {
    setSelectedMode(mode);
    try { OverlayModule.setShotMode(mode); } catch {}
  }, []);

  const handleSensitivityChange = useCallback((val: number) => {
    setSensitivity(val);
    try { OverlayModule.setSensitivity(val); } catch {}
  }, []);

  const handleThresholdChange = useCallback((val: number) => {
    setDetectThreshold(val);
    try { OverlayModule.setDetectionThreshold(val); } catch {}
  }, []);

  const handleMarginChange = useCallback(
    (axis: 'X' | 'Y', value: number) => {
      const key = `${activeMarginTab.toLowerCase()}${axis}` as keyof MarginSettings;
      const updated = {...margin, [key]: value};
      setMargin(updated);
      try { OverlayModule.setMarginOffset(updated.d2X, updated.d2Y); } catch {}
    },
    [activeMarginTab, margin],
  );

  const getActiveMargin = () => {
    switch (activeMarginTab) {
      case 'D2':     return {x: margin.d2X, y: margin.d2Y};
      case 'E2':     return {x: margin.e2X, y: margin.e2Y};
      case 'INSIDE': return {x: margin.insideX, y: margin.insideY};
    }
  };

  return (
    <View style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0D0D1A" />

      <View style={styles.header}>
        <Text style={styles.logo}>Bit-Aim</Text>
        <Text style={styles.subtitle}>Carrom Aim Assist • v3.1 • Snap-to-Coin</Text>
      </View>

      <ScrollView style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>

        {/* Permission banner */}
        {!hasOverlay && (
          <TouchableOpacity style={styles.permBanner} onPress={requestOverlay}>
            <Text style={styles.permBannerText}>
              Grant "Display over other apps" to use the overlay
            </Text>
            <Text style={styles.permBannerCta}>Tap to grant →</Text>
          </TouchableOpacity>
        )}

        {/* Main toggles */}
        <View style={styles.card}>
          <ToggleRow
            title="Aim Overlay"
            sub={overlayActive
              ? 'Running — tap floating icon in game to show lines'
              : 'Start to draw aim lines on top of Carrom Pool'}
            value={overlayActive}
            onToggle={toggleOverlay}
            color="#FFD700"
          />
          <Divider />
          <ToggleRow
            title="Auto-Detect Board + Pieces"
            sub={autoDetect
              ? 'Reading screen — board, striker and coins detected automatically'
              : 'Use CV to auto-detect board size and pieces (per-session permission)'}
            value={autoDetect}
            onToggle={toggleAutoDetect}
            color="#00E5FF"
          />
          <Divider />
          <ToggleRow
            title="Striker Moveable"
            sub={strikerMoveable
              ? 'Drag the gold-ringed striker to reposition it'
              : 'Striker locked — follows auto-detect only'}
            value={strikerMoveable}
            onToggle={toggleStrikerMoveable}
            color="#FF8A00"
          />
          <Divider />
          {/* v3.1: Snap-to-Coin */}
          <ToggleRow
            title="Snap-to-Coin  🎯"
            sub={snapMode
              ? 'Tap any coin — aim line snaps to perfect pocket angle automatically'
              : 'Normal tap-to-aim mode'}
            value={snapMode}
            onToggle={toggleSnapMode}
            color="#22FF6E"
          />
        </View>

        {/* Snap-to-coin info card — shown when snap is on */}
        {snapMode && (
          <View style={[styles.card, styles.snapCard]}>
            <Text style={styles.snapCardTitle}>Snap-to-Coin Active</Text>
            <Text style={styles.snapCardBody}>
              Tap directly on any detected coin (white, black or red queen) and
              the aim line will automatically compute the ideal angle to pocket it.{'\n\n'}
              A lime dotted line shows the coin path to the chosen pocket.{'\n'}
              Tapping empty board space sets a normal aim point.
            </Text>
            <View style={styles.snapLegend}>
              <View style={[styles.snapSwatch, {backgroundColor: '#00E5FF'}]} />
              <Text style={styles.snapLegendText}>Striker aim line</Text>
              <View style={[styles.snapSwatch, {backgroundColor: '#22FF6E', marginLeft: 16}]} />
              <Text style={styles.snapLegendText}>Coin → Pocket path</Text>
            </View>
          </View>
        )}

        {/* Shot mode */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Prediction Lines</Text>
          <Text style={styles.cardSub}>
            One cyan line from striker. Orange line shows struck coin's path.
          </Text>
          <View style={styles.shotGrid}>
            {SHOT_MODES.map(({mode, label, desc}) => (
              <TouchableOpacity key={mode}
                style={[styles.shotBtn, selectedMode === mode && styles.shotBtnActive]}
                onPress={() => handleModeSelect(mode)}>
                <Text style={[styles.shotLabel, selectedMode === mode && styles.shotLabelActive]}>
                  {label}
                </Text>
                <Text style={styles.shotDesc}>{desc}</Text>
              </TouchableOpacity>
            ))}
          </View>
          <View style={styles.legend}>
            <LegendDot color="#00E5FF" label="Aim" />
            <LegendDot color="#FF8A00" label="Coin" />
            <LegendDot color="#22C55E" label="Pocket!" />
            <LegendDot color="#22FF6E" label="Snap path" />
          </View>
        </View>

        {/* Shot power */}
        <View style={styles.card}>
          <View style={styles.rowSpread}>
            <Text style={styles.cardTitle}>Shot Power</Text>
            <Text style={styles.valueLabel}>{sensitivity.toFixed(1)}x</Text>
          </View>
          <Slider style={styles.slider}
            minimumValue={0.3} maximumValue={3.0} step={0.1}
            value={sensitivity} onValueChange={handleSensitivityChange}
            minimumTrackTintColor="#FFD700" maximumTrackTintColor="#333"
            thumbTintColor="#FFD700" />
          <View style={styles.rowSpread}>
            <Text style={styles.sliderEndLabel}>Soft</Text>
            <Text style={styles.sliderEndLabel}>Hard</Text>
          </View>
        </View>

        {/* Detection sensitivity */}
        <View style={styles.card}>
          <View style={styles.rowSpread}>
            <Text style={styles.cardTitle}>Detection Sensitivity</Text>
            <Text style={[styles.valueLabel, {color: '#00E5FF'}]}>{detectThreshold}</Text>
          </View>
          <Text style={styles.cardSub}>
            Lower = more circles detected. Higher = only the clearest ones.
          </Text>
          <Slider style={styles.slider}
            minimumValue={8} maximumValue={50} step={1}
            value={detectThreshold} onValueChange={handleThresholdChange}
            minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
            thumbTintColor="#00E5FF" />
          <View style={styles.rowSpread}>
            <Text style={styles.sliderEndLabel}>Sensitive</Text>
            <Text style={styles.sliderEndLabel}>Strict</Text>
          </View>
        </View>

        {/* Fine-tune offset */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Fine-Tune Offset</Text>
          <Text style={styles.cardSub}>
            Nudge aim point if screen reports a small offset.
          </Text>
          <View style={styles.tabRow}>
            {(['D2', 'E2', 'INSIDE'] as const).map(tab => (
              <TouchableOpacity key={tab}
                style={[styles.tab, activeMarginTab === tab && styles.tabActive]}
                onPress={() => setActiveMarginTab(tab)}>
                <Text style={[styles.tabText, activeMarginTab === tab && styles.tabTextActive]}>
                  {tab}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
          <View style={styles.marginRow}>
            <Text style={styles.marginLabel}>
              X: <Text style={styles.marginValue}>{getActiveMargin().x.toFixed(1)}</Text>
            </Text>
            <Slider style={styles.slider}
              minimumValue={-30} maximumValue={30} step={0.5}
              value={getActiveMargin().x}
              onValueChange={v => handleMarginChange('X', v)}
              minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
              thumbTintColor="#00E5FF" />
          </View>
          <View style={styles.marginRow}>
            <Text style={styles.marginLabel}>
              Y: <Text style={styles.marginValue}>{getActiveMargin().y.toFixed(1)}</Text>
            </Text>
            <Slider style={styles.slider}
              minimumValue={-30} maximumValue={30} step={0.5}
              value={getActiveMargin().y}
              onValueChange={v => handleMarginChange('Y', v)}
              minimumTrackTintColor="#00E5FF" maximumTrackTintColor="#333"
              thumbTintColor="#00E5FF" />
          </View>
          <TouchableOpacity style={styles.resetBtn}
            onPress={() => {
              const r: MarginSettings = {d2X:0,d2Y:0,e2X:0,e2Y:0,insideX:0,insideY:0};
              setMargin(r);
              try { OverlayModule.setMarginOffset(0, 0); } catch {}
            }}>
            <Text style={styles.resetBtnText}>Reset</Text>
          </TouchableOpacity>
        </View>

        {/* How to use */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>How to Use</Text>
          <HowStep n="1" text='Grant "Draw over apps" permission' />
          <HowStep n="2" text="Turn on Aim Overlay" />
          <HowStep n="3" text="Turn on Auto-Detect (screen capture per session)" />
          <HowStep n="4" text="Open Carrom Pool" />
          <HowStep n="5" text="Tap floating icon to toggle aim lines" />
          <HowStep n="6" text="Drag the gold-ringed striker to reposition it" highlight />
          <HowStep n="7" text="Tap the board to set a normal aim target" />
          <HowStep n="8" text='Enable "Snap-to-Coin" and tap any coin for auto-pocket aim' highlight />
          <View style={styles.tipBox}>
            <Text style={styles.tipText}>
              Tip: Snap-to-Coin picks the closest pocket. The lime dotted line
              shows where the coin will travel. Works best when auto-detect
              is on and all coins are detected.
            </Text>
          </View>
        </View>

        <View style={styles.footer}>
          <Text style={styles.footerText}>
            Bit-Aim v3.1 • Snap-to-Coin • Moveable Striker • Auto Board Detect
          </Text>
        </View>
      </ScrollView>
    </View>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function ToggleRow({title, sub, value, onToggle, color}: {
  title: string; sub: string; value: boolean;
  onToggle: (v: boolean) => void; color: string;
}) {
  return (
    <View style={styles.row}>
      <View style={{flex: 1, paddingRight: 10}}>
        <Text style={styles.cardTitle}>{title}</Text>
        <Text style={styles.cardSub}>{sub}</Text>
      </View>
      <Switch value={value} onValueChange={onToggle}
        trackColor={{false: '#333', true: color}}
        thumbColor={value ? '#FFF' : '#888'} />
    </View>
  );
}

function Divider() {
  return <View style={{height: 1, backgroundColor: '#222244', marginVertical: 12}} />;
}

function LegendDot({color, label}: {color: string; label: string}) {
  return (
    <View style={styles.legendItem}>
      <View style={[styles.legendSwatch, {backgroundColor: color}]} />
      <Text style={styles.legendLabel}>{label}</Text>
    </View>
  );
}

function HowStep({n, text, highlight}: {n: string; text: string; highlight?: boolean}) {
  return (
    <View style={styles.stepRow}>
      <View style={[styles.stepBadge, highlight && styles.stepBadgeHL]}>
        <Text style={[styles.stepNum, highlight && {color: '#22FF6E'}]}>{n}</Text>
      </View>
      <Text style={[styles.howToStep, highlight && {color: '#22FF6E', fontWeight: '700'}]}>
        {text}
      </Text>
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#0D0D1A'},
  header: {
    paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight ?? 24 : 44,
    paddingBottom: 16, paddingHorizontal: 20,
    backgroundColor: '#13132A', borderBottomWidth: 1, borderBottomColor: '#222244',
  },
  logo:     {color: '#FFD700', fontSize: 26, fontWeight: '900', letterSpacing: 1},
  subtitle: {color: '#8888BB', fontSize: 12, marginTop: 2},
  scroll:        {flex: 1},
  scrollContent: {padding: 16, paddingBottom: 40},
  permBanner: {
    backgroundColor: '#2A1A00', borderWidth: 1, borderColor: '#FFD700',
    borderRadius: 10, padding: 14, marginBottom: 12,
  },
  permBannerText: {color: '#FFC', fontSize: 13},
  permBannerCta:  {color: '#FFD700', fontSize: 13, fontWeight: '700', marginTop: 4},
  card: {
    backgroundColor: '#16162E', borderRadius: 14, padding: 16,
    marginBottom: 14, borderWidth: 1, borderColor: '#222244',
  },
  snapCard:        {borderColor: '#22FF6E44', backgroundColor: '#071A0E'},
  snapCardTitle:   {color: '#22FF6E', fontSize: 15, fontWeight: '700', marginBottom: 8},
  snapCardBody:    {color: '#A7F3D0', fontSize: 12, lineHeight: 18},
  snapLegend:      {flexDirection: 'row', alignItems: 'center', marginTop: 12},
  snapSwatch:      {width: 14, height: 4, borderRadius: 2, marginRight: 6},
  snapLegendText:  {color: '#A7F3D0', fontSize: 11},
  cardTitle: {color: '#FFFFFF', fontSize: 15, fontWeight: '700', marginBottom: 3},
  cardSub:   {color: '#8888BB', fontSize: 12, marginBottom: 4},
  row:       {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  rowSpread: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center'},
  shotGrid:  {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginTop: 8},
  shotBtn: {
    width: '47%', backgroundColor: '#1E1E3A', borderRadius: 10, padding: 12,
    borderWidth: 1.5, borderColor: '#333355', alignItems: 'flex-start',
  },
  shotBtnActive:   {borderColor: '#00E5FF', backgroundColor: '#00151F'},
  shotLabel:       {color: '#AAA', fontSize: 14, fontWeight: '700'},
  shotLabelActive: {color: '#00E5FF'},
  shotDesc:        {color: '#666688', fontSize: 10, marginTop: 3},
  legend:          {flexDirection: 'row', flexWrap: 'wrap', gap: 10, marginTop: 12},
  legendItem:      {flexDirection: 'row', alignItems: 'center'},
  legendSwatch:    {width: 14, height: 4, borderRadius: 2, marginRight: 6},
  legendLabel:     {color: '#AAA', fontSize: 11},
  slider:          {width: '100%', height: 36},
  sliderEndLabel:  {color: '#666688', fontSize: 11},
  valueLabel:      {color: '#FFD700', fontSize: 16, fontWeight: '700'},
  tabRow:  {flexDirection: 'row', gap: 8, marginVertical: 10},
  tab: {
    flex: 1, paddingVertical: 8, borderRadius: 8,
    backgroundColor: '#1E1E3A', alignItems: 'center',
    borderWidth: 1, borderColor: '#333355',
  },
  tabActive:     {backgroundColor: '#00293A', borderColor: '#00E5FF'},
  tabText:       {color: '#8888BB', fontSize: 13, fontWeight: '600'},
  tabTextActive: {color: '#00E5FF'},
  marginRow:   {marginBottom: 8},
  marginLabel: {color: '#AAA', fontSize: 13, marginBottom: 2},
  marginValue: {color: '#00E5FF', fontWeight: '700'},
  resetBtn: {
    marginTop: 6, paddingVertical: 8, borderRadius: 8,
    backgroundColor: '#1E1E3A', alignItems: 'center',
    borderWidth: 1, borderColor: '#444466',
  },
  resetBtnText: {color: '#FF7777', fontSize: 13, fontWeight: '600'},
  stepRow:     {flexDirection: 'row', alignItems: 'flex-start', marginBottom: 8},
  stepBadge: {
    width: 22, height: 22, borderRadius: 11, backgroundColor: '#1E1E3A',
    alignItems: 'center', justifyContent: 'center', marginRight: 10, marginTop: 1,
    borderWidth: 1, borderColor: '#333355',
  },
  stepBadgeHL: {borderColor: '#22FF6E'},
  stepNum:     {color: '#8888BB', fontSize: 11, fontWeight: '700'},
  howToStep:   {color: '#CCCCEE', fontSize: 13, flex: 1},
  tipBox:      {
    backgroundColor: '#071A0E', padding: 10, borderRadius: 8, marginTop: 8,
    borderWidth: 1, borderColor: '#22FF6E44',
  },
  tipText:  {color: '#A7F3D0', fontSize: 12},
  footer:   {alignItems: 'center', marginTop: 10},
  footerText: {color: '#444466', fontSize: 11},
});
